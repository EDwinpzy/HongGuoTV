// Adapted 2026-10-27 from tsukiyo-zero/hongguoTV kotlin-tv (GPL-3.0-only).
// SPDX-License-Identifier: GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PlaybackDeviceRegistrationException(cause: Throwable? = null) :
    IOException("播放设备注册失败，请稍后重试", cause)

// cloudResponse() now lives in CloudAccount.kt (upstream 0.1.26 shape): one shared
// bounded-body reader for device-register AND account endpoints.

/** Anonymous playback identity. Register once, then keep the same identity across repositories. */
class PlaybackDeviceSession(
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = ::registerOnAnyHost
) {
    companion object {
        val shared = PlaybackDeviceSession()
    }

    internal data class Profile(val values: Map<String, String>, val cookie: String) {
        private var recommendationSigner: Signer? = null

        @Synchronized
        internal fun recommendationSigner(clock: () -> Long, values: Map<String, String>): Signer =
            recommendationSigner ?: Signer(clock, values).also { recommendationSigner = it }
    }

    private var cdid = UUID.randomUUID().toString()
    private var ready: Profile? = null
    /** Independently registered spare identity (upstream v1.2.33 `SpareDevice`). */
    private var spare: Profile? = null

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return toProfile(registerDevice(http)).also { ready = it }
    }

    @Synchronized
    internal fun existing(): Profile? = ready

    /**
     * 空响应身份屏蔽自愈（2026-11-01 事件实测：屏蔽按设备身份生效，换域名与新连接无法逃逸，
     * 全新身份即时恢复）：立即重新注册一次匿名设备。成功则替换内存身份（cdid 一并换新）并返回
     * true；注册失败、中断或结果非法保留原身份返回 false。沿用同一注册流程，不做指纹伪造；
     * 频次由调用方约束（同一连续失败期至多一次）。
     */
    @Synchronized
    internal fun rotate(http: OkHttpClient): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        val device =
            try {
                register(http) ?: return false
            } catch (error: SocketTimeoutException) {
                // A stalled registration is a normal retryable failure. Marking the
                // thread interrupted here made every later request in this playback
                // worker look cancelled, turning one timeout into a dead playback.
                return false
            } catch (error: InterruptedIOException) {
                if (Thread.currentThread().isInterrupted ||
                    error.message?.contains("取消") == true ||
                    error.message?.contains("cancel", ignoreCase = true) == true
                ) {
                    Thread.currentThread().interrupt()
                }
                return false
            } catch (error: IOException) {
                return false
            }
        if (!validDevice(device)) return false
        cdid = UUID.randomUUID().toString()
        ready = toProfile(device)
        return true
    }

    /** Playback uses the anonymous registered identity, so it is always rotatable. */
    internal fun playbackRotate(http: OkHttpClient): Boolean = rotate(http)

    /**
     * A second, independently registered anonymous identity. The empty-answer /
     * 110001 shield is keyed to `device_id`, so when the main identity is cooled
     * the spare is served at once. Registered lazily on the first main-identity
     * tab failure and kept for the process life; a failed registration simply
     * returns null and the caller keeps its normal failure path.
     */
    @Synchronized
    internal fun spare(http: OkHttpClient): Profile? {
        if (Thread.currentThread().isInterrupted) return null
        spare?.let { return it }
        val device =
            try {
                register(http) ?: return null
            } catch (error: IOException) {
                return null
            }
        if (!validDevice(device) || Thread.currentThread().isInterrupted) return null
        return toProfile(device, UUID.randomUUID().toString()).also { spare = it }
    }

    private fun registerDevice(http: OkHttpClient): DeviceRegister.Registered {
        val device =
            try {
                register(http) ?: throw PlaybackDeviceRegistrationException()
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                throw PlaybackDeviceRegistrationException(error)
            }
        if (!validDevice(device)) throw PlaybackDeviceRegistrationException()
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        return device
    }

    private fun validDevice(device: DeviceRegister.Registered) =
        validId.matches(device.deviceId) && validId.matches(device.installId)

    private fun toProfile(device: DeviceRegister.Registered, cdidValue: String = cdid): Profile =
        Profile(
            VendorConstants.device +
                mapOf(
                    "device_id" to device.deviceId,
                    "iid" to device.installId,
                    "cdid" to cdidValue,
                ),
            device.cookies
                .filterKeys {
                    it in setOf("install_id", "ttreq", "store-region", "store-region-src")
                }
                .entries
                .joinToString("; ") { (name, value) -> "$name=$value" },
        )
}

private val validId = Regex("[1-9][0-9]{0,29}")

/**
 * Default registration: rotate through the equivalent registration hosts. A host
 * that times out must not spend the whole playback route budget - each host gets
 * its own short deadline via PlaybackRouteTransport and the next one is tried at
 * once. A host that ANSWERS with a rejected registration (device_id=0) proves the
 * network path is fine, so the rejection is returned as-is instead of hammering
 * the other hosts with a payload the server already declined.
 */
private fun registerOnAnyHost(http: OkHttpClient): DeviceRegister.Registered? {
    val body = DeviceRegister.payload(VendorConstants.device)
    val headers = DeviceRegister.headers(body, VendorConstants.device)
    // Registration has no account cookies. Cancellation belongs to the requesting work scope.
    val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    var lastError: IOException? = null
    for (hostIndex in 0 until DeviceRegister.hostCount()) {
        val request = Request.Builder().url(DeviceRegister.url(hostIndex))
        headers.forEach { (key, value) -> request.header(key, value) }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        try {
            // null = the host answered but rejected the registration: return it.
            return RequestScope.execute(PlaybackRouteTransport.call(transport, request.build())) { response ->
                DeviceRegister.parse(cloudResponse(response), response.headers("Set-Cookie"))
            }
        } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted || error is InterruptedIOException) throw error
            lastError = error
        }
    }
    throw lastError ?: IOException("播放设备注册失败，请稍后重试")
}
