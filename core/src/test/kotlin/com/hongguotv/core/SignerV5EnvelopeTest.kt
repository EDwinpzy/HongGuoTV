package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reviewed synthetic vector for upstream PR #122's complete Medusa v5 envelope. */
class SignerV5EnvelopeTest {
    @Test fun matchesPlaybackWireContractWithCompleteDeviceEnvelope() {
        val fixture = JSONObject(
            javaClass.getResource("/signing-v5-playback-envelope.json")!!.readText(),
        )
        val values = fixture.getJSONObject("identity")
        val order = fixture.getJSONArray("queryOrder")
        val identity = (0 until order.length()).associate {
            order.getString(it).let { key -> key to values.getString(key) }
        }
        val signer = Signer(
            { 1800000000000 },
            identity,
            { 0.5 },
            "synthetic",
            mobileV5 = true,
        )
        val actual = signer.signPlayback(
            "https://example.test/model",
            identity,
            decode64(fixture.getString("body")),
            1800000000000,
        )
        assertEquals(fixture.getString("url"), actual.url)
        val expected = fixture.getJSONObject("headers")
        assertEquals(expected.keys().asSequence().toSet(), actual.headers.keys)
        expected.keys().forEach {
            assertEquals(it, expected.getString(it), actual.headers[it])
        }
    }
}
