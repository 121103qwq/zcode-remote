package io.github.xgy.zcoderemote.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlPolicyTest {
    @Test
    fun `accepts current v4 URL and preserves signed bytes`() {
        val input = "https://zcode.z.ai/remote/v4/?hash=a%2Bb&sid=device-1&t=1724800000&name=My+PC&future=x%2Fy"

        val parsed = RemoteUrlPolicy.parse(input)

        assertEquals(input, parsed.original)
        assertEquals("My PC", parsed.displayName)
        assertEquals("https://zcode.z.ai/remote/v4/", parsed.displayLocation)
    }

    @Test
    fun `accepts desktop metadata without rebuilding the signed query`() {
        val input = "https://zcode.z.ai/remote/v4?sid=test-device&hash=test%2Bhash%3D&t=1800000000000&mid=test-mid&name=DESKTOP-TEST&app_version=9.9.9"

        val parsed = RemoteUrlPolicy.parse(input)

        assertEquals(input, parsed.original)
        assertEquals("DESKTOP-TEST", parsed.displayName)
        assertEquals("https://zcode.z.ai/remote/v4", parsed.displayLocation)
    }

    @Test
    fun `accepts legacy official v3 URL`() {
        assertNotNull(
            RemoteUrlPolicy.parseOrNull(
                "https://zcode.z.ai/remote/v3?sid=s&hash=h&t=1724800000",
            ),
        )
    }

    @Test
    fun `trims only outer whitespace`() {
        val parsed = RemoteUrlPolicy.parse(
            "  https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1724800000  ",
        )
        assertEquals(
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1724800000",
            parsed.original,
        )
    }

    @Test
    fun `rejects lookalike and credential-smuggling hosts`() {
        val attacks = listOf(
            "https://zcode.z.ai.evil.example/remote/v4?sid=s&hash=h&t=1",
            "https://evil.example/?next=zcode.z.ai/remote/v4&sid=s&hash=h&t=1",
            "https://zcode.z.ai@evil.example/remote/v4?sid=s&hash=h&t=1",
            "https://evil-zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
            "https://foo.z.ai/remote/v4?sid=s&hash=h&t=1",
        )
        attacks.forEach { attack -> assertNull(attack, RemoteUrlPolicy.parseOrNull(attack)) }
    }

    @Test
    fun `rejects unsafe schemes ports fragments and paths`() {
        val attacks = listOf(
            "http://zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
            "https://zcode.z.ai:444/remote/v4?sid=s&hash=h&t=1",
            "https://zcode.z.ai/remote/v5?sid=s&hash=h&t=1",
            "https://zcode.z.ai/remote/v4/extra?sid=s&hash=h&t=1",
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1#secret",
            "javascript:alert(1)",
            "file:///remote/v4?sid=s&hash=h&t=1",
            "data:text/html,zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
            "intent://zcode.z.ai/remote/v4?sid=s&hash=h&t=1",
        )
        attacks.forEach { attack -> assertNull(attack, RemoteUrlPolicy.parseOrNull(attack)) }
    }

    @Test
    fun `requires one complete credential set and numeric timestamp`() {
        val invalid = listOf(
            "https://zcode.z.ai/remote/v4?hash=h&t=1",
            "https://zcode.z.ai/remote/v4?sid=s&t=1",
            "https://zcode.z.ai/remote/v4?sid=s&hash=h",
            "https://zcode.z.ai/remote/v4?sid=s&sid=other&hash=h&t=1",
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=tomorrow",
        )
        invalid.forEach { value -> assertNull(value, RemoteUrlPolicy.parseOrNull(value)) }
    }

    @Test
    fun `navigation stays on exact official origin`() {
        assertTrue(
            RemoteUrlPolicy.isTrustedTopLevelNavigation(
                "https://zcode.z.ai/remote/v4/assets/app.js",
            ),
        )
        assertFalse(
            RemoteUrlPolicy.isTrustedTopLevelNavigation(
                "https://docs.z.ai/remote/help",
            ),
        )
        assertFalse(
            RemoteUrlPolicy.isTrustedTopLevelNavigation(
                "https://zcode.z.ai.evil.example/",
            ),
        )
    }
}
