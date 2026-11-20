// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaFormat
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.VideoTracks

/**
 * Device decoder capability probe (upstream CodecCapabilities, 2026-11-14 port).
 *
 * TV boxes commonly cap AVC decoders near 1920x1088 with width/height checked
 * independently, so portrait sizes like 1080x1920 are rejected while landscape
 * 1920x1080 plays fine - and some boxes claim support they don't have. That
 * over-reporting is exactly the "some episodes play only ~10 seconds" symptom:
 * ExoPlayer starts, hits the first undecodable sample, and errors out. Learned
 * runtime failures (persisted across restarts) override the static probe.
 *
 * "重置设备能力记录" in 播放设置 clears those learned failures, letting the box
 * re-probe after a firmware/decoder change.
 */
object CodecCapabilities {
    private const val STORE = "native-decoder-lessons-v1"
    /**
     * v4 requires a repeat failure before a tier is rejected and self-heals on a
     * rendered frame. Earlier revisions could persist a one-off failure against a
     * padded size (1922x1080), which then blocked every later attempt at that tier.
     */
    private const val SCHEMA = 4
    private val lessons = DecoderLessons()
    private var prefs: SharedPreferences? = null
    private val verdicts = HashMap<String, Boolean>()
    @Volatile private var logged = false

    fun init(context: Context) {
        if (prefs != null) return
        val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        prefs = store
        if (store.getInt("schema", 1) < SCHEMA) {
            // v1 could remember a failure from a transient probe/stream and then
            // permanently reject every higher tier (e.g. 1080P fell back to 360P).
            // Relearn from real playback after the upgrade instead.
            val dropped = store.getStringSet("failures", emptySet())?.size ?: 0
            val droppedPending = store.getStringSet("pending", emptySet())?.size ?: 0
            store.edit().remove("failures").remove("pending").putInt("schema", SCHEMA).apply()
            lessons.reset()
            android.util.Log.i(
                "HongguoTV",
                "Decoder capability store migrated to schema $SCHEMA "+
                    "(dropped $dropped legacy failures, $droppedPending probation entries)",
            )
        } else {
            lessons.restore(
                store.getStringSet("failures", emptySet()) ?: emptySet(),
                store.getStringSet("pending", emptySet()) ?: emptySet(),
            )
            if (lessons.size() > 0) {
                android.util.Log.i("HongguoTV", "Decoder capability lessons restored: ${lessons.size()} entries")
            }
        }
    }

    fun mime(codec: String): String? =
        when (codec.lowercase()) {
            "h264", "avc", "avc1", "avc3" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "bytevc1", "hvc1", "hev1", "h265", "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> null
        }

    fun supports(codec: String, width: Int?, height: Int?): Boolean {
        if (lessons.fails(codec, width, height)) return false
        val type = mime(codec) ?: return true
        if (width == null || height == null || width <= 0 || height <= 0) return true
        val (codedWidth,codedHeight)=decoderProbeSize(width,height)
        val key = "$type $codedWidth $codedHeight"
        synchronized(verdicts) { verdicts[key]?.let { return it } }
        val verdict =
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder &&
                        runCatching {
                            info.getCapabilitiesForType(type).videoCapabilities.isSizeSupported(codedWidth, codedHeight)
                        }.getOrDefault(false)
                }
            }.getOrDefault(true) // A broken ROM probe must not block playback.
        synchronized(verdicts) { verdicts[key] = verdict }
        return verdict
    }

    fun supports(info: StreamInfo) = supports(info.codec, info.width, info.height)

    fun supports(track: VideoTracks.Track) = supports(track.fourcc, track.width, track.height)

    /** Runtime-observed failures only; used to avoid re-forcing a tier that really failed. */
    fun learnedFailure(info: StreamInfo) = lessons.fails(info.codec, info.width, info.height)
    fun learnedFailure(track: VideoTracks.Track) = lessons.fails(track.fourcc, track.width, track.height)

    /**
     * Records a runtime decode failure; true when the static probe had claimed support
     * (over-reporting). The learned key is codec + coded size when known, so only the
     * failing geometry is avoided.
     */
    fun learnFailure(codec: String, width: Int?, height: Int?): Boolean {
        val overReported = supports(codec, width, height)
        val (key,confirmed)=lessons.observeFailure(codec,width,height) ?: return false
        persist()
        if(confirmed) {
            android.util.Log.w("HongguoTV", "Learned decoder failure $key after repeat (static probe claimed support: $overReported)")
        } else {
            android.util.Log.w("HongguoTV", "Decoder failure probation $key (will reject only if it repeats)")
        }
        return overReported
    }

    /** Playback proved this codec/size works; drop any stale learned failure. */
    fun confirmPlayable(codec: String, width: Int?, height: Int?): Boolean {
        val removed=lessons.forget(codec,width,height)
        if(removed) {
            persist()
            android.util.Log.i("HongguoTV", "Cleared stale decoder failure after successful frame: ${lessons.key(codec,width,height)}")
        }
        return removed
    }

    /** Clears every learned failure (播放设置 → 重置设备能力记录). */
    fun resetLearned() {
        lessons.reset()
        synchronized(verdicts) { verdicts.clear() }
        persist()
    }

    fun learnedCount() = lessons.size()

    private fun persist() {
        prefs?.edit()
            ?.putInt("schema", SCHEMA)
            ?.putStringSet("failures", lessons.keys())
            ?.putStringSet("pending", lessons.pendingKeys())
            ?.apply()
    }

    fun logDeviceCapabilities() {
        if (logged) return
        logged = true
        val lines =
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .filter { !it.isEncoder }
                    .flatMap { info ->
                        listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                            .filter { type -> runCatching { info.getCapabilitiesForType(type) }.isSuccess }
                            .map { type ->
                                val video = info.getCapabilitiesForType(type).videoCapabilities
                                "${info.name} $type up to ${video.supportedWidths.upper}x${video.supportedHeights.upper}"
                            }
                    }
            }.getOrNull() ?: return
        android.util.Log.i("HongguoTV", "Decoder capabilities: ${lines.joinToString("; ")}")
    }
}
