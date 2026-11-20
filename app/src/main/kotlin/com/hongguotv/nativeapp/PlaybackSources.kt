// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.PlaybackSourceUnavailableException
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.VideoTracks
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException

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

/** Better tier = higher quality, h264/avc over other codecs (upstream StreamLadder.better). */
private val betterTier =
    compareBy<StreamInfo> { it.qualityValue }
        .thenBy { if (it.codec.lowercase() in setOf("h264", "avc", "avc1", "avc3")) 1 else 0 }
private val bestFirstTier =
    compareByDescending<StreamInfo> { it.qualityValue }
        .thenByDescending { if (it.codec.lowercase() in setOf("h264", "avc", "avc1", "avc3")) 1 else 0 }

internal data class PlaybackAttempt(val tier: StreamInfo,val forced: Boolean)

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
        "Playback ladder: " + ladder.joinToString { "${it.quality}/${it.codec}/${it.width}x${it.height}" },
    )
    val primary = ladder.filterNot { it.isFallback }
    val fallback = ladder.filter { it.isFallback }
    Log.i(
        "HongguoTV",
        "Playback candidates: " + ladder.joinToString {
            "${it.quality}/${it.codec}/${it.width}x${it.height}=${supportsTier(it)}"
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
    for ((tier,forced) in primaryOrder) {
        val video = RemoteVideo(http, tier)
        try {
            Log.i(
                "HongguoTV",
                "Playback trying: ${tier.quality}/${tier.codec}/${tier.width}x${tier.height}" +
                    if (forced) " (best effort)" else "",
            )
            video.prepare()
            val track = video.trackInfo()
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
                        if (forced) " (forced best effort)" else "",
                )
                return OpenedStream(video, tier, ladder, track)
            }
            Log.i(
                "HongguoTV",
                "Skipped ${tier.quality} ${tier.codec}: coded ${track?.width}x${track?.height} beyond decoder capability",
            )
            video.close()
        } catch (error: Exception) {
            video.close()
            if(cancelledPlayback(error)) throw error
            if (error is PlaybackSourceUnavailableException) primaryDeterministic = error
            else primaryError = error
            Log.w("HongguoTV", "Playback tier failed: ${tier.quality}/${tier.codec}: ${error.message}")
        }
    }
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
