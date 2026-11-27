// Adapted 2026-11-07 from tsukiyo-zero/hongguoTV 0.1.26 (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import org.json.JSONObject

data class RelatedWork(val series: Series, val season: String, val upcoming: Boolean, val seasonIndex: Int = 0) {
    val label
        get() =
            listOf(season, if (upcoming) "待上线 · 预约" else "已上线")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}

/**
 * Reading plan's related-work cell, verified with public season metadata. Only short-drama types
 * are actionable; novels/audio are intentionally excluded.
 */
object RelatedWorks {
    private val seasonSuffix = Regex("第?([0-9零〇一二两三四五六七八九十百]+)季[\\p{P}\\p{Z}\\s]*$")
    private val seasonTag = Regex("第\\s*([0-9零〇一二两三四五六七八九十百]+)\\s*季")

    private fun seasonNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        val digits = "零一二三四五六七八九"
        var total = 0
        var digit = 0
        for (c in value) when (c) {
            '十', '百' -> { total += maxOf(1, digit) * if (c == '十') 10 else 100; digit = 0 }
            '两' -> digit = 2
            '〇' -> digit = 0
            else -> { digit = digits.indexOf(c); if (digit < 0) return null }
        }
        return (total + digit).takeIf { it > 0 }
    }

    private fun stem(title: String) = title.trim().replace(seasonSuffix, "")
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    fun ordered(current: Series, rows: List<RelatedWork>): List<RelatedWork> = rows.sortedWith(
        compareBy<RelatedWork> { if (stem(it.series.title) == stem(current.title)) 0 else 1 }
            .thenBy { stem(it.series.title) }
            .thenBy { it.seasonIndex.takeIf { n -> n > 0 }
                ?: seasonSuffix.find(it.series.title.trim())?.groupValues?.get(1)?.let(::seasonNumber)
                ?: 1 }
    )

    /**
     * Actual seasons belonging to the current series, one row per season.
     *
     * The related-work cell mixes official seasons with same-IP spin-offs and duplicate
     * uploads. Keep only rows whose title shares the current title's season-stripped stem,
     * then collapse duplicates by season number. Official `第N季` tags win over a generic
     * `同IP` label; a playable duplicate wins over an unpublished one.
     *
     * [includeCurrent] adds the current series as its own season so the player sidebar can
     * mark "本剧" and still offer the surrounding seasons. A series with no siblings returns
     * either an empty list or only the current row.
     */
    fun seasons(
        current: Series,
        rows: List<RelatedWork>,
        includeCurrent: Boolean = true,
    ): List<RelatedWork> {
        val currentStem = stem(current.title)
        if (currentStem.isBlank()) return emptyList()
        val currentSeason = seasonFromTitle(current.title) ?: 1
        val grouped = linkedMapOf<Int, MutableList<Pair<RelatedWork, Boolean>>>()
        if (includeCurrent) {
            val own = RelatedWork(current, "第${currentSeason}季", false, currentSeason)
            grouped.getOrPut(currentSeason) { mutableListOf() } += own to true
        }
        rows.forEach { row ->
            if (row.series.id == current.id || stem(row.series.title) != currentStem) return@forEach
            val season = seasonFromTag(row.season) ?: seasonFromTitle(row.series.title) ?: 1
            grouped.getOrPut(season) { mutableListOf() } += row to false
        }
        return grouped.entries
            .sortedBy { it.key }
            .map { (season, candidates) ->
                val picked = candidates
                    .sortedWith(
                        compareBy<Pair<RelatedWork, Boolean>> { (row, own) ->
                            when {
                                own -> 0
                                seasonFromTag(row.season) == season -> 1
                                else -> 2
                            }
                        }
                            .thenBy { (row, _) -> if (row.upcoming) 1 else 0 }
                            .thenBy { (row, _) -> titleSeason(row.series.title)?.let { 0 } ?: 1 }
                            .thenBy { (row, _) -> row.series.title.length }
                            .thenBy { (row, _) -> row.series.id }
                    )
                    .first().first
                picked.copy(season = "第${season}季", seasonIndex = season)
            }
    }

    /** Conservative: identical title stem, consecutive season, unique playable candidate. */
    fun nextSeason(current: Series, rows: List<RelatedWork>): RelatedWork? {
        val title = current.title.trim()
        val suffix = seasonSuffix.find(title)
        val number = suffix?.groupValues?.get(1)?.let(::seasonNumber) ?: if (suffix == null) 1 else return null
        val currentStem = stem(title)
        if (currentStem.isBlank()) return null
        return rows.filter { row ->
            val candidateTitle = row.series.title.trim()
            val candidateSuffix = seasonSuffix.find(candidateTitle)
            val fromTitle = candidateSuffix?.groupValues?.get(1)?.let(::seasonNumber)
            val index = row.seasonIndex.takeIf { it > 0 } ?: fromTitle
            !row.upcoming && row.series.id != current.id && index == number + 1 &&
                (fromTitle == null || fromTitle == index) &&
                stem(candidateTitle) == currentStem
        }.singleOrNull()
    }

    fun parse(raw: String, currentId: String): List<RelatedWork> = parse(JSONObject(raw), currentId)

    fun parse(root: JSONObject, currentId: String): List<RelatedWork> {
        if (root.optInt("code", -1) != 0) throw IOException("相关作品接口暂不可用")
        val cells = root.optJSONArray("data") ?: throw IOException("相关作品数据格式已变化")
        return cells
            .objects()
            .filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row ->
                val id = row.str("series_id")
                val title = row.str("title")
                val type = row.optInt("content_type")
                if (
                    id == currentId ||
                        !id.matches(Regex("[0-9]{1,30}")) ||
                        title.isBlank() ||
                        type !in listOf(1004, 1007)
                )
                    return@mapNotNull null
                val upcoming = type == 1007
                if (!upcoming && (row.optBoolean("disable_play") || row.optInt("episode_cnt") <= 0))
                    return@mapNotNull null
                val season =
                    row.optJSONObject("tag_info")?.str("text").orEmpty().ifBlank {
                        row.optInt("season_index").takeIf { it > 0 }?.let { "第${it}季" }.orEmpty()
                    }
                RelatedWork(
                    Series(
                        id,
                        title,
                        coverUrl(row.str("cover")),
                        row.str("video_desc"),
                        if (upcoming) "待上线" else "全 ${row.optInt("episode_cnt")} 集",
                    ),
                    season,
                    upcoming,
                    row.optInt("season_index").coerceAtLeast(0),
                )
            }
            .distinctBy { it.series.id }
            .sortedWith(compareBy<RelatedWork> { it.seasonIndex.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
    }

    private fun seasonFromTag(value: String): Int? =
        seasonTag.find(value)?.groupValues?.get(1)?.let(::seasonNumber)

    private fun seasonFromTitle(value: String): Int? =
        seasonSuffix.find(value.trim())?.groupValues?.get(1)?.let(::seasonNumber)

    private fun titleSeason(value: String): Int? = seasonFromTitle(value)
}
