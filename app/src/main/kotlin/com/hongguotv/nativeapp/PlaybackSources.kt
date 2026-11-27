// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import androidx.media3.common.PlaybackException
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.PlaybackBudget
import com.hongguotv.core.PlaybackSourceUnavailableException
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.util.Locale

/**
 * One opened playback tier (upstream OpenedStream, 2026-11-14 port). Carries the whole
 * ladder plus the verified coded size, so a runtime decoder failure can learn the exact
 * failing geometry and step down without re-requesting the playback model.
 */
internal class OpenedStream(
    val video: RemoteVideo,
    val info: StreamInfo,
    val tiers: List<StreamInfo>,
    val track: VideoTracks.Track?,
) : Closeable {
    override fun close() = video.close()
}

/**
 * The ladder has tiers but none is decodable on this box (deterministic): retrying only
 * yields the same result, so no auto-recovery budget and no lower-tier button. Kept as a
 * manual retry - the server's transcode supply may flip later.
 */
internal class NoPlayableQualityException :
    IOException("本集所有档位均超出电视解码能力，可尝试其他集数")

/** Better tier = higher resolution, then source bitrate, then h264/avc compatibility. */
private val betterTier =
    compareBy<StreamInfo> { it.qualityValue }
        .thenBy { it.bitrate }
        .thenBy { if (it.codec.lowercase() in setOf("h264", "avc", "avc1", "avc3")) 1 else 0 }
private val bestFirstTier =
    compareByDescending<StreamInfo> { it.qualityValue }
        .thenByDescending { it.bitrate }
        .thenByDescending { if (it.codec.lowercase() in setOf("h264", "avc", "avc1", "avc3")) 1 else 0 }

internal data class PlaybackAttempt(val tier: StreamInfo,val forced: Boolean)

internal enum class PlaybackSurfaceKind { SURFACE_VIEW, TEXTURE_VIEW }

/**
 * S621's vendor HWC path reports `hwc overlay number exceed limit` and presents
 * black HEVC frames while audio continues. TextureView renders visibly and was
 * measured at 1.43% janky frames in 1080P/30fps on the affected box.
 */
internal fun playbackSurfaceKind(model: String): PlaybackSurfaceKind =
    if(model.trim().equals("S621",ignoreCase=true))
        PlaybackSurfaceKind.TEXTURE_VIEW else PlaybackSurfaceKind.SURFACE_VIEW

internal fun formatSourceBitrate(bitsPerSecond: Long): String =
    if (bitsPerSecond >= 1_000_000) {
        String.format(Locale.ROOT, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
    } else {
        "${(bitsPerSecond + 500) / 1_000} kbps"
    }

internal fun actualQualityLabel(
    name: String,
    track: VideoTracks.Track?,
    tier: StreamInfo?,
): String {
    val label = name.ifBlank { tier?.quality.orEmpty() }.ifBlank { "自动" }
    val width = track?.width?.takeIf { it > 0 } ?: tier?.width?.takeIf { it > 0 }
    val height = track?.height?.takeIf { it > 0 } ?: tier?.height?.takeIf { it > 0 }
    val size = if (width != null && height != null) "${width}×${height}" else null
    val bitrate = tier?.bitrate?.takeIf { it > 0 }?.let { "片源码率 ${formatSourceBitrate(it)}" }
    return listOfNotNull(label, size, bitrate).joinToString(" · ")
}

internal fun primaryPlaybackAttempts(
    ladder: List<StreamInfo>,
    maxQuality: Int,
    supportsTier: (StreamInfo) -> Boolean,
    learnedFailure: (StreamInfo) -> Boolean = { false },
    retryLearned: Boolean = false,
): List<PlaybackAttempt> {
    val primary = ladder.filterNot { it.isFallback }
    fun best(candidates: List<StreamInfo>): StreamInfo? =
        candidates.filter { it.qualityValue in 1..maxQuality }.maxWithOrNull(betterTier)
            ?: candidates.filter { it.qualityValue <= 0 }.maxWithOrNull(betterTier)
            ?: candidates.minWithOrNull(betterTier)
    val supported=primary.filter { supportsTier(it) && (it.qualityValue <= 0 || it.qualityValue <= maxQuality) }
    // Only the no-supported-primary case needs a real best-effort attempt. The
    // website fallback must not make an unsupported primary look "covered".
    // An explicit quality change is different: the user asked for that tier, so
    // try the highest requested primary once even when a stale failure was learned.
    val force=when {
        retryLearned -> best(primary)
        // The static probe and the learned record are both heuristics. When no
        // primary tier is otherwise eligible, still give the best one a real
        // ExoPlayer attempt: a stale one-off failure must not turn a playable
        // episode into NoPlayableQualityException. A genuine failure is learned
        // again and handled by the normal recovery path.
        supported.isEmpty() -> best(primary)
        else -> null
    }
    return buildList {
        force?.let { add(PlaybackAttempt(it,true)) }
        supported
            .sortedWith(bestFirstTier)
            .filterNot { it === force }
            .forEach { add(PlaybackAttempt(it,false)) }
    }
}

/**
 * Opens the first tier the device can ACTUALLY decode (upstream openStream, 2026-11-14
 * port): pick by API metadata + static/learned capability, then read the coded size from
 * the MP4 header and re-check - boxes often advertise AVC sizes they cannot really
 * decode (portrait 1080x1920 beyond the 1088 height cap), which is the ~10s-then-error
 * symptom. A rejected tier is closed and the next lower one is tried from the SAME model.
 */
internal fun openStream(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    type: ContentType,
    supportsTier: (StreamInfo) -> Boolean,
    metadataAhead: List<String> = emptyList(),
    speculative: Boolean = false,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
    learnedFailure: (StreamInfo) -> Boolean = { false },
    retryLearned: Boolean = false,
    seriesId: String = "",
): OpenedStream? = PlaybackBudget.run(if (speculative) SPECULATIVE_TOTAL_MS else FOREGROUND_TOTAL_MS) {
    openStreamWithinBudget(
        http, repository, id, maxQuality, type, supportsTier, metadataAhead, speculative,
        supportsTrack, learnedFailure, retryLearned, seriesId,
    )
}

/**
 * Foreground budget for ONE episode switch: the ladder read plus the parallel
 * header probes. Before this cap the ladder had 10s and every header probe its
 * own 12s on top, so a dead CDN edge could hold a switch for 30s+ with no
 * overall limit. 18s = 10s ladder + 8s for the slowest single header probe.
 */
private const val FOREGROUND_TOTAL_MS = 18_000L
private const val SPECULATIVE_TOTAL_MS = 10_000L

private fun openStreamWithinBudget(
    http: OkHttpClient,
    repository: ContentRepository,
    id: String,
    maxQuality: Int,
    type: ContentType,
    supportsTier: (StreamInfo) -> Boolean,
    metadataAhead: List<String> = emptyList(),
    speculative: Boolean = false,
    supportsTrack: (VideoTracks.Track?) -> Boolean,
    learnedFailure: (StreamInfo) -> Boolean = { false },
    retryLearned: Boolean,
    seriesId: String = "",
): OpenedStream? {
    val ladder = repository.playbackTiers(
        id,
        maxQuality,
        type,
        metadataAhead,
        speculative,
        seriesId,
    )
    Log.i(
        "HongguoTV",
        "Playback ladder: " + ladder.joinToString {
            "${it.quality}/${it.codec}/${it.width}x${it.height}/${it.bitrate}bps"
        },
    )
    val primary = ladder.filterNot { it.isFallback }
    val fallback = ladder.filter { it.isFallback }
    Log.i(
        "HongguoTV",
        "Playback candidates: " + ladder.joinToString {
            "${it.quality}/${it.codec}/${it.width}x${it.height}/${it.bitrate}bps=${supportsTier(it)}"
        },
    )
    val primaryOrder = primaryPlaybackAttempts(ladder,maxQuality,supportsTier,learnedFailure,retryLearned)
    Log.i(
        "HongguoTV",
        "Playback primary order: " + primaryOrder.joinToString {
            "${it.tier.quality}/${it.tier.codec}/${it.tier.width}x${it.tier.height}" +
                if (it.forced) "(best effort)" else ""
        },
    )
    var primaryError: Exception? = null
    var primaryDeterministic: PlaybackSourceUnavailableException? = null
    // Probe every primary candidate's MP4 header at once instead of one after
    // another: each probe is an independent CDN read (up to 12s on a dead edge),
    // and a serial walk made the user wait for the SUM. The winner is still the
    // highest-priority candidate that decoded, so the selection is unchanged -
    // only the wait collapses to the slowest single probe.
    val probed = probeTiers(primaryOrder) { tier ->
        val video = RemoteVideo(http, tier)
        try {
            TierProbe.Opened(video, video.prepare().trackInfo())
        } catch (error: Exception) {
            video.close()
            TierProbe.Failed(error)
        }
    }
    for ((tier, forced) in primaryOrder) {
        val outcome = probed[tier] ?: continue
        when (outcome) {
            is TierProbe.Opened -> {
                val track = outcome.track
                Log.i(
                    "HongguoTV",
                    "Playback track: ${track?.fourcc}/${track?.width}x${track?.height} " +
                        "supported=${supportsTrack(track)}",
                )
                // A requested primary tier gets one real ExoPlayer attempt even when the
                // static probe rejects its portrait geometry. Learned runtime failures are
                // excluded above, so this cannot loop on a tier the box already proved bad.
                if (forced || supportsTrack(track)) {
                    Log.i(
                        "HongguoTV",
                        "Playback selected: ${tier.quality}/${tier.codec}/${track?.width}x${track?.height}" +
                            "/${tier.bitrate}bps" +
                            if (forced) " (forced best effort)" else "",
                    )
                    closeProbesExcept(probed, outcome.video)
                    return OpenedStream(outcome.video, tier, ladder, track)
                }
                Log.i(
                    "HongguoTV",
                    "Skipped ${tier.quality} ${tier.codec}: coded ${track?.width}x${track?.height} beyond decoder capability",
                )
                outcome.video.close()
            }
            is TierProbe.Failed -> {
                val error = outcome.error
                if(cancelledPlayback(error)) {
                    probed.values.forEach { (it as? TierProbe.Opened)?.video?.close() }
                    throw error
                }
                if (error is PlaybackSourceUnavailableException) primaryDeterministic = error
                else primaryError = error
                Log.w("HongguoTV", "Playback tier failed: ${tier.quality}/${tier.codec}: ${error.message}")
            }
        }
    }
    // Every opened probe that was not selected has served its purpose.
    probed.values.forEach { (it as? TierProbe.Opened)?.video?.close() }
    // The website fallback is a continuation source, never proof that the signed
    // primary ladder failed capability checks. Only reach it after every primary
    // candidate was attempted.
    var fallbackError: Exception? = null
    var fallbackDeterministic: PlaybackSourceUnavailableException? = null
    for (tier in fallback.sortedWith(bestFirstTier)) {
        val video = RemoteVideo(http, tier)
        try {
            Log.i("HongguoTV", "Playback fallback trying: ${tier.quality}/${tier.width}x${tier.height}")
            video.prepare()
            val track = video.trackInfo()
            if (supportsTrack(track)) {
                Log.i("HongguoTV", "Playback fallback selected: ${tier.quality}/${track?.width}x${track?.height}")
                return OpenedStream(video, tier, ladder, track)
            }
            video.close()
        } catch (error: Exception) {
            video.close()
            if(cancelledPlayback(error)) throw error
            if (error is PlaybackSourceUnavailableException) fallbackDeterministic = error
            else fallbackError = error
            Log.w("HongguoTV", "Playback fallback failed: ${tier.quality}: ${error.message}")
        }
    }
    if (ladder.isNotEmpty()) {
        throwNoOpenedStream(
            ladder,
            primaryDeterministic ?: fallbackDeterministic ?: primaryError ?: fallbackError,
        )
    }
    return null
}

/** Result of one parallel MP4 header probe. */
internal sealed class TierProbe {
    class Opened(val video: RemoteVideo, val track: VideoTracks.Track?) : TierProbe()
    class Failed(val error: Exception) : TierProbe()
}

/**
 * Reads every candidate's header concurrently on a small daemon pool. Returns the
 * outcomes keyed by tier (a tier whose probe never completed is absent). The
 * caller picks the winner by priority and closes the rest. [probe] does the real
 * I/O for one tier; tests inject a fake to verify the concurrency and cleanup.
 */
internal fun probeTiers(
    attempts: List<PlaybackAttempt>,
    pool: java.util.concurrent.ExecutorService = probePool,
    probe: (StreamInfo) -> TierProbe,
): Map<StreamInfo, TierProbe> {
    if (attempts.isEmpty()) return emptyMap()
    val futures = attempts.associate { attempt ->
        attempt.tier to pool.submit<TierProbe?> {
            if (Thread.currentThread().isInterrupted) return@submit null
            // Diagnostics only: a logging failure (e.g. the JVM test harness) must
            // never turn into a tier failure.
            runCatching {
                Log.i(
                    "HongguoTV",
                    "Playback trying: ${attempt.tier.quality}/${attempt.tier.codec}/" +
                        "${attempt.tier.width}x${attempt.tier.height}" +
                        if (attempt.forced) " (best effort)" else "",
                )
            }
            probe(attempt.tier)
        }
    }
    val outcomes = linkedMapOf<StreamInfo, TierProbe>()
    var firstCancellation: Exception? = null
    for ((tier, future) in futures) {
        val outcome = try {
            future.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            (error.cause as? Exception)?.let { TierProbe.Failed(it) }
        }
        if (outcome != null) {
            outcomes[tier] = outcome
            if (outcome is TierProbe.Failed && cancelledPlayback(outcome.error) && firstCancellation == null) {
                firstCancellation = outcome.error
            }
        }
    }
    // A cancelled probe cancels the whole selection: close everything that opened
    // and let the caller observe the cancellation instead of a half-probed ladder.
    if (firstCancellation != null) {
        outcomes.values.forEach { (it as? TierProbe.Opened)?.video?.close() }
        futures.values.forEach { it.cancel(true) }
        throw firstCancellation
    }
    return outcomes
}

private fun closeProbesExcept(probed: Map<StreamInfo, TierProbe>, keep: RemoteVideo) {
    probed.values.forEach { probe ->
        (probe as? TierProbe.Opened)?.video?.takeIf { it !== keep }?.close()
    }
}

/** Shared daemon pool: header probes are short and bursty, one burst per episode switch. */
private val probePool: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hongguotv-tier-probe").apply { isDaemon = true }
    }

internal fun throwNoOpenedStream(ladder: List<StreamInfo>, lastError: Exception?): Nothing {
    // No candidate was opened: preserve the actual transport failure so the
    // caller can recover. A timeout/reset is not evidence that the TV cannot
    // decode any tier (and must not disable the automatic recovery budget).
    if (lastError != null) throw lastError
    if (ladder.isNotEmpty()) throw NoPlayableQualityException()
    throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
}

internal fun cancelledPlayback(error: Throwable): Boolean =
    Thread.currentThread().isInterrupted || generateSequence(error) { it.cause }.take(8).any {
        (it is InterruptedIOException && it !is java.net.SocketTimeoutException &&
            it.message?.contains("timeout", ignoreCase=true)!=true) ||
            (it is SocketException && it.message?.contains("cancel",ignoreCase=true)==true) ||
            it.message?.contains("请求已取消")==true
    }

/** Lowest ladder tier strictly below the attempted quality; null = no dead-end button. */
internal fun nextLowerQuality(attempted: Int, tiers: List<StreamInfo>): Int? =
    tiers.map { it.qualityValue }.filter { it in 1 until attempted }.minOrNull()

/** Auto-downgrade is worth another attempt while budget remains and a lower tier exists. */
internal fun shouldAutoDowngrade(
    attempts: Int,
    maxAttempts: Int,
    tiers: List<StreamInfo>,
    currentQuality: Int,
): Boolean =
    attempts < maxAttempts && currentQuality > 0 && tiers.any { it.qualityValue in 1 until currentQuality }

/**
 * Transient failures should restart playback without taking over the TV with an
 * error panel. Network timeouts and decoder shutdowns are often one-off on TV
 * boxes; a deterministic source/capability failure is handled separately.
 */
internal fun isTransientPlaybackErrorCode(errorCode: Int): Boolean =
    errorCode in 2000..2999 ||
        errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
        errorCode in
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

internal fun isTransientPlaybackFailure(problem: Throwable): Boolean =
    generateSequence(problem) { it.cause }.take(8).any { cause ->
        when (cause) {
            is PlaybackException -> isTransientPlaybackErrorCode(cause.errorCode)
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.SocketException,
            is java.io.InterruptedIOException,
            is java.io.EOFException -> true
            is java.io.IOException -> cause.message?.let { message ->
                message.contains("超时") ||
                    message.contains("timeout", ignoreCase=true) ||
                    message.contains("连接重置") ||
                    message.contains("connection reset", ignoreCase=true) ||
                    message.contains("分段读取中断") ||
                    message.contains("视频资源已变化")
            } == true
            else -> false
        }
    }

/** Last-resort quality for a decoder timeout when the ladder exposed no lower tier. */
internal fun recoveryQualityFallback(attemptedQuality: Int): Int? =
    when {
        attemptedQuality > 720 -> 720
        attemptedQuality > 480 -> 480
        else -> null
    }

/**
 * Display modes to try, best first. Native cadence wins when the panel exposes it;
 * otherwise 25/50fps content uses 50Hz and 30/60fps content uses 60Hz to avoid
 * carrying judder from S621's limited 50/60Hz mode set.
 */
internal fun displayRefreshTargets(frameRate: Float): List<Float> {
    if(!frameRate.isFinite() || frameRate<=0f) return emptyList()
    val family=when(frameRate) {
        in 23f..26f -> 50f
        in 27f..31f -> 60f
        in 48f..52f -> 50f
        in 58f..62f -> 60f
        else -> null
    }
    return buildList {
        add(frameRate)
        if(family!=null && kotlin.math.abs(family-frameRate)>0.6f) add(family)
    }
}

/** Final fallback cadence for tests and callers that only need one target. */
internal fun targetDisplayRefreshRate(frameRate: Float): Float? =
    displayRefreshTargets(frameRate).lastOrNull()
