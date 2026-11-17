// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.util.Locale

/**
 * Content key for danmaku de-duplication. Whitespace, zero-width characters and
 * letter case do not make a comment meaningfully different on a TV screen.
 */
internal fun danmakuTextKey(text: String): String =
    text.filterNot { it.isWhitespace() || it=='\u200B' || it=='\uFEFF' }
        .lowercase(Locale.ROOT)

/** Keeps the first occurrence of each comment text for one episode. */
internal class DanmakuTextDeduper {
    private val seen=mutableSetOf<String>()

    fun clear() = seen.clear()

    fun isNew(text: String): Boolean {
        val key=danmakuTextKey(text)
        return key.isNotEmpty() && seen.add(key)
    }
}
