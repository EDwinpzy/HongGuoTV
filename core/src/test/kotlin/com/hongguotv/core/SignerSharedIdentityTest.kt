package com.hongguotv.core

import org.junit.Assert.assertSame
import org.junit.Test

class SignerSharedIdentityTest {
    @Test
    fun v5PlaybackKeepsOneProcessIdentityAcrossRepositories() {
        assertSame(Signer.sharedV5(), Signer.sharedV5())
    }
}
