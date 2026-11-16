// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Current App playback channel (upstream N3urda 0.16.5).
 *
 * The old `/novel/player/multi_video_model/v1` endpoint now answers every request
 * with HTTP 200 and a zero-byte body. The official mobile player moved to
 * `/novel/player/multi_video_model/player/v1` and signs the exact gzip bytes with
 * the v04.10.03 carrier. This source is intentionally separate from the retired v3
 * channel: if the endpoint changes again, only this channel needs replacement.
 */
internal class MobilePlaybackStreamSource(
    private val http: OkHttpClient,
    private val session: PlaybackDeviceSession = PlaybackDeviceSession.shared,
    private val clock: () -> Long = System::currentTimeMillis,
    private val signer: Signer = sharedSigner,
) : StreamSource {
    override val name = "mobile-v5"

    private val mobileUa =
        "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"

    override fun stream(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String>,
        speculative: Boolean,
    ): StreamInfo {
        val tiers = ladder(id, maxQuality, type, metadataAhead, speculative)
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
    ): List<StreamInfo> {
        require(id.matches(Regex("[0-9]{1,30}")))
        val profile = if (speculative) session.existing()
            ?: throw IOException("播放设备尚未就绪")
        else session.profile(http)
        val tiers = request(id, profile, speculative)
        val compatible = tiers.filter { isTvCompatible(it.codec) }
        val others = tiers.filterNot { isTvCompatible(it.codec) }
        return compatible.sortedByDescending { it.qualityValue } +
            others.sortedByDescending { it.qualityValue }
    }

    override fun refreshIdentity() {
        runCatching { session.rotate(http) }
    }

    private fun request(
        id: String,
        profile: PlaybackDeviceSession.Profile,
        speculative: Boolean,
    ): List<StreamInfo> {
        if (Thread.currentThread().isInterrupted) throw IOException("请求已取消")
        val now = clock()
        val query = linkedMapOf(
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "74332",
            "version_name" to "7.4.3.32",
            "channel" to "vivo_8662_64",
            "device_platform" to "android",
            "device_type" to "PGT-AN10",
            "os_version" to "12",
            "update_version_code" to "74332",
            "iid" to profile.values.getValue("iid"),
            "cdid" to profile.values.getValue("cdid"),
            "device_id" to profile.values.getValue("device_id"),
            "_rticket" to now.toString(),
        )
        val payload = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("detail_page_version", 0)
                    .put("device_level", 3)
                    .put("disable_digg_stat", false)
                    .put("disable_video_relate_book", false)
                    .put("need_all_video_definition", true)
                    .put("need_mp4_align", false)
                    .put("use_os_player", false)
                    .put("use_server_dns", false)
                    .put("video_platform", 1024),
            )
            .put(
                "mixed_video_id_map",
                JSONObject().put("1004", JSONArray().put(id)),
            )
            .put("dr_scene", "player")
        val gzip = gzip(payload.toString().toByteArray(Charsets.UTF_8))
        val signed = signer.signPlayback(ENDPOINT, query, gzip, now)
        val request = Request.Builder()
            .url(signed.url)
            .header("User-Agent", mobileUa)
            .header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(gzip).hex().uppercase())
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post(gzip.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        if (speculative && Thread.currentThread().isInterrupted) throw IOException("请求已取消")
        return RequestScope.execute(transport.newCall(request)) { response ->
            if (!response.isSuccessful) {
                throw IOException("播放接口请求失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("播放接口响应为空")
            val limit = 2L * 1024 * 1024
            if (body.contentLength() > limit) throw IOException("播放信息过大")
            val source = body.source()
            source.request(limit + 1)
            if (source.buffer.size > limit) throw IOException("播放信息过大")
            val text = source.readUtf8()
            if (text.isBlank()) throw EmptyPlaybackResponseException("stage=mobile-v5")
            val root = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw IOException("播放接口返回了无法解析的内容", error)
            }
            val code = root.optInt("code", root.optInt("Code", -1))
            if (code != 0) throw AppApiRejectedException(code)
            val data = root.optJSONObject("data")
                ?: throw IOException("播放信息格式异常")
            val entries = data.optJSONObject("1004") ?: data
            val entry = entries.optJSONObject(id)
                ?: throw IOException("播放接口未返回本集片源")
            val model = when (val raw = entry.opt("video_model")) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            } ?: throw IOException("播放信息格式异常")
            val tiers = tiers(model)
            if (tiers.isEmpty()) {
                throw IOException("本集没有电视可播放的清晰度，请稍后再试或选择其他剧集")
            }
            tiers
        }
    }

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
        val quality = when {
            definition > 0 -> definition
            width > 0 && (height == 0 || width < height) -> width
            height > 0 -> height
            else -> 0
        }
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
        StreamInfo(
            url = url,
            key = key,
            quality = if (quality > 0) "${quality}P" else "自动",
            codec = codec,
            width = width,
            height = height,
            qualityValue = quality,
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

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    private fun lenientB64(value: String): ByteArray {
        val normalized = value.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace('-', '+')
            .replace('_', '/')
        return decode64(normalized.padEnd((normalized.length + 3) / 4 * 4, '='))
    }

    private fun isTvCompatible(codec: String) =
        codec.lowercase(Locale.ROOT) in setOf("h264", "avc", "avc1", "avc3")

    private companion object {
        const val ENDPOINT =
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"
        val sharedSigner = Signer(mobileV5 = true)
        val betterTier: Comparator<StreamInfo> =
            compareBy<StreamInfo> {
                if (it.codec.lowercase(Locale.ROOT) in setOf("h264", "avc", "avc1", "avc3")) 1 else 0
            }.thenBy { it.qualityValue }
    }
}
