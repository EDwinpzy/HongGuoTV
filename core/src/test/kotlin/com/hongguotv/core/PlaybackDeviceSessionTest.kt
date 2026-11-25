package com.hongguotv.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity rotation (upstream b597f28): rotate() re-registers, failure keeps the old identity. */
class PlaybackDeviceSessionTest {
    private val http = OkHttpClient()

    @Test
    fun rotateRegistersFreshIdentityAndKeepsItCachedWithoutReregistering() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "test-only"))
        }
        val first = session.profile(http)
        assertEquals(1, calls.get())
        assertTrue(session.rotate(http))
        assertEquals(2, calls.get())
        val second = session.profile(http)
        assertEquals(2, calls.get()) // rotate already registered and cached - no re-register
        assertNotSame(first, second)
        assertNotEquals(first.values["cdid"], second.values["cdid"]) // fresh cdid = fresh identity
        assertEquals("333", second.values["device_id"])
        assertEquals("ttreq=test-only", second.cookie)
    }

    @Test
    fun rotateFailureKeepsTheWorkingIdentity() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            if (calls.incrementAndGet() == 1)
                DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "keep"))
            else throw IOException("test-only")
        }
        val first = session.profile(http)
        assertFalse(session.rotate(http))
        assertEquals(2, calls.get())
        assertSame(first, session.profile(http)) // failed rotation keeps the identity, no re-register
        assertEquals(2, calls.get())
    }

    @Test
    fun rotateIgnoresInvalidRegistrationResults() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("0", "444") // device_id "0" fails the [1-9]... pattern
        }
        assertFalse(session.rotate(http))
        // Invalid result was not cached: profile re-registers and rejects as usual.
        try {
            session.profile(http)
            throw AssertionError("expected PlaybackDeviceRegistrationException")
        } catch (expected: PlaybackDeviceRegistrationException) {
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun rotateWorksBeforeFirstProfileRegistration() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("333", "444")
        }
        assertTrue(session.rotate(http))
        assertEquals(1, calls.get())
        val profile = session.profile(http)
        assertEquals(1, calls.get()) // rotate's registration is reused, profile never re-registers
        assertEquals("333", profile.values["device_id"])
    }

    @Test
    fun registrationTimeoutDoesNotInterruptThePlaybackWorker() {
        try {
            val session = PlaybackDeviceSession { throw SocketTimeoutException("test timeout") }
            assertFalse(session.rotate(http))
            // A stalled registration must not poison every later request in this
            // worker: RequestScope treats an interrupted thread as cancelled.
            assertFalse(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    /**
     * 2026-11-24: the anonymous device pool is throttled to HTTP 200 + 0 bytes. A
     * signed-in user presents the account-bound device instead. It must be presented
     * as-is, must survive the scheduler's identity refresh (rotating away would
     * return to the throttled pool), and must be dropped on logout.
     */
    @Test
    fun trustedAccountDeviceReplacesTheAnonymousIdentityAndIsNeverRotated() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("333", "444")
        }
        session.trustAccountDevice("1905892595378490", "1905892595382586")
        val identity = session.profile(http)
        assertEquals("1905892595378490", identity.values["device_id"])
        assertEquals("1905892595382586", identity.values["iid"])
        assertEquals("", identity.cookie) // account cookies never enter the playback chain
        assertEquals(0, calls.get()) // no anonymous registration is spent

        assertFalse(session.playbackRotate(http))
        assertEquals(0, calls.get())
        val after = session.profile(http)
        assertEquals("1905892595378490", after.values["device_id"])

        session.forgetAccountDevice()
        assertNull(session.trustedIdentity())
        val anonymous = session.profile(http)
        assertEquals("333", anonymous.values["device_id"])
        assertEquals(1, calls.get())
    }

    @Test
    fun trustedAccountDeviceRejectsMalformedIds() {
        val session = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }
        session.trustAccountDevice("", "")
        session.trustAccountDevice("0", "1905892595382586")
        assertNull(session.trustedIdentity())
        assertEquals("333", session.profile(http).values["device_id"])
    }
}
