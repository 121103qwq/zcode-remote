package io.github.xgy.zcoderemote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoReconnectPolicyTest {
    @Test
    fun `network failures back off and stop`() {
        val policy = AutoReconnectPolicy(listOf(10L, 20L))

        assertEquals(10L, policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.NETWORK))
        assertEquals(20L, policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.NETWORK))
        assertNull(policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.NETWORK))
    }

    @Test
    fun `security and expired errors never retry`() {
        val policy = AutoReconnectPolicy(listOf(10L))

        assertNull(policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.EXPIRED))
        assertNull(policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.SSL))
        assertNull(policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.UNSAFE))
        assertEquals(10L, policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.NETWORK))
    }

    @Test
    fun `reset restores first delay`() {
        val policy = AutoReconnectPolicy(listOf(10L, 20L))
        policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.RENDERER)

        policy.reset()

        assertEquals(10L, policy.nextDelay(TrustedRemoteWebViewClient.ErrorKind.NETWORK))
    }
}
