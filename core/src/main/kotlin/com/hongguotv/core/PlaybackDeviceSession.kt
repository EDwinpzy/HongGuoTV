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
    private val register: (OkHttpClient) -> DeviceRegister.Registered? = { http ->
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (key, value) ->
            request.header(key, value)
        }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        // Registration has no account cookies. Cancellation belongs to the requesting work scope.
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        RequestScope.execute(PlaybackRouteTransport.call(transport,request.build())) { response ->
            DeviceRegister.parse(cloudResponse(response), response.headers("Set-Cookie"))
        }
    }
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

    /**
     * 2026-11-24 事件：匿名注册的设备池整体被限流（HTTP 200 + 0 字节），而同一台
     * 设备、同一网络出口上的账号接口全部正常。登录后把账号绑定的设备身份交给本会话，
     * 全部播放通道自动改用它（这是一台服务端已经在登录流程里见过的设备，而不是一次
     * 性的匿名注册）；退出登录清除后回落匿名注册。注意这是必要条件而非充分条件：
     * 上游限流存在与身份无关的时间窗口，那段窗口里任何身份的播放请求都是空的。
     */
    @Volatile private var trusted: TrustedIdentity? = null

    /** 账号登录时绑定的设备身份（[AccountCredentials.deviceId] / [AccountCredentials.installId]）。 */
    internal data class TrustedIdentity(val deviceId: String, val installId: String)

    /** 登录后调用；设备身份无效时保持现状。 */
    @Synchronized
    fun trustAccountDevice(deviceId: String, installId: String) {
        if (!validId.matches(deviceId) || !validId.matches(installId)) return
        if (trusted?.deviceId == deviceId && trusted?.installId == installId) return
        trusted = TrustedIdentity(deviceId, installId)
        // The anonymous identity is no longer what playback should present.
        ready = null
    }

    /** 退出登录后调用：播放回到匿名注册设备。 */
    @Synchronized
    fun forgetAccountDevice() {
        if (trusted == null) return
        trusted = null
        ready = null
    }

    /** True while playback presents the account-bound device instead of an anonymous one. */
    fun hasTrustedAccountDevice(): Boolean = trusted != null

    /** The identity playback presents right now (null before the first request). */
    @Synchronized
    internal fun trustedIdentity(): TrustedIdentity? = trusted

    @Synchronized
    internal fun profile(http: OkHttpClient): Profile {
        ready?.let {
            return it
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("播放请求已取消")
        trusted?.let { return toProfile(it).also { ready = it } }
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

    /**
     * Playback uses the anonymous registered identity, so it is always rotatable -
     * EXCEPT while an account device is trusted: that identity is the one the
     * server serves, and rotating away from it would return to the throttled pool.
     */
    internal fun playbackRotate(http: OkHttpClient): Boolean {
        if (trusted != null) return false
        return rotate(http)
    }

    private fun toProfile(identity: TrustedIdentity): Profile =
        Profile(
            VendorConstants.device +
                mapOf(
                    "device_id" to identity.deviceId,
                    "iid" to identity.installId,
                    "cdid" to cdid,
                ),
            "",
        )

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

    private fun toProfile(device: DeviceRegister.Registered): Profile =
        Profile(
            VendorConstants.device +
                mapOf(
                    "device_id" to device.deviceId,
                    "iid" to device.installId,
                    "cdid" to cdid,
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
