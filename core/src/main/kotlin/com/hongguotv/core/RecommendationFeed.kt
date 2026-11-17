// Adapted from tsukiyo-zero/hongguoTV kotlin-tv (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Per-account recommendation session. Pagination is driven by the server cursor, not the page number. */
class RecommendationCursor internal constructor(
    @Volatile internal var session: String = "",
    @Volatile internal var offset: Int = 0,
) {
    internal val seen = linkedSetOf<String>()
    internal var expectedPage = 1
    internal var lastPage = 0
    internal var lastResult: CatalogPage? = null
    @Volatile internal var cellFlow: CellFlow? = null
    @Volatile internal var unsignedFallback = false

    internal data class CellFlow(val cellId: String, val planId: String, val versionTag: String)

    internal fun restartServer() {
        session = ""
        offset = 0
        cellFlow = null
    }

    internal fun remember(ids: Collection<String>) {
        synchronized(seen) {
            seen.addAll(ids)
            while (seen.size > 540) seen.remove(seen.first())
        }
    }

    internal fun seenIds(): Set<String> = synchronized(seen) { seen.toSet() }

    fun markSeen(id: String) {
        if (id.isBlank() || id.length > 32) return
        remember(listOf(id))
    }
}

class NoNewRecommendationsException(val rememberedCount: Int = 0) :
    IOException("暂时没有新的推荐，请稍后再试")

internal data class RecommendationPage(
    val items: List<Series>,
    val session: String,
    val seenIds: List<String>,
)

/**
 * Official App recommendation feed. Account cookies stay on official fqnovel hosts;
 * the no-redirect client prevents credentials from being forwarded elsewhere.
 */
class RecommendationFeed(
    private val http: OkHttpClient,
    private val account: AccountCredentials,
    private val pageTarget: Int = PAGE_TARGET,
    private val maxRounds: Int = MAX_ROUNDS,
    internal val cursor: RecommendationCursor = RecommendationCursor(),
    private val playbackSession: PlaybackDeviceSession = PlaybackDeviceSession.shared,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val accountHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    /** Fetched pages stay warm so reopening the tab can replay them without skipping. */
    private val pageCache = linkedMapOf<Int, CatalogPage>()

    fun reset() = synchronized(cursor) {
        cursor.restartServer()
        cursor.expectedPage = 1
        cursor.lastPage = 0
        cursor.lastResult = null
        pageCache.clear()
        cursor.unsignedFallback = false
    }

    fun page(page: Int): CatalogPage = synchronized(cursor) {
        require(page in 1..100)
        pageCache[page]?.let { return@synchronized it }
        if (page == cursor.lastPage) return@synchronized checkNotNull(cursor.lastResult)
        if (page != cursor.expectedPage) throw IOException("推荐已刷新，请重新获取")

        val collected = linkedMapOf<String, Series>()
        var rounds = 0
        while (collected.size < pageTarget && rounds < maxRounds) {
            rounds++
            var roundComplete = false
            var lastProblem: IOException? = null
            for (attempt in 0 until 3) {
                val signed = !cursor.unsignedFallback
                val identity = if (signed) {
                    playbackSession.profile(accountHttp)
                } else {
                    null
                }
                val request = buildRequest(attempt, identity)
                val result = try {
                    RequestScope.execute(accountHttp.newCall(request)) { response ->
                        JSONObject(feedResponse(response))
                    }
                } catch (problem: IOException) {
                    lastProblem = problem
                    if (problem is InterruptedIOException) throw problem
                    continue
                }
                val parsed = try {
                    if (cursor.cellFlow != null && !cursor.unsignedFallback) parseCell(result) else parse(result)
                } catch (problem: IOException) {
                    lastProblem = problem
                    val code = result.optInt("code", -1)
                    if (signed && code == 110) {
                        cursor.unsignedFallback = true
                        cursor.restartServer()
                        continue
                    }
                    if (code != 0 && cursor.session.isNotEmpty()) {
                        cursor.restartServer()
                        continue
                    }
                    if (collected.isNotEmpty()) break
                    throw problem
                }

                val nextSession = parsed.session.ifBlank { cursor.session }
                val discoveredFlow =
                    if (!cursor.unsignedFallback && cursor.cellFlow == null) cellFlow(result) else null
                cursor.offset = when {
                    cursor.cellFlow != null && !cursor.unsignedFallback -> {
                        val next = result.getJSONObject("data").optLong("next_offset", -1)
                        if (next <= cursor.offset || next > 1_000_000)
                            throw IOException("推荐续页游标未前进，请重新获取")
                        next.toInt()
                    }
                    discoveredFlow != null -> 0
                    else -> cursor.offset + maxOf(1, rawVideoCount(result))
                }
                cursor.session = nextSession
                if (discoveredFlow != null) cursor.cellFlow = discoveredFlow

                val seen = cursor.seenIds()
                val fresh = parsed.items.filterNot { it.id in seen }
                cursor.remember(parsed.seenIds)
                fresh.forEach { item ->
                    cursor.markSeen(item.id)
                    collected.putIfAbsent(item.id, item)
                }
                roundComplete = true
                break
            }
            if (!roundComplete) {
                if (collected.isNotEmpty()) break
                throw lastProblem ?: IOException("推荐加载失败，请稍后重试")
            }
        }
        if (collected.isEmpty()) throw NoNewRecommendationsException(cursor.seenIds().size)
        val result = CatalogPage(collected.values.toList(), true)
        cursor.expectedPage = page + 1
        cursor.lastPage = page
        cursor.lastResult = result
        pageCache[page] = result
        result
    }

    private fun buildRequest(
        attempt: Int,
        identity: PlaybackDeviceSession.Profile?,
    ): Request {
        val signed = identity != null
        val flow = if (signed) cursor.cellFlow else null
        val host = FEED_HOSTS[attempt % FEED_HOSTS.size]
        val path = if (flow == null) "/reading/bookapi/bookmall/tab/v1/"
        else "/reading/bookapi/bookmall/cell/change/v1/"
        val version = if (signed) "74332" else "73532"
        val params = linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "channel" to "update_64",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "resolution" to "1280*2772",
            "dpi" to "520",
            "ac" to "wifi",
        ).apply {
            val allowed = setOf(
                "channel", "device_platform", "os", "ssmix", "device_type", "device_brand",
                "language", "os_api", "os_version", "resolution", "dpi", "ac",
            )
            putAll(account.device.filterKeys { it in allowed })
            put("version_code", version)
            put("version_name", if (signed) "7.4.3.32" else "7.3.5.32")
            put("manifest_version_code", version)
            put("update_version_code", version)
            put("tab_type", "16")
            put("bottom_tab_type", "7")
            put("landing_bottom_tab_type", "7")
            put("offset", cursor.offset.toString())
            put("client_req_type", if (cursor.offset == 0) "1" else "2")
            put("_rticket", clock().toString())
            if (cursor.session.isNotEmpty()) put("session_id", cursor.session)
            if (flow != null) {
                put("cell_id", flow.cellId)
                put("plan_id", flow.planId)
                put("version_tag", flow.versionTag)
                put("client_template", "7")
                put("change_type", "0")
                put("client_req_type", "2")
                put("filter_ids", cursor.seenIds().joinToString(","))
            }
        }

        if (identity != null) {
            val base = "$host$path"
            val values = identity.values + params + mapOf("pv_player" to "74332")
            val signature = identity.recommendationSigner(clock, values)
                .signPlayback(base, values, null, FEED_UA)
            val query = signature.url.substringAfter('?', "")
            val ticket = query.split('&')
                .firstOrNull { it.startsWith("_rticket=") }
                ?.substringAfter('=')
                ?.toLongOrNull()
            val headers = linkedMapOf(
                "User-Agent" to FEED_UA,
                "Accept" to "application/json",
                "X-XS-From-Web" to "0",
                "Sdk-Version" to "2",
            )
            headers.putAll(
                if (ticket != null) PlayerSigner.headers(query, null, ts = ticket / 1000, ticket = ticket)
                else PlayerSigner.headers(query, null)
            )
            headers["Cookie"] = account.cookie.ifBlank { identity.cookie }
            return Request.Builder().url(signature.url).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
        }

        val url = "$host$path".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url)
            .header("User-Agent", LEGACY_UA)
            .header("Accept", "application/json")
            .header("X-XS-From-Web", "0")
            .header("Sdk-Version", "2")
            .header("Cookie", account.cookie)
            .build()
    }

    companion object {
        // The TV feed only needs a few entries to start playing. Keeping the first
        // batch small avoids spending the whole tab-switch window aggregating 14 rows.
        private const val PAGE_TARGET = 4
        private const val MAX_ROUNDS = 4
        private const val FEED_UA =
            "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"
        private const val LEGACY_UA =
            "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)"
        private const val FEED_RESPONSE_LIMIT = 16 * 1024 * 1024L
        private val FEED_HOSTS = listOf(
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-hl.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com",
        )

        internal fun feedResponse(response: okhttp3.Response): String {
            if (!response.isSuccessful) throw IOException("推荐请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("推荐接口响应为空")
            if (body.contentLength() > FEED_RESPONSE_LIMIT) throw IOException("推荐接口响应过大")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (out.size() <= FEED_RESPONSE_LIMIT) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
            val text = out.toByteArray().toString(Charsets.UTF_8)
            if (text.isBlank()) throw IOException("推荐接口返回空响应，请稍后重试")
            return text
        }

        private fun cellFlow(result: JSONObject): RecommendationCursor.CellFlow? {
            val tab = result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()
                ?.firstOrNull { it.str("tab_type") == "16" } ?: return null
            if (!tab.optBoolean("bottom_unlimited") || tab.optInt("client_template", -1) != 7) return null
            val cellId = tab.optJSONArray("cell_data")?.optJSONObject(0)?.let {
                it.str("cell_id_str").ifBlank { it.str("cell_id") }
            }.orEmpty()
            val planId = tab.str("bookstore_id")
            val version = tab.str("version_tag")
            if (!cellId.matches(Regex("[1-9][0-9]{0,29}")) ||
                !planId.matches(Regex("[1-9][0-9]{0,29}")) ||
                version.length > 4096 ||
                version.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
            return RecommendationCursor.CellFlow(cellId, planId, version)
        }

        internal fun parseCell(result: JSONObject): RecommendationPage {
            if (result.optInt("code", -1) != 0)
                throw IOException("推荐续刷暂不可用（错误码 ${result.optInt("code", -1)}）")
            val data = result.optJSONObject("data") ?: throw IOException("推荐续刷数据格式已变化")
            val view = data.optJSONObject("cell_view") ?: throw IOException("推荐续刷单元缺失")
            val cell = if (view.has("cell_data")) view else JSONObject().put("cell_data", JSONArray().put(view))
            val tab = JSONObject().put("tab_type", 16).put("session_id", data.str("session_id"))
                .put("cell_data", JSONArray().put(cell))
            val normalized = JSONObject().put("code", 0)
                .put("data", JSONObject().put("tab_item", JSONArray().put(tab)))
            return parse(normalized)
        }

        internal fun rawVideoCount(result: JSONObject): Int {
            val tab = result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()
                ?.firstOrNull { it.str("tab_type") == "16" } ?: return 0
            val cells = tab.optJSONArray("cell_data") ?: return 0
            var count = 0
            for (index in 0 until cells.length()) {
                val inner = cells.optJSONObject(index)?.optJSONArray("cell_data") ?: continue
                for (row in 0 until inner.length()) {
                    count += inner.optJSONObject(row)?.optJSONArray("video_data")?.length() ?: 0
                }
            }
            return count
        }

        internal fun parse(result: JSONObject): RecommendationPage {
            if (result.optInt("code", -1) != 0)
                throw IOException("推荐暂不可用（错误码 ${result.optInt("code", -1)}），请稍后刷新")
            val tab = result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()
                ?.firstOrNull { it.str("tab_type") == "16" }
                ?: throw IOException("推荐数据格式已变化")
            val session = tab.str("session_id")
            if (session.length > 4096 || session.any { it == '\r' || it == '\n' || it == '\u0000' })
                throw IOException("推荐分页标记无效")
            val rows = tab.optJSONArray("cell_data")?.objects().orEmpty().flatMap { cell ->
                cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { card ->
                    card.optJSONArray("video_data")?.objects().orEmpty().map {
                        it.optJSONObject("video_detail") ?: it
                    }
                }
            }
            val entries = rows.mapNotNull { row ->
                val id = row.str("series_id").ifBlank { row.str("book_id") }
                val title = row.str("series_title")
                    .ifBlank { row.str("book_name") }
                    .ifBlank { row.str("title") }
                if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) null
                else {
                    val count = row.optInt("episode_cnt")
                    row to Series(
                        id,
                        title,
                        coverUrl(row.str("series_cover").ifBlank { row.str("cover") }),
                        row.str("series_intro").ifBlank { row.str("video_desc") },
                        if (count > 0) "共 $count 集" else "",
                    )
                }
            }.distinctBy { it.second.id }
            if (entries.isEmpty()) throw IOException("推荐未返回可播放的剧集，请刷新重试")
            return RecommendationPage(
                items = entries.filterNot { (row, _) -> vertical(row) == true }.map { it.second },
                session = session,
                seenIds = entries.map { it.second.id },
            )
        }

        /**
         * The account feed marks each series' orientation on its first video. Missing
         * metadata stays eligible: several server variants omit the flag, while a
         * TV-only feed must not become empty because of an unknown value.
         */
        private fun vertical(row: JSONObject): Boolean? {
            val videos = row.optJSONArray("video_list")?.objects().orEmpty()
            for (video in videos) {
                flag(video.opt("vertical"))?.let { return it }
            }
            return flag(row.opt("vertical"))
        }

        private fun flag(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "vertical", "portrait" -> true
                "false", "0", "horizontal", "landscape" -> false
                else -> null
            }
            else -> null
        }
    }
}
