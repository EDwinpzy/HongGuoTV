// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.util.Locale

/**
 * Learned decoder failures: capabilities the device claims but cannot actually decode
 * (upstream DecoderLessons, 2026-11-14 port). Keys are codec plus coded size, so a
 * failed portrait 1080x1920 never blocks landscape 1920x1080. Persisted across
 * restarts by CodecCapabilities into "native-decoder-lessons-v1".
 */
internal class DecoderLessons {
    private val failed = HashSet<String>()

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
        return "$normalized|$width|$height"
    }

    fun remember(codec: String, width: Int?, height: Int?): String? {
        val exact = key(codec, width, height) ?: return null
        failed += exact
        return exact
    }

    fun fails(codec: String, width: Int?, height: Int?): Boolean {
        val normalized = family(codec)
        if (normalized.isEmpty()) return false
        if (width != null && height != null && width > 0 && height > 0) {
            if (failed.contains("$normalized|$width|$height")) return true
        }
        // A remembered sizeless verdict (metadata was missing) covers that codec entirely.
        return failed.contains(normalized)
    }

    fun keys(): Set<String> = failed.toSet()

    fun restore(keys: Set<String>) {
        failed.clear()
        failed += keys.mapNotNull(::restoreKey)
    }

    fun reset() = failed.clear()

    fun size() = failed.size

    /** Migrate pre-normalization keys such as "hvc1|1920|1080" on upgrade. */
    private fun restoreKey(raw: String): String? {
        val parts = raw.split('|')
        if (parts.size != 3) return family(raw).ifEmpty { null }
        val width = parts[1].toIntOrNull()
        val height = parts[2].toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return key(parts[0], width, height)
    }
}
