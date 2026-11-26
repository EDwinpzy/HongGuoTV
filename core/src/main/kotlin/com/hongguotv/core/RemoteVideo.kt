// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo): Closeable {
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    private val mediaHttp=http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    data class Range(val bytes: ByteArray,val total: Long)
    var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    private class RetryableRangeException(message: String,cause: Throwable?=null): IOException(message,cause)
    private fun rangeOnce(start: Long,end: Long,deadline: Long): Range {
        if(closed) throw InterruptedIOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
        if(remaining<=0) throw SocketTimeoutException("视频读取超时")
        val request=Request.Builder().url(info.url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=mediaHttp.newCall(request).apply {
            // A media range is small and bounded. Do not let a dead CDN
            // connection pin the decoder past the logical range budget.
            timeout().timeout(minOf(REQUEST_TIMEOUT_MS,remaining),TimeUnit.MILLISECONDS)
        }
        synchronized(calls) { if(closed) throw InterruptedIOException("播放请求已取消"); calls+=call }
        try {
            return RequestScope.execute(call) { response ->
                val full: Long
                val expected: Int
                if(response.code==206) {
                    val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                        .matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw IOException("视频分段响应异常")
                    val (begin,finish,size)=match.destructured
                    full=size.toLong(); val last=minOf(end,full-1)
                    if(begin.toLong()!=start || finish.toLong()<begin.toLong() ||
                        finish.toLong()>last || full<=0)
                        throw IOException("视频分段范围不匹配")
                    if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                    expected=(minOf(last,finish.toLong())-start+1).toInt()
                } else if(response.code==200 && start==0L) {
                    // Some CDN edges ignore Range. Accept it only for the first
                    // block; a 200 for a later offset cannot be trusted.
                    full=response.body?.contentLength()?.takeIf { it>0 }
                        ?: throw IOException("视频资源长度未知")
                    expected=(minOf(end,full-1)+1).toInt()
                    if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                } else {
                    val message="视频服务器不支持分段读取（HTTP ${response.code}）"
                    if(response.code>=500 || response.code==408 || response.code==429)
                        throw RetryableRangeException(message)
                    throw IOException(message)
                }
                val body=response.body ?: throw RetryableRangeException("视频分段为空")
                val bytes=ByteArray(expected)
                var read=0
                try {
                    val input=body.byteStream()
                    while(read<expected) {
                        val count=input.read(bytes,read,expected-read)
                        if(count<0) break
                        read+=count
                    }
                } catch(e: IOException) {
                    // A short 206 is legal. Keep the bytes already received and
                    // let range() continue from the new absolute offset.
                    if(read==0) throw RetryableRangeException("视频分段读取中断",e)
                }
                if(read==0) throw RetryableRangeException("视频分段为空")
                Range(if(read==expected) bytes else bytes.copyOf(read),full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }
    /**
     * Fill one logical range across transient HTTP/IO failures and legal short 206
     * responses. CDNs sometimes close a Range body a little early; treating that as a
     * fatal error made ExoPlayer re-buffer instead of continuing from the next byte.
     */
    private fun range(start: Long,end: Long): Range {
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val output=ByteArrayOutputStream((end-start+1).coerceAtMost(1024*1024L).toInt())
        var position=start
        var full=0L
        var requests=0
        // A header probe inside the playback budget must stop at that deadline,
        // not at its own longer media budget (otherwise one dead CDN edge spent
        // 12s per tier on top of the ladder read, with no overall cap).
        val budget=minOf(MEDIA_BUDGET_MS,PlaybackRequestBudget.remainingMillis() ?: MEDIA_BUDGET_MS)
        if(budget<=0) throw SocketTimeoutException("视频读取超时")
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget)
        while(true) {
            val target=if(full>0) minOf(end,full-1) else end
            if(position>target) break
            if(requests>=MAX_REQUESTS) throw IOException("视频分段请求过多")
            if(System.nanoTime()>=deadline) throw SocketTimeoutException("视频读取超时")
            var retries=0
            while(true) {
                try {
                    val chunk=rangeOnce(position,target,deadline)
                    if(full==0L) full=chunk.total else if(full!=chunk.total) throw IOException("视频资源已变化，请重试")
                    output.write(chunk.bytes)
                    position+=chunk.bytes.size
                    requests++
                    break
                } catch(e: IOException) {
                    if(closed || Thread.currentThread().isInterrupted)
                        throw InterruptedIOException("播放请求已取消").apply { initCause(e) }
                    val retryable=isTransientMediaFailure(e)
                    if(!retryable || retries>=2 ||
                        System.nanoTime()>=deadline || requests>=MAX_REQUESTS) throw e
                    retries++
                    try { Thread.sleep(140L*retries) }
                    catch(interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("播放请求已取消",interrupted)
                    }
                }
            }
        }
        return Range(output.toByteArray(),full)
    }

    /** Retry only transport resets; a deterministic HTTP 4xx must surface at once. */
    private fun isTransientMediaFailure(error: Throwable): Boolean {
        var current: Throwable?=error
        repeat(8) {
            val cause=current ?: return false
            when(cause) {
                is RetryableRangeException,
                is SocketTimeoutException,
                is java.net.ConnectException,
                is java.net.SocketException,
                is java.io.EOFException -> return true
                is InterruptedIOException -> return cause.message
                    ?.contains("timeout", ignoreCase=true)==true
                is java.net.ProtocolException -> if(
                    cause.message?.contains("unexpected end",ignoreCase=true)==true ||
                    cause.message?.contains("stream reset",ignoreCase=true)==true
                ) return true
                is IOException -> if(
                    cause.message?.contains("unexpected end",ignoreCase=true)==true ||
                    cause.message?.contains("connection reset",ignoreCase=true)==true
                ) return true
            }
            current=cause.cause
        }
        return false
    }
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        val first=range(0,256*1024-1); total=first.total
        prefix=first.bytes
        val end=MediaCrypto.headerEnd(first.bytes,total)
        val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes else first.bytes.copyOf(end)
        prepared=MediaCrypto.prepare(header,info.key)
        if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        return this
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes
        return this
    }
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        if(start>=total) return byteArrayOf()
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        val first=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        val raw=if(start+available<end) first+range(start+available,end-1).bytes else first
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    /** Coded video size from the prepared (decrypted) MP4 header; null before prepare(). */
    @Synchronized fun trackInfo(): com.hongguotv.core.VideoTracks.Track? {
        if(!::prepared.isInitialized) return null
        return VideoTracks.videoTrack(prepared.header)
    }
    override fun close() { synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() } }

    private companion object {
        const val MEDIA_BUDGET_MS=12_000L
        const val REQUEST_TIMEOUT_MS=6_000L
        const val MAX_REQUESTS=32
    }
}
