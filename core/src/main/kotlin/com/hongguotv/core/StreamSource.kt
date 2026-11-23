// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException

/**
 * One playback address channel. The playback chain has been reworked four times
 * (drpys x-argus -> signed App API -> website public MP4 -> unsigned App API), so each
 * channel lives in its own class: when an endpoint changes, fix or replace the affected
 * channel only, or register a new one in [Companion.default] - the repository and the
 * app never change.
 *
 * Implementations must be stateless per call (thread-safe) and throw [IOException]
 * with a user-facing message on failure; the scheduler falls through to the next
 * channel and surfaces the LAST error if all channels fail.
 */
interface StreamSource {
    /** Stable identifier for logging ("app-api", "public-mp4", ...). */
    val name: String

    /**
     * Resolve the playback address for one episode. [metadataAhead] lists the next
     * episodes (at most 2) so a channel MAY batch them into one request and cache
     * the models - switching episodes then costs no fresh request (2026-11-06
     * event: the server grants each identity only ~1 request per host path, so
     * request economy is the difference between playing and failing).
     *
     * [speculative] marks a BACKGROUND read (upstream 0.1.26): try ONE route only,
     * never rotate the identity, never count empty rounds - a speculative miss
     * must not burn the foreground's failure budget or quota.
     */
    fun stream(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String> = emptyList(),
        speculative: Boolean = false,
        seriesId: String = "",
    ): StreamInfo

    /**
     * Every playable tier of one episode (upstream streamLadder, 2026-11-14 capability
     * port). The default is the single resolved address - channels without a multi-tier
     * model keep working; the multi-video-model channel overrides with the full ladder
     * so the app can verify each tier against the device's real decoder capabilities
     * without re-requesting the model per tier switch.
     */
    fun ladder(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String> = emptyList(),
        speculative: Boolean = false,
        seriesId: String = "",
    ): List<StreamInfo> =
        listOf(stream(id, maxQuality, type, metadataAhead, speculative, seriesId))

    /**
     * Re-register the device identity before a retry after a transient empty answer
     * (upstream refreshIdentity, 2026-11-14 port). Empty playback responses are keyed
     * by device id - a fresh identity's FIRST request is usually served. Channels
     * without a registered identity keep the no-op default.
     */
    fun refreshIdentity() {}

    companion object {
        /**
         * Ordered fallback chain: multi_video_model first (current App endpoint,
         * six-header signature, multi-quality + CENC keys), the retired unsigned
         * video_model second (kept as a probe - the endpoint may come back), the
         * website's public MP4 last (360p, plain, immune to App API breakage).
         */
        internal fun default(
            transport: AppApiTransport,
            http: okhttp3.OkHttpClient,
            webUa: String,
            site: String,
            webFallback: StreamSource = WebFallbackStreamSource(http, webUa, site),
            session: PlaybackDeviceSession = PlaybackDeviceSession.shared,
        ): List<StreamSource> =
            listOf(
                // Current App protocol (0.16.14): preload/v1 with the v5 Medusa
                // field-21 fix, then the same-identity video_tab GET. Keep this
                // channel first: it is the server's current gate and its requests
                // are bounded by PlaybackRouteTransport.
                MobilePlaybackStreamSource(http, session),
                MultiVideoModelStreamSource(transport, http, session),
                AppApiStreamSource(transport),
                webFallback,
                PublicMp4StreamSource(http, webUa, site),
            )
    }
}

/**
 * Runs the fallback chain: first channel that resolves wins; failures are logged and
 * skipped. Throws the last channel's error when everything fails.
 *
 * 2026-11-05: also catches RuntimeException. The 2026-11-05 server event returned
 * non-empty, non-JSON bodies (risk-control HTML) on some networks - JSONObject(text)
 * threw JSONException, a RuntimeException that used to escape this loop and kill the
 * whole chain before later channels (public MP4) could run. Parse failures are
 * channel failures, not app crashes.
 */
internal class StreamScheduler(private val sources: List<StreamSource>) {
    private val log = java.util.logging.Logger.getLogger("com.hongguotv.core.stream")

    /**
     * One refresh is enough to prove whether the anonymous identity is the problem.
     * The old four-round storm multiplied every server slowdown by the size of the
     * whole channel list and was the main cause of the multi-minute "playback timeout".
     */
    private companion object {
        const val MAX_EMPTY_RETRIES = 1
        const val FOREGROUND_BUDGET_MS = 10_000L
        const val SPECULATIVE_BUDGET_MS = 6_000L
    }

    fun stream(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String> = emptyList(),
        speculative: Boolean = false,
        seriesId: String = "",
    ): StreamInfo = PlaybackRequestBudget.run(
        if (speculative) SPECULATIVE_BUDGET_MS else FOREGROUND_BUDGET_MS
    ) {
        var last: IOException? = null
        var deterministic: PlaybackSourceUnavailableException? = null
        for (source in sources) {
            try {
                return@run source.stream(id, maxQuality, type, metadataAhead, speculative, seriesId)
            } catch (e: IOException) {
                if (e is PlaybackSourceUnavailableException) deterministic = e else last = e
                log.warning("channel ${source.name} failed for $id: $e")
            } catch (e: RuntimeException) {
                last = IOException("播放通道 ${source.name} 返回了无法解析的内容", e)
                log.warning("channel ${source.name} parse failure for $id: $e")
            }
        }
        throw deterministic ?: last ?: IOException("没有可用的播放通道")
    }

    /**
     * Same fallback chain for the full tier ladder (upstream streamLadder, 2026-11-14
     * capability port). The app walks the returned list against the device's REAL
     * decoder capabilities; channels without a multi-tier model return their single
     * address, so the verified-downgrade flow works on every channel.
     *
     * 2026-11-14 second fix (upstream streamLadder attempt loop): identity recovery is
     * a WHOLE-ROUND retry, not a per-channel retry. Upstream keeps GET and POST routes
     * in ONE loop and, when they are exhausted, calls refreshIdentity() INSIDE that
     * loop and refills the routes. Our old layout retried only the channel that had
     * just failed: a risk-control rejection on the primary channel (110001) was not
     * transient, so the loop broke out, the backup channel (with a no-op
     * refreshIdentity) burned all four "refreshes", and the primary was never tried
     * again with the new identity. Live symptom: endless "empty answer; refreshing
     * identity" retries while upstream on the same emulator played 540x960 fine,
     * and a force-stop (which drops the in-memory identity) played on the first try.
     */
    fun ladder(
        id: String,
        maxQuality: Int,
        type: ContentType,
        metadataAhead: List<String> = emptyList(),
        speculative: Boolean = false,
        seriesId: String = "",
    ): List<StreamInfo> = PlaybackRequestBudget.run(
        if (speculative) SPECULATIVE_BUDGET_MS else FOREGROUND_BUDGET_MS
    ) {
        var refreshes = 0
        while (true) {
            var last: IOException? = null
            var lastRecoverable = false
            var deterministic: PlaybackSourceUnavailableException? = null
            var recoverable = false
            for (source in sources) {
                // Capability-first playback must stay on the SIGNED App API channels
                // (2026-11-14). The website's 360p channel serves a 10-second PREVIEW
                // clip for many episodes (mvhd duration 10.0s / 514KB) while the signed
                // model for the same episode reports 63.169s. When a sporadic empty
                // response makes the chain fall through there, the player sees a valid
                // 10s MP4, ends normally, and NEVER raises an error - "this episode
                // only plays 10 seconds". Upstream has no public fallback at all, so
                // its empty answers stay on the signed path and get retried. Keep the
                // website channel for the legacy stream() path only.
                if (source.name == "public-mp4") continue
                try {
                    val tiers = source.ladder(
                        id,
                        maxQuality,
                        type,
                        metadataAhead,
                        speculative,
                        seriesId,
                    )
                    if (tiers.isNotEmpty()) return tiers
                } catch (e: IOException) {
                    if (e is PlaybackSourceUnavailableException) {
                        deterministic = e
                        lastRecoverable = false
                    } else {
                        last = e
                        lastRecoverable = identityRecoverable(e)
                    }
                    if (identityRecoverable(e)) recoverable = true
                    log.warning("channel ${source.name} ladder failed for $id: $e (identity refresh=$refreshes)")
                } catch (e: RuntimeException) {
                    last = IOException("播放通道 ${source.name} 返回了无法解析的内容", e)
                    lastRecoverable = false
                    log.warning("channel ${source.name} ladder parse failure for $id: $e")
                }
            }
            if (speculative || refreshes >= MAX_EMPTY_RETRIES || !recoverable) {
                val failure = last ?: deterministic ?: IOException("没有可用的播放通道")
                // A source that explicitly says "not available" cannot be healed by
                // another identity; keep that answer even if another channel timed out.
                if (deterministic != null) throw deterministic
                // Only the FINAL failure of the round decides the user-visible
                // category. An empty answer followed by a plain timeout must stay a
                // timeout (retryable), not become a deterministic source failure.
                if (recoverable && lastRecoverable) {
                    throw PlaybackSourceUnavailableException(
                        "片源接口暂时不可用，已停止重复重试，请稍后再试",
                        failure,
                    )
                }
                throw failure
            }
            refreshes++
            log.warning("signed round empty/rejected; refreshing ALL channel identities (refresh $refreshes/$MAX_EMPTY_RETRIES)")
            // Refresh EVERY channel, most importantly the primary one. Refreshing only
            // the channel that failed last (usually the no-op backup) rotates nothing
            // that the next round's first request actually uses.
            sources.forEach { runCatching { it.refreshIdentity() } }
            try { Thread.sleep(350L * refreshes) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("播放请求已取消")
            }
            // Start the chain over from the primary channel on the fresh identity.
        }
        throw InterruptedIOException("播放请求已取消")
    }

    /**
     * Answers a FRESH device identity heals (including wrapped causes):
     *  - 0-byte HTTP 200 (AppApiEmptyResponseException / EmptyPlaybackResponseException)
     *  - non-empty risk-control envelope, code 110001 (AppApiRejectedException)
     * Both are keyed to the registered device id; a fresh identity's FIRST playback
     * request is reliably served (2026-11-14 live probe).
     */
    private fun identityRecoverable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(6).any {
            it is AppApiEmptyResponseException || it is EmptyPlaybackResponseException ||
                it is AppApiRejectedException
        }
}

/**
 * A deterministic source failure (HTTP 403/404, protected DASH not playable by this
 * client, or an exhausted lookup). Automatic recovery must not hammer it three more
 * times; skipping episodes or a manual retry is the only useful next action.
 */
class PlaybackSourceUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
