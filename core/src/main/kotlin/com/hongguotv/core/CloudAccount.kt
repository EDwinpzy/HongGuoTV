// Adapted 2026-11-07 from tsukiyo-zero/hongguoTV 0.1.26, account endpoint shapes
// referenced from charlie901208/HGTV feat/phone-login (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 账户云端数据访问：封装账户相关请求与返回数据。

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class AccountCredentials(
    val cookie: String,
    val deviceId: String = "",
    val installId: String = "",
    val device: Map<String, String> = emptyMap(),
) {
    fun toJson() =
        JSONObject()
            .put("cookie", cookie)
            .put("deviceId", deviceId)
            .put("installId", installId)
            .apply {
                val table = JSONObject()
                device.forEach { (k, v) -> table.put(k, v) }
                put("device", table)
            }
            .toString()

    override fun toString() = "AccountCredentials([redacted])"

    companion object {
        fun fromJson(value: String): AccountCredentials {
            val json = JSONObject(value)
            val device = json.optJSONObject("device") ?: JSONObject()
            return AccountCredentials(
                json.getString("cookie"),
                json.str("deviceId"),
                json.str("installId"),
                device.keys().asSequence().associateWith { device.getString(it) },
            )
        }
    }
}

internal object AccountCredentialsParser {
    fun parseHeader(value: String) = AccountCredentials(value)
}

data class CloudProfile(val id: String, val nickname: String)

/**
 * 会话有效，但这个手机号还没有红果阅读业务账号（`biz_user_id` 为空）。
 *
 * 2026-11-03 实机：三个手机号里两个老账号可登录，一个新的号短信登录成功、会话也拿到了，
 * 但 `/reading/user/info/v1/` 返回的 `biz_user_id` 为空——该号从未在红果 App 侧建立账号资料。
 * 与客户端登录链路无关，提示应先到官方 App 登录一次。
 */
class AccountNotProvisionedException :
    IOException("该手机号还没有红果账号资料，请先用官方红果 App 登录一次再回来试（或改用扫码）")

data class CloudProgress(
    val series: Series,
    val episodeId: String,
    val episodeIndex: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val episodeCount: Int = 0,
)

internal class EmptyAccountResponseException(val detail: String) :
    IOException("账号接口返回空响应，请稍后重试")

internal fun cloudResponse(response: okhttp3.Response): String {
    if (!response.isSuccessful) throw IOException("账号请求失败（HTTP ${response.code}）")
    val body = response.body ?: throw IOException("账号接口响应为空")
    val limit = 2 * 1024 * 1024L
    if (body.contentLength() > limit) throw IOException("账号接口响应过大")
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val input = body.byteStream()
    while (out.size() <= limit) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    val bytes = out.toByteArray()
    if (bytes.size > limit) throw IOException("账号接口响应过大")
    val text = String(bytes, Charsets.UTF_8)
    if (text.isBlank()) {
        val protocol = if (response.protocol == okhttp3.Protocol.HTTP_2) "h2" else "h1"
        throw EmptyAccountResponseException("HTTP ${response.code}/${bytes.size}B/$protocol")
    }
    return text
}

data class CloudFavorite(val series: Series, val collectedAt: Long)

data class CloudReservation(
    val series: Series,
    val online: Boolean,
    val publishAt: Long,
    val publishText: String = "",
)

class CloudAccountClient(private val http: OkHttpClient) {
    fun registerDevice(): DeviceRegister.Registered? {
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (k, v) ->
            request.header(k, v)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        return http.newCall(request.build()).execute().use {
            DeviceRegister.parse(cloudResponse(it), it.headers("Set-Cookie"))
        }
    }

    private fun json(
        credentials: AccountCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        gzip: Boolean = false,
    ): JSONObject {
        val device =
            VendorConstants.device.toMutableMap().apply {
                putAll(credentials.device)
                put("device_id", credentials.deviceId)
                put("iid", credentials.installId)
                for (key in
                    listOf(
                        "version_code",
                        "manifest_version_code",
                        "update_version_code",
                        "pv_player",
                    )) put(key, if (body != null) "73932" else "72232")
                put("version_name", if (body != null) "7.3.9.32" else "7.2.2.32")
            }
        val wireBody =
            if (gzip && body != null)
                java.io
                    .ByteArrayOutputStream()
                    .apply { java.util.zip.GZIPOutputStream(this).use { it.write(body) } }
                    .toByteArray()
            else body
        val signed =
            Signer(device = device)
                .sign("https://api5-normal-sinfonlinea.fqnovel.com$path", device + params, wireBody)
        val request = Request.Builder().url(signed.url).header("Cookie", credentials.cookie)
        signed.headers.forEach { (k, v) -> request.header(k, v) }
        if (wireBody != null)
            request.post(wireBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (gzip) request.header("Content-Encoding", "gzip")
        val transport =
            if (path == "/reading/bookapi/bookshelf/video/update/v1/")
                http.newBuilder().retryOnConnectionFailure(false).build()
            else http
        return transport.newCall(request.build()).execute().use { response ->
            val text = try {
                cloudResponse(response)
            } catch (error: EmptyAccountResponseException) {
                // 只暴露固定阶段与传输结果，不携带请求地址、账号、Cookie 或设备身份。
                val stage = when (path) {
                    "/reading/user/info/v1/" -> "账号资料"
                    "/reading/bookapi/read_history/list/v" -> "云端观看历史"
                    "/reading/bookapi/read_progress/list/v1/" -> "云端播放进度"
                    "/reading/bookapi/read_history/update/v" -> "云端观看历史写入"
                    "/reading/bookapi/read_progress/upload/v1/" -> "云端播放进度写入"
                    "/reading/bookapi/multi-detail/v1/" -> "云端剧集资料"
                    else -> "账号"
                }
                throw IOException("${stage}接口返回空响应（${error.detail}），可刷新重试；持续失败可尝试重新登录")
            }
            val json = JSONObject(text)
            val code = json.optInt("code", -1)
            if (code != 0) throw IOException("账号接口不可用（错误码 $code），请重新登录或稍后重试")
            json
        }
    }

    fun profile(credentials: AccountCredentials): CloudProfile {
        val data =
            json(credentials, "/reading/user/info/v1/").optJSONObject("data")
                ?: throw IOException("账号资料为空")
        val id = data.str("biz_user_id")
        if (id.isBlank()) throw AccountNotProvisionedException()
        return CloudProfile(id, data.str("user_name").ifBlank { "红果用户" })
    }

    fun history(credentials: AccountCredentials): List<CloudProgress> {
        val history = readHistory(credentials)
        // The legacy progress list remains a compatibility source for rows the
        // history endpoint has not materialized yet. Newest timestamp wins.
        val progress = runCatching { readProgressList(credentials) }.getOrNull().orEmpty()
        val merged = mergeProgress(history, progress)
        return if (merged.isEmpty()) emptyList() else enrich(credentials, merged)
    }

    /**
     * Official video history is separate from playback progress. Reading only
     * read_progress makes phone-side history invisible on TV, so fetch the real
     * history list first and page until the local 200-row shelf is covered.
     */
    private fun readHistory(credentials: AccountCredentials): List<CloudProgress> {
        val result = linkedMapOf<String, CloudProgress>()
        var offset = 0L
        for (page in 0 until 20) {
            val payload =
                json(
                    credentials,
                    "/reading/bookapi/read_history/list/v",
                    mapOf(
                        "limit" to "0",
                        "offset" to offset.toString(),
                        "book_type" to "2",
                        "full_field" to "false",
                        "query_soft_deleted" to "false",
                        "last_min_read_timestamp_ms" to "0",
                        "is_first_load" to (offset == 0L).toString(),
                    ),
                )
            val data = payload.optJSONObject("data") ?: payload
            val rows = data.optJSONArray("data_list") ?: throw IOException("云端观看历史数据格式已变化")
            parseHistory(rows).forEach { row ->
                val previous = result[row.series.id]
                if (previous == null || row.updatedAt > previous.updatedAt) result[row.series.id] = row
            }
            if (result.size >= 200 || !data.optBoolean("has_more", false)) break
            if (rows.length() == 0) throw IOException("云端观看历史分页未完整读取")
            if (page == 19) throw IOException("云端观看历史分页过多，请稍后重试")
            val next = data.optLong("next_offset", offset + 500)
            offset = if (next > offset) next else offset + 500
        }
        return result.values.sortedByDescending { it.updatedAt }
    }

    private fun readProgressList(credentials: AccountCredentials): List<CloudProgress> {
        val data =
            json(
                credentials,
                "/reading/bookapi/read_progress/list/v1/",
                mapOf("book_types" to "2", "limit" to "50"),
            )
        val rows =
            data.optJSONArray("video_data")
                ?: data.optJSONObject("data")?.optJSONArray("video_data")
                ?: throw IOException("云端播放进度数据格式已变化")
        return parseProgress(rows)
    }

    private fun mergeProgress(
        history: List<CloudProgress>,
        progress: List<CloudProgress>,
    ): List<CloudProgress> =
        (history + progress)
            .groupBy { it.series.id }
            .values
            .map { rows ->
                val newest = rows.maxByOrNull { it.updatedAt }!!
                val fallback = rows.firstOrNull { it !== newest }?.series
                newest.copy(
                    series = newest.series.copy(
                        title = newest.series.title.ifBlank { fallback?.title.orEmpty() },
                        cover = newest.series.cover.ifBlank { fallback?.cover.orEmpty() },
                        description = newest.series.description.ifBlank { fallback?.description.orEmpty() },
                        badge = newest.series.badge.ifBlank { fallback?.badge.orEmpty() },
                    ),
                    episodeCount = rows.maxOf { it.episodeCount },
                )
            }
            .sortedByDescending { it.updatedAt }

    private fun enrich(credentials: AccountCredentials, rows: List<CloudProgress>): List<CloudProgress> {
        val missing =
            rows.filter { it.series.title.isBlank() || it.series.cover.isBlank() }
                .map { it.series.id }
                .distinct()
        if (missing.isEmpty()) {
            return rows.map { row ->
                if (row.series.title.isBlank()) row.copy(series = row.series.copy(title = "剧集 ${row.series.id}"))
                else row
            }
        }
        val details =
            missing.chunked(50).flatMap { batch ->
                val data =
                    json(
                        credentials,
                        "/reading/bookapi/multi-detail/v1/",
                        mapOf("book_id" to batch.joinToString(","), "book_type" to "2"),
                    ).optJSONArray("data") ?: throw IOException("云端剧集资料格式已变化")
                data.objects()
            }.associateBy { it.str("book_id") }
        return rows.map { row ->
            val detail = details[row.series.id]
            val series =
                if (detail == null) {
                    row.series.copy(title = row.series.title.ifBlank { "剧集 ${row.series.id}" })
                } else {
                    row.series.copy(
                        title = row.series.title.ifBlank { detail.str("book_name") },
                        cover = row.series.cover.ifBlank { coverUrl(detail.str("thumb_url")) },
                        description = row.series.description.ifBlank { detail.str("abstract") },
                        badge = row.series.badge.ifBlank { episodeBadge(detail) },
                    )
                }
            row.copy(series = series, episodeCount = row.episodeCount.coerceAtLeast(parseCount(series.badge)))
        }
    }

    /** Read-only video shelf. Protocol reference: charlie901208/HGTV AccountParser.favorites. */
    fun favorites(credentials: AccountCredentials): List<CloudFavorite> =
        parseFavorites(json(credentials, "/reading/bookapi/bookshelf/video/info/v1/"))

    /**
     * Video shelf only (novel bookshelf/add can acknowledge without changing video favorites).
     * Protocol reference: HGTV videoShelf public request model. No automatic retry: callers must
     * read the shelf back before reporting success.
     */
    fun setFavorite(credentials: AccountCredentials, seriesId: String, add: Boolean) {
        require(seriesId.matches(Regex("[0-9]{1,30}"))) { "剧集编号无效" }
        val entry =
            JSONObject()
                .put("book_id", seriesId)
                .put("book_type", 2)
                .put("modify_time", System.currentTimeMillis())
                .put("video_shelf_operate_type", if (add) 0 else 1)
        if (!add) entry.put("group_name", "")
        val body =
            JSONObject()
                .put("is_cancelled", false)
                .put("update_bookshelf_video_list", JSONArray().put(entry))
        if (add)
            body.put(
                "shark_extra",
                JSONObject()
                    .put("enter_from", "MainFragmentActivity")
                    .put("previous_page", "")
                    .put("page_list", "MainFragmentActivity")
                    .put("is_active_behavior", "true")
                    .put("inactive_type", "0"),
            )
        json(
            credentials,
            "/reading/bookapi/bookshelf/video/update/v1/",
            body = body.toString().toByteArray(),
        )
    }

    /**
     * Protocol reference: charlie901208/HGTV feat/phone-login, accountReserves. Keep the two
     * upstream categories separate; an unpublished series is never playable. The upstream
     * pagination contract is unverified, so expose only this requested batch.
     */
    fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation> {
        val raw =
            json(
                credentials,
                "/reading/user/subscribe/list/v0/",
                mapOf(
                    "offset" to "0",
                    "limit" to "20",
                    "is_online" to online.toString(),
                    "tab_type" to "13",
                    "subscribe_order_type" to "0",
                    "swipe_type" to "0",
                    "need_calendar_schema" to "false",
                    "subscribe_offset" to "0",
                ),
            )
        val rows = parseReservations(raw, online)
        if (!online) return rows
        val missing =
            rows.filter { EpisodeSummary.total(it.series.badge) == 0 }.map { it.series.id }
        if (missing.isEmpty()) return rows
        // Optional batch metadata: its failure must not hide a valid reservation list.
        val totals =
            runCatching {
                    json(
                            credentials,
                            "/reading/bookapi/multi-detail/v1/",
                            mapOf("book_id" to missing.joinToString(","), "book_type" to "2"),
                        )
                        .optJSONArray("data")
                        ?.objects()
                        .orEmpty()
                        .associate { it.str("book_id") to episodeBadge(it) }
                }
                .getOrDefault(emptyMap())
        return rows.map { row ->
            totals[row.series.id]
                ?.takeIf { it.isNotBlank() }
                ?.let { row.copy(series = row.series.copy(badge = it)) } ?: row
        }
    }

    /**
     * SearchUncoverSubscribeRequest fields come from the official RPC model. code=0 only
     * acknowledges the write; account-list confirmation is a separate operation.
     */
    fun reserve(credentials: AccountCredentials, work: RelatedWork) {
        require(work.upcoming && work.series.id.matches(Regex("[0-9]{1,19}")))
        val id = work.series.id.toLongOrNull() ?: throw IllegalArgumentException("预约编号无效")
        val body =
            JSONObject()
                .put("item_id", id)
                .put("item_type", 3)
                .put("op_type", 1)
                .put("subscribe_source", 0)
                .toString()
                .toByteArray()
        json(credentials, "/reading/bookapi/search/uncover_subscribe/v1/", body = body, gzip = true)
    }

    fun upload(credentials: AccountCredentials, progress: CloudProgress, episodeCount: Int) {
        require(
            progress.series.id.matches(Regex("[0-9]{1,30}")) &&
                progress.episodeId.matches(Regex("[0-9]{1,30}"))
        )
        val count = episodeCount.coerceAtLeast(progress.episodeCount)
        val stamp = progress.updatedAt
        val history =
            JSONObject()
                .put("book_id", numericId(progress.series.id))
                .put("book_type", 2)
                .put("vid", numericId(progress.episodeId))
                .put("vid_index", progress.episodeIndex + 1)
                .put("chapter_index", 0)
                .put("current_play_position", progress.position.coerceAtLeast(0))
                .put("player_accumulate_total_time", progress.position.coerceAtLeast(0))
                .put("read_timestamp_ms", stamp)
                .put("update_timestamp_ms", stamp)
                .put("is_delete", false)
                .put("is_interactive_game", false)
                .put("is_listen_mode", false)
                .put("is_multi_season", 0)
                .put("season_index", 0)
                .put("duration", progress.duration.coerceAtLeast(0))
                .put("episode_cnt", count)
                .put("digged_count", 0)
                .put("user_digg", false)
                .put("origin_novel_book_id", 0)
                .put("recent_reads", 0)
                .put("retain_video_play_time", 0)
                .put("series_play_cnt", 0)
                .put("tone_id", 0)
                .put("use_soft_delete", false)
                .put("meet_guide_comment_tag", false)
                .put("user_playlet_comment_flag", false)
        try {
            json(
                credentials,
                "/reading/bookapi/read_history/update/v",
                body = JSONObject().put("update_datas", JSONArray().put(history)).toString().toByteArray(),
            )
        } catch (error: IOException) {
            throw IOException("云端观看历史写入失败：${error.message}", error)
        }
        val entry =
            JSONObject()
                .put("book_id", progress.series.id)
                .put("item_id", progress.episodeId)
                .put("vid_index", progress.episodeIndex + 1)
                .put("current_play_time", progress.position.coerceAtLeast(0))
                .put("current_total_time", progress.duration.coerceAtLeast(0))
                .put("episode_count", count)
                .put("read_timestamp_ms", progress.updatedAt)
                .put("book_type", 2)
                .put(
                    "progress_rate",
                    if (progress.duration > 0)
                        (progress.position.toDouble() / progress.duration)
                            .coerceIn(0.0, 1.0)
                            .toString()
                    else "0",
                )
                .put("player_cumulative_total_duration", progress.position.coerceAtLeast(0))
                .put("progress_type", 0)
                .put("channel_id", 0)
                .put("cur_channel_id", 0)
                .put("check_timestamp", false)
                .put("is_listen_mode", false)
                .put("is_local_book", false)
                .put("listen_and_read", false)
                .put("page_index", 0)
                .put("page_progress_rate", 0)
                .put("paragraph_offset", 0)
                .put("tone_id", 0)
        val body =
            JSONObject()
                .put("books", JSONArray().put(entry))
                .put("write_mode", 1)
                .toString()
                .toByteArray()
        try {
            json(credentials, "/reading/bookapi/read_progress/upload/v1/", body = body)
        } catch (error: IOException) {
            throw IOException("云端播放进度写入失败：${error.message}", error)
        }
    }

    companion object {
        private fun numericId(value: String): Any = value.toLongOrNull() ?: value

        private fun episodeBadge(row: JSONObject): String {
            val count =
                listOf("episode_cnt", "episode_count", "drama_chapter_number")
                    .map { row.optInt(it) }
                    .firstOrNull { it > 0 } ?: 0
            return if (count > 0) "全 $count 集" else ""
        }

        fun parseFavorites(raw: JSONObject): List<CloudFavorite> {
            val data = raw.optJSONObject("data") ?: throw IOException("账户收藏数据格式已变化")
            if (!data.has("video_shelf_info")) throw IOException("账户收藏列表格式已变化")
            val rows =
                if (data.isNull("video_shelf_info")) JSONArray()
                else data.optJSONArray("video_shelf_info") ?: throw IOException("账户收藏列表格式已变化")
            val result =
                rows.objects().mapNotNull { row ->
                    val video = row.optJSONObject("video_data") ?: row
                    val id =
                        video
                            .str("series_id")
                            .ifBlank { row.str("series_id") }
                            .ifBlank { row.str("book_id") }
                    val title =
                        row.str("series_title")
                            .ifBlank { video.str("title") }
                            .ifBlank { row.str("book_name") }
                    if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                    val cover =
                        row.str("series_cover_url")
                            .ifBlank { video.str("cover") }
                            .ifBlank { video.str("cover_url") }
                            .ifBlank { video.str("thumb_url") }
                            .ifBlank { row.str("thumb_url") }
                    val count =
                        video.optInt(
                            "episode_cnt",
                            video.optInt("episode_count", row.optInt("episode_cnt")),
                        )
                    val tags =
                        runCatching {
                                JSONArray(row.str("category_schema"))
                                    .objects()
                                    .map { it.str("name") }
                                    .filter(String::isNotBlank)
                                    .take(5)
                                    .joinToString(" · ")
                            }
                            .getOrDefault("")
                            .ifBlank { video.str("category") }
                    val stamp =
                        row.optLong("collect_time").coerceAtLeast(0).let {
                            if (it in 1..9999999999L) it * 1000 else it
                        }
                    CloudFavorite(
                        Series(
                            id,
                            title,
                            coverUrl(cover),
                            row.str("series_intro")
                                .ifBlank { video.str("video_desc") }
                                .ifBlank { row.str("abstract") },
                            if (count > 0) "全 $count 集" else "",
                            tags,
                        ),
                        stamp,
                    )
                }
            if (rows.length() > 0 && result.isEmpty()) throw IOException("账户收藏条目格式已变化")
            return result.sortedByDescending { it.collectedAt }.distinctBy { it.series.id }
        }

        fun parseReservations(raw: JSONObject, online: Boolean): List<CloudReservation> {
            val data = raw.optJSONObject("data") ?: throw IOException("预约数据格式已变化，请稍后重试")
            // A null list is the upstream's empty category; missing/non-array data is an error.
            if (!data.has("subscribe_items")) throw IOException("预约列表格式已变化，请稍后重试")
            val rows =
                if (data.isNull("subscribe_items")) JSONArray()
                else data.optJSONArray("subscribe_items") ?: throw IOException("预约列表格式已变化，请稍后重试")
            val result =
                rows
                    .objects()
                    .mapNotNull { row ->
                        val video =
                            row.optJSONObject("subscribe_data")
                                ?: row.optJSONObject("video_data")
                                ?: row
                        val seriesId =
                            video
                                .str("series_id")
                                .ifBlank { row.str("series_id") }
                                .ifBlank { row.str("book_id") }
                        // Unpublished entries use a flat reservation item, without a playable
                        // series ID.
                        // Namespace that key so it can never be mistaken for a playback ID.
                        val id =
                            if (seriesId.matches(Regex("[0-9]{1,30}"))) seriesId
                            else if (
                                !online &&
                                    video === row &&
                                    row.str("item_id").matches(Regex("[0-9]{1,30}"))
                            )
                                "reservation:" + row.str("item_id")
                            else return@mapNotNull null
                        val title =
                            row.str("series_title")
                                .ifBlank { video.str("title") }
                                .ifBlank { video.str("book_name") }
                                .ifBlank { row.str("name") }
                        if (title.isBlank()) return@mapNotNull null
                        if (row.has("is_online") && row.optBoolean("is_online") != online)
                            return@mapNotNull null
                        val cover =
                            row.str("series_cover_url")
                                .ifBlank { video.str("cover") }
                                .ifBlank { video.str("cover_url") }
                                .ifBlank { video.str("thumb_url") }
                        val stamp =
                            row.optLong("schedule_publish_time").coerceAtLeast(0).let {
                                if (it in 1..9999999999L) it * 1000 else it
                            }
                        val series =
                            Series(
                                id,
                                title,
                                coverUrl(cover),
                                video
                                    .str("video_desc")
                                    .ifBlank { row.str("abstract") }
                                    .ifBlank { row.str("item_desc") },
                                if (online)
                                    episodeBadge(video)
                                        .ifBlank { episodeBadge(row) }
                                        .ifBlank { "已上线" }
                                else "待上线",
                                video.str("category"),
                            )
                        CloudReservation(series, online, stamp, row.str("expected_rec_text"))
                    }
                    .distinctBy { it.series.id }
            if (rows.length() > 0 && result.isEmpty()) throw IOException("预约条目格式已变化，请稍后重试")
            return result
        }

        fun parseProgress(rows: JSONArray): List<CloudProgress> =
            rows
                .objects()
                .mapNotNull { row ->
                    val id = row.str("series_id").ifEmpty { row.str("book_id") }
                    val episode = row.str("vid")
                    if (!id.matches(Regex("[0-9]{1,30}")) || !episode.matches(Regex("[0-9]{1,30}")))
                        return@mapNotNull null
                    val stamp =
                        timestamp(row)
                    CloudProgress(
                        Series(id, ""),
                        episode,
                        (row.optInt("vid_index", 1) - 1).coerceAtLeast(0),
                        row.optLong("current_play_time").coerceAtLeast(0),
                        row.optLong("current_total_time").coerceAtLeast(0),
                        stamp,
                        row.optInt("episode_count").coerceAtLeast(0),
                    )
                }
                .distinctBy { it.series.id }
                .sortedByDescending { it.updatedAt }

        fun parseHistory(rows: JSONArray): List<CloudProgress> =
            rows
                .objects()
                .mapNotNull { row ->
                    val id = row.str("book_id_str").ifBlank { row.str("book_id") }
                    val episode = row.str("vid").ifBlank { row.str("item_id") }
                    if (!id.matches(Regex("[0-9]{1,30}")) ||
                        !episode.matches(Regex("[0-9]{1,30}")) ||
                        row.optBoolean("is_delete", false)
                    ) {
                        return@mapNotNull null
                    }
                    val stamp = timestamp(row)
                    if (stamp <= 0) return@mapNotNull null
                    val count =
                        firstPositive(row, "episode_cnt", "episode_count", "drama_chapter_number")
                    val title = row.str("book_name").ifBlank { row.str("series_title") }
                    val badge = if (count > 0) "全 $count 集" else ""
                    CloudProgress(
                        Series(
                            id,
                            title,
                            coverUrl(row.str("thumb_url").ifBlank { row.str("series_cover_url") }),
                            row.str("abstract").ifBlank { row.str("series_intro") },
                            badge,
                        ),
                        episode,
                        (row.optInt("vid_index", 1) - 1).coerceAtLeast(0),
                        firstNonNegative(row, "current_play_position", "current_play_time", "player_accumulate_total_time"),
                        firstNonNegative(row, "duration", "current_total_time"),
                        stamp,
                        count,
                    )
                }
                .distinctBy { it.series.id }
                .sortedByDescending { it.updatedAt }

        private fun timestamp(row: JSONObject): Long {
            val value =
                listOf("read_timestamp_ms", "update_timestamp_ms", "last_watch_time")
                    .map { row.optLong(it) }
                    .firstOrNull { it > 0 } ?: 0L
            return if (value in 1..9_999_999_999L) value * 1000 else value
        }

        private fun firstPositive(row: JSONObject, vararg keys: String): Int =
            keys.map { row.optInt(it) }.firstOrNull { it > 0 } ?: 0

        private fun firstNonNegative(row: JSONObject, vararg keys: String): Long =
            keys.mapNotNull { key -> if (row.has(key)) row.optLong(key) else null }
                .firstOrNull { it >= 0 } ?: 0L

        private fun parseCount(badge: String): Int =
            Regex("(\\d{1,5})").find(badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
