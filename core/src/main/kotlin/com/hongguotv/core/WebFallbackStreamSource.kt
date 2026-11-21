// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Full-episode website fallback.
 *
 * The website's public `/video/public/{id}/360p.mp4` path can be a ten-second
 * preview, but its protected player exposes `/api/video/protected/fallback-play-info`
 * for the same public episodes. That endpoint returns a complete, unencrypted,
 * progressive H.264 MP4 (verified duration and byte ranges), which the existing
 * [RemoteVideo] pipeline can play without any new container or DRM support.
 */
internal class WebFallbackStreamSource(
    private val http: OkHttpClient,
    private val webUa: String,
    private val site: String,
) : StreamSource {
    override val name = "web-fallback"

    override fun stream(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String>,
        speculative: Boolean,
        seriesId: String,
    ): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload = JSONObject().put("item_id", id).toString()
        val request = requestBuilder("/api/video/protected/fallback-play-info")
            .post(payload.toRequestBody(JSON))
            .build()
        return RequestScope.execute(PlaybackRouteTransport.call(http, request)) { response ->
            if (!response.isSuccessful) throw unavailable(response.code, "网页完整片源")
            val body = response.body ?: throw IOException("网页播放地址响应为空")
            if (body.contentLength() > 64 * 1024) throw IOException("网页播放地址响应过大")
            val source = body.source()
            source.request(64 * 1024 + 1)
            if (source.buffer.size > 64 * 1024) throw IOException("网页播放地址响应过大")
            val root = JSONObject(source.readUtf8())
            val code = root.optJSONObject("base_resp")?.optInt("StatusCode", -1) ?: -1
            if (code != 0) throw IOException("网页暂未提供完整片源（错误码 $code）")
            val url = root.str("main_url")
            val secure = url.startsWith("https://") ||
                (site.startsWith("http://") && url.startsWith("http://"))
            if (!secure) throw IOException("网页播放地址格式异常")
            verifyFullMp4(url)
            StreamInfo(
                url = url,
                key = null,
                quality = "360P",
                codec = "h264",
                qualityValue = 360,
                isFallback = true,
            )
        }
    }

    private fun unavailable(httpCode: Int, source: String): IOException =
        if (httpCode == 403 || httpCode == 404) {
            PlaybackSourceUnavailableException("$source 未向当前账号提供该集（HTTP $httpCode）")
        } else {
            IOException("$source 请求失败（HTTP $httpCode）")
        }

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder()
            .url("$site$path")
            .header("User-Agent", webUa)
            .header("Referer", "$site/")
            .header("Origin", site)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")

    /** Reject the known ten-second preview clip before ExoPlayer can mistake it for an episode. */
    private fun verifyFullMp4(url: String) {
        val probe = Request.Builder()
            .url(url)
            .header("User-Agent", webUa)
            .header("Referer", "$site/")
            .header("Range", "bytes=0-131071")
            .header("Accept-Encoding", "identity")
            .build()
        RequestScope.execute(PlaybackRouteTransport.call(http, probe)) { response ->
            if (response.code != 200 && response.code != 206) {
                throw unavailable(response.code, "网页完整片源")
            }
            val body = response.body ?: throw IOException("网页完整片源响应为空")
            val source = body.source()
            source.request(128 * 1024)
            val length = minOf(source.buffer.size, 128L * 1024).toInt()
            val head = source.buffer.readByteArray(length.toLong())
            if (head.size < 32 || String(head, 4, 4, Charsets.US_ASCII) != "ftyp") {
                throw IOException("网页完整片源格式异常")
            }
            val seconds = declaredSeconds(head)
            if (seconds != null && seconds in 0.1..30.0) {
                throw IOException("网页片源仍为试看片段，请稍后重试")
            }
        }
    }

    private fun declaredSeconds(data: ByteArray): Double? {
        val at = String(data, Charsets.ISO_8859_1).indexOf("mvhd")
        if (at < 4) return null
        val version = data[at + 4].toInt() and 0xff
        val (scale, duration) = when (version) {
            0 -> if (at + 24 <= data.size) data.u32(at + 16) to data.u32(at + 20) else return null
            1 -> if (at + 32 <= data.size) data.u32(at + 20) to data.i64(at + 24) else return null
            else -> return null
        }
        if (scale <= 0 || duration <= 0) return null
        return duration.toDouble() / scale.toDouble()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
