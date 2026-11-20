// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.util.Locale

/**
 * Learned decoder failures: capabilities the device claims but cannot actually decode
 * (upstream DecoderLessons, 2026-11-14 port). Keys are codec plus coded size, so a
 * failed portrait 1080x1920 never blocks landscape 1920x1080. A single failure
 * stays in probation; only a repeat becomes a rejection. Persisted across
 * restarts by CodecCapabilities into "native-decoder-lessons-v1".
 */
internal class DecoderLessons {
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()

    /**
     * One key per decoder family. The MP4 track fourcc (hvc1/hev1) and the playback
     * API's codec name (bytevc1) describe the SAME decoder, so learning a failure
     * under one spelling must also reject the other. Keep AVC separate from HEVC and
     * keep ByteVC2 separate (no TV decoder).
     */
    private fun family(codec: String): String =
        when (codec.trim().lowercase(Locale.ROOT)) {
            "h264", "avc", "avc1", "avc3" -> "avc"
            "bytevc1", "hvc1", "hev1", "h265", "hevc" -> "hevc"
            "bytevc2", "bvc2" -> "bytevc2"
            else -> codec.trim().lowercase(Locale.ROOT)
        }

    fun key(codec: String, width: Int?, height: Int?): String? {
        val normalized = family(codec)
        if (normalized.isEmpty()) return null
        if (width == null || height == null || width <= 0 || height <= 0) return normalized
        val (codedWidth,codedHeight)=decoderProbeSize(width,height)
        return "$normalized|$codedWidth|$codedHeight"
    }

    fun remember(codec: String, width: Int?, height: Int?): String? {
        val exact = key(codec, width, height) ?: return null
        pending -= exact
        failed += exact
        return exact
    }

    /**
     * First observed runtime failure is probation; a repeat makes it a learned
     * rejection. One transient decoder/stream glitch must not downgrade a tier
     * for the rest of the installation's life.
     */
    fun observeFailure(codec: String, width: Int?, height: Int?): Pair<String,Boolean>? {
        val exact = key(codec, width, height) ?: return null
        if (failed.contains(exact)) return exact to true
        if (pending.add(exact)) return exact to false
        pending -= exact
        failed += exact
        return exact to true
    }

    /** A real rendered frame disproves an earlier learned failure for this geometry. */
    fun forget(codec: String, width: Int?, height: Int?): Boolean {
        val exact = key(codec, width, height) ?: return false
        val removedFailure = failed.remove(exact)
        val removedPending = pending.remove(exact)
        return removedFailure || removedPending
    }

    fun fails(codec: String, width: Int?, height: Int?): Boolean {
        val normalized = family(codec)
        if (normalized.isEmpty()) return false
        key(codec,width,height)?.let { if (failed.contains(it)) return true }
        // A remembered sizeless verdict (metadata was missing) covers that codec entirely.
        return failed.contains(normalized)
    }

    fun keys(): Set<String> = failed.toSet()

    fun pendingKeys(): Set<String> = pending.toSet()

    fun restore(keys: Set<String>, pendingKeys: Set<String> = emptySet()) {
        failed.clear()
        failed += keys.mapNotNull(::restoreKey)
        pending.clear()
        pending += pendingKeys.mapNotNull(::restoreKey)
        pending -= failed
    }

    fun reset() {
        failed.clear()
        pending.clear()
    }

    fun size() = failed.size

    /** Normalize legacy keys such as "hvc1|1922|1080" on upgrade. */
    private fun restoreKey(raw: String): String? {
        val parts = raw.split('|')
        if (parts.size != 3) return family(raw).ifEmpty { null }
        val width = parts[1].toIntOrNull()
        val height = parts[2].toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return key(parts[0], width, height)
    }
}
