// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Current App playback channel (upstream N3urda 0.16.14).
 *
 * 2026-11-23: the server started answering HTTP 200 + 0 bytes to both the
 * retired `/novel/player/multi_video_model/v1` flow and the previous v5
 * `player/v1` request. The upstream same-request A/B isolated the wire
 * difference to Medusa field 21 (377 -> 312) together with the current
 * 7.4.5.32 App profile. The primary request now posts the gzip model body to
 * `reading.snssdk.com/novel/player/multi_video_model/preload/v1`; an empty
 * answer falls back to the same-identity `video_tab` GET, and a model that
 * only carries ByteVC2 uses the App-provided `fallback_api` codec negotiation.
 *
 * This channel stays pure Kotlin: no official App, native signing SDK, proxy
 * or website player is involved.
 */
internal class MobilePlaybackStreamSource(
    private val http: OkHttpClient,
    private val session: PlaybackDeviceSession = PlaybackDeviceSession.shared,
    private val clock: () -> Long = System::currentTimeMillis,
    private val signer: Signer = sharedSigner,
) : StreamSource {
    override val name = "mobile-v5"

    /** Current App/SDK requests are accepted with the platform HTTP agent. */
    private val playbackUa: String
        get() = System.getProperty("http.agent")
            ?.takeIf { it.isNotBlank() }
            ?: "okhttp/4.12.0"

    override fun stream(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String>,
        speculative: Boolean,
        seriesId: String,
    ): StreamInfo {
        val tiers = ladder(id, maxQuality, type, metadataAhead, speculative, seriesId)
        val best = tiers.filter { it.qualityValue in 1..maxQuality }.maxWithOrNull(betterTier)
            ?: tiers.filter { it.qualityValue <= 0 }.maxWithOrNull(betterTier)
            ?: tiers.minWithOrNull(betterTier)
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        return if (best.qualityValue > maxQuality)
            best.copy(quality = best.quality + "（兼容资源）")
        else best
    }

    override fun ladder(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String>,
        speculative: Boolean,
        seriesId: String,
    ): List<StreamInfo> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val profile = if (speculative) session.existing()
            ?: throw IOException("播放设备尚未就绪")
        else session.profile(http)
        return try {
            currentModel(id, profile)
        } catch (empty: EmptyPlaybackResponseException) {
            // Same identity, current profile: the GET is the server's documented
            // fallback when the primary preload path answers a zero-byte body.
            // When this identity is risk-controlled on the GET too, the upstream
            // desktop client (v1.2.33 SpareDevice) moves to a SPARE device for
            // the tab read instead of failing the whole episode switch: the spare
            // identity is registered ahead of time and served immediately, so the
            // extra attempt costs one GET, not a fresh registration in the path.
            videoTab(id, profile, speculative)
                ?: spareTab(id, speculative).takeIf { !speculative }
                ?: throw empty
        }
    }

    override fun refreshIdentity() {
        runCatching { session.playbackRotate(http) }
    }

    /** Primary current-App model request. */
    private fun currentModel(
        id: String,
        profile: PlaybackDeviceSession.Profile,
    ): List<StreamInfo> {
        if (Thread.currentThread().isInterrupted) throw IOException("请求已取消")
        val now = clock()
        val query = currentQuery(profile) + ("_rticket" to now.toString())
        val payload = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", 2)
                    .put("disable_digg_stat", false)
                    .put("disable_video_relate_book", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", false)
                    .put("video_platform", 1024),
            )
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(id)))
            .put("dr_scene", "preload")
        val gzip = gzip(payload.toString().toByteArray(Charsets.UTF_8))
        val signed = signer.signPlayback(MODEL_ENDPOINT, query, gzip, now)
        val request = Request.Builder()
            .url(signed.url)
            .header("User-Agent", playbackUa)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", md5(gzip))
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(gzip.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(PlaybackRouteTransport.call(transport, request)) { response ->
            if (!response.isSuccessful) {
                throw IOException("播放接口请求失败（HTTP ${response.code}）")
            }
            val text = readLimited(response, "播放接口")
            if (text.isBlank()) {
                throw EmptyPlaybackResponseException("stage=mobile-preload")
            }
            val root = json(text)
            val code = root.optInt("code", root.optInt("Code", -1))
            if (code != 0) throw AppApiRejectedException(code)
            val data = root.optJSONObject("data")
                ?: throw IOException("播放信息格式异常")
            val entries = data.optJSONObject("1004") ?: data
            val entry = entries.optJSONObject(id)
                ?: entries.keys().asSequence()
                    .mapNotNull { entries.optJSONObject(it) }
                    .firstOrNull { it.opt("video_model") != null }
                ?: throw IOException("播放接口未返回本集片源")
            val model = modelValue(entry.opt("video_model"))
                ?: throw IOException("播放信息格式异常")
            tiersOrCompatible(model, transport)
        }
    }

    /**
     * Same-identity `video_tab` GET. Its v5 signature must cover the digest of
     * EMPTY bytes (MD5("")), not the 16 zero bytes a null body would produce.
     */
    private fun videoTab(
        id: String,
        profile: PlaybackDeviceSession.Profile,
        speculative: Boolean,
    ): List<StreamInfo>? {
        if (Thread.currentThread().isInterrupted) return null
        val now = clock()
        val values = currentQuery(profile) + linkedMapOf(
            "video_id" to id,
            "video_id_type" to "0",
            "device_level" to "1",
            "need_all_video_definition" to "1",
            "need_mp4_align" to "0",
            "use_os_player" to "0",
            "video_platform" to "1024",
            "source" to "0",
            "from_video_id" to "",
            "_rticket" to now.toString(),
        )
        val hosts = if (speculative) TAB_HOSTS.take(1) else TAB_HOSTS
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        // Delayed hedge across the equivalent tab hosts (upstream `race`): the
        // first host is sent at once; while it stays silent, the next one starts
        // after HEDGE_DELAY_MS, and the first USABLE model wins. A host that
        // answers before the delay wins alone, so the healthy path still costs
        // one request. An empty body is not a win (risk-control signal) - the
        // remaining hosts keep their chance before the empty answer is returned.
        val routes = hosts.map { host -> tabRoute(host, values, now, transport) }
        return HedgedCall.run(routes, HEDGE_DELAY_MS, { PlaybackRequestBudget.deadlineNanos() }) { response ->
            if (!response.isSuccessful) throw IOException("video_tab HTTP ${response.code}")
            val text = readLimited(response, "video_tab")
            if (text.isBlank()) return@run HedgedCall.Verdict.Empty
            val root = json(text)
            if (root.optInt("code", root.optInt("Code", -1)) != 0) return@run HedgedCall.Verdict.Empty
            val data = root.optJSONObject("data") ?: return@run HedgedCall.Verdict.Empty
            val rows = data.optJSONObject("video_model_data") ?: data
            val row = rows.optJSONObject(id)
                ?: rows.keys().asSequence()
                    .mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.opt("video_model") != null }
                ?: return@run HedgedCall.Verdict.Empty
            val model = modelValue(row.opt("video_model")) ?: return@run HedgedCall.Verdict.Empty
            val tiers = tiersOrCompatible(model, transport)
            if (tiers.isEmpty()) HedgedCall.Verdict.Empty
            else HedgedCall.Verdict.Usable(tiers)
        }
    }

    /**
     * Spare-identity tab read (upstream `SpareDevice`): when the main identity's
     * GET answered empty on every host, try the SAME tab read once on a device
     * that was registered ahead of time. The upstream client keeps a small pool
     * of pre-registered identities for exactly this case; the shield is keyed to
     * the device id, so a different identity is served while the main one cools.
     */
    private fun spareTab(id: String, speculative: Boolean): List<StreamInfo>? {
        if (Thread.currentThread().isInterrupted) return null
        val spare = session.spare(http) ?: return null
        return videoTab(id, spare, speculative)
    }

    /** One signed tab GET on [host]; the call inherits the playback budget slice. */
    private fun tabRoute(
        host: String,
        values: Map<String, String>,
        now: Long,
        transport: OkHttpClient,
    ): () -> okhttp3.Call {
        // signPlayback appends and signs the final query; passing a URL that
        // already carries '?' would produce a second query string.
        val signed = signer.signPlayback("https://$host$TAB_PATH", values, ByteArray(0), now)
        val request = Request.Builder()
            .url(signed.url)
            .header("User-Agent", playbackUa)
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .removeHeader("x-ss-stub")
            .removeHeader("Content-Type")
            .removeHeader("Content-Encoding")
            .get()
            .build()
        // Each hedge gets the same playback budget slice a direct call would.
        return { PlaybackRouteTransport.call(transport, request) }
    }

    /**
     * App-provided VOD codec negotiation; no website or account headers.
     * ByteVC2-only models advertise `fallback_api` and answer `codec_type=1`
     * (H.265) or `codec_type=3` (H.264/HEVC) with TV-decodable tiers.
     */
    private fun tiersOrCompatible(model: JSONObject, transport: OkHttpClient): List<StreamInfo> {
        val inline = orderTiers(tiers(model))
        if (inline.isNotEmpty()) return inline
        val endpoint = fallbackUrl(model).takeIf { it.startsWith("https://") }
            ?: throw IOException("本集没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        for (codec in listOf("1", "3")) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            val url = endpoint.toHttpUrl().newBuilder()
                .setQueryParameter("codec_type", codec)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()
            val compatible = try {
                RequestScope.execute(transport.newCall(request)) { response ->
                    if (!response.isSuccessful) return@execute emptyList()
                    val root = json(readLimited(response, "片源兼容接口"))
                    val data = root.optJSONObject("video_info")?.optJSONObject("data")
                        ?: root.optJSONObject("data")
                        ?: return@execute emptyList()
                    orderTiers(tiers(data))
                }
            } catch (interrupted: java.io.InterruptedIOException) {
                throw interrupted
            } catch (_: IOException) {
                emptyList()
            }
            if (compatible.isNotEmpty()) return compatible
        }
        throw IOException("本集没有电视可播放的清晰度，请稍后再试或选择其他剧集")
    }

    private fun currentQuery(profile: PlaybackDeviceSession.Profile): LinkedHashMap<String, String> =
        linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "74532",
            "version_name" to "7.4.5.32",
            "update_version_code" to "74532",
            "manifest_version_code" to "74532",
            "os" to "android",
            "ssmix" to "a",
            "channel" to "update_64",
            "device_platform" to "android",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "os_version" to "16",
            "os_api" to "36",
            "ac" to "wifi",
            "language" to "zh",
            "host_abi" to "arm64-v8a",
            "iid" to profile.values.getValue("iid"),
            "cdid" to profile.values.getValue("cdid"),
            "device_id" to profile.values.getValue("device_id"),
        )

    private fun tiers(model: JSONObject): List<StreamInfo> {
        val videoList = model.opt("video_list") ?: return emptyList()
        val entries = when (videoList) {
            is JSONObject -> videoList.keys().asSequence()
                .mapNotNull { videoList.optJSONObject(it) }.toList()
            is JSONArray -> videoList.objects()
            else -> return emptyList()
        }
        val seed = model.str("key_seed").takeIf { it.isNotEmpty() }
            ?.let { runCatching { lenientB64(it) }.getOrNull() }
        return entries.mapNotNull { entry -> tier(entry, seed) }
    }

    private fun tier(entry: JSONObject, seed: ByteArray?): StreamInfo? = runCatching {
        val meta = entry.optJSONObject("video_meta")
        val codec = (entry.str("codec_type").ifEmpty {
            meta?.str("codec_type").orEmpty()
        }).lowercase(Locale.ROOT)
        val codecBlob = "$codec ${entry.str("gear_des_key")}".lowercase(Locale.ROOT)
        if (codecBlob.contains("bytevc2") ||
            Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codecBlob)
        ) return@runCatching null
        val definition = (meta?.str("definition").orEmpty().ifEmpty {
            entry.str("definition")
        }).substringBefore('p').toIntOrNull() ?: 0
        val width = (meta?.str("vwidth").orEmpty().ifEmpty {
            entry.str("vwidth")
        }).toIntOrNull() ?: 0
        val height = (meta?.str("vheight").orEmpty().ifEmpty {
            entry.str("vheight")
        }).toIntOrNull() ?: 0
        val quality = playbackQualityValue(width, height, definition)
        val rawUrl = listOf("main_url", "play_url", "backup_url_1")
            .map { entry.str(it) }
            .firstOrNull { it.isNotEmpty() }
            ?: return@runCatching null
        val url = resolveUrl(rawUrl, seed) ?: return@runCatching null
        val encryption = entry.optJSONObject("encrypt_info")
        val spade = encryption?.str("spade_a").orEmpty().ifEmpty { entry.str("spade_a") }
        val key = spade.takeIf { it.isNotEmpty() }
            ?.let { runCatching { MediaCrypto.deriveKey(it) }.getOrNull() }
        if (encryption != null && key == null &&
            encryption.opt("encrypt") !in listOf(false, 0, "0", "false")
        ) return@runCatching null
        val bitrate = sequenceOf(
            meta?.optLong("real_bitrate", 0) ?: 0,
            meta?.optLong("bitrate", 0) ?: 0,
            entry.optLong("real_bitrate", 0),
            entry.optLong("bitrate", 0),
        ).firstOrNull { it > 0 } ?: 0
        val fps = sequenceOf(
            meta?.optDouble("float_fps", 0.0) ?: 0.0,
            meta?.optDouble("fps", 0.0) ?: 0.0,
            entry.optDouble("float_fps", 0.0),
            entry.optDouble("fps", 0.0),
        ).firstOrNull { it > 0.0 }?.toFloat() ?: 0f
        StreamInfo(
            url = url,
            key = key,
            quality = if (quality > 0) "${quality}P" else "自动",
            codec = codec,
            width = width,
            height = height,
            qualityValue = quality,
            bitrate = bitrate,
            fps = fps,
        )
    }.getOrNull()

    private fun resolveUrl(raw: String, seed: ByteArray?): String? {
        val cleaned = cleanUrl(raw)
        if (cleaned.startsWith("https://") || cleaned.startsWith("http://")) return cleaned
        val decoded = runCatching { lenientB64(cleaned) }.getOrNull()
        if (decoded != null && seed != null &&
            decoded.size >= 5 && decoded[0].u() == 0xa8
        ) {
            runCatching { MediaCrypto.decryptUrl(cleaned, seed) }
                .getOrNull()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { return it }
        }
        return decoded
            ?.let { runCatching { String(it, Charsets.UTF_8).trim() }.getOrNull() }
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun orderTiers(tiers: List<StreamInfo>): List<StreamInfo> {
        val compatible = tiers.filter { isTvCompatible(it.codec) }
        val others = tiers.filterNot { isTvCompatible(it.codec) }
        return compatible.sortedByDescending { it.qualityValue } +
            others.sortedByDescending { it.qualityValue }
    }

    private fun isTvCompatible(codec: String) =
        codec.lowercase(Locale.ROOT) in setOf("h264", "avc", "avc1", "avc3")

    private fun fallbackUrl(model: JSONObject): String {
        val raw = model.opt("fallback_api")
        return when (raw) {
            is JSONObject -> raw.str("fallback_api")
            is JSONArray -> raw.optString(0)
            is String -> raw
            else -> ""
        }
    }

    private fun modelValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun json(text: String): JSONObject =
        try {
            JSONObject(text)
        } catch (error: Exception) {
            throw IOException("播放接口返回了无法解析的内容", error)
        }

    private fun readLimited(response: okhttp3.Response, label: String): String {
        val body = response.body ?: throw IOException("${label}响应为空")
        val limit = 2L * 1024 * 1024
        if (body.contentLength() > limit) throw IOException("${label}返回内容过大")
        val source = body.source()
        source.request(limit + 1)
        if (source.buffer.size > limit) throw IOException("${label}返回内容过大")
        return source.readUtf8()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    private fun md5(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02X".format(it) }

    private fun lenientB64(value: String): ByteArray {
        val normalized = value.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace('-', '+')
            .replace('_', '/')
        return decode64(normalized.padEnd((normalized.length + 3) / 4 * 4, '='))
    }

    private companion object {
        const val MODEL_ENDPOINT =
            "https://reading.snssdk.com/novel/player/multi_video_model/preload/v1"
        const val TAB_PATH = "/reading/bookapi/video_tab/multi_video_model/v1/"
        val TAB_HOSTS = listOf(
            "api5-normal-sinfonlineb.fqnovel.com",
            // Upstream desktop client v1.2.33 `videoTabModel` orders the tab
            // hosts as sinfonlineb -> sinfonlinec; hl/lf are POST-only hosts and
            // answer the GET slower or not at all. The first host that produces a
            // usable model wins, so the second entry is only a hedge.
            "api5-normal-sinfonlinec.fqnovel.com",
        )
        /** Stagger between hedge starts; a healthy host answers well inside it. */
        const val HEDGE_DELAY_MS = 1_200L
        val sharedSigner = Signer.sharedV5()
        val betterTier: Comparator<StreamInfo> =
            compareBy<StreamInfo> {
                if (it.codec.lowercase(Locale.ROOT) in setOf("h264", "avc", "avc1", "avc3")) 1 else 0
            }.thenBy { it.qualityValue }
    }
}
