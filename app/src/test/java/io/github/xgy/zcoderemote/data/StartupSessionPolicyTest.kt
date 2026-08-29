package io.github.xgy.zcoderemote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSessionPolicyTest {
    @Test
    fun `fresh launcher start auto opens`() {
        assertTrue(
            StartupSessionPolicy.shouldAutoOpen(
                action = "android.intent.action.MAIN",
                categories = setOf("android.intent.category.LAUNCHER"),
                isRestored = false,
            ),
        )
    }

    @Test
    fun `restored activity does not auto open`() {
        assertFalse(
            StartupSessionPolicy.shouldAutoOpen(
                action = "android.intent.action.MAIN",
                categories = setOf("android.intent.category.LAUNCHER"),
                isRestored = true,
            ),
        )
    }

    @Test
    fun `shared text and deep links do not auto open`() {
        assertFalse(
            StartupSessionPolicy.shouldAutoOpen(
                action = "android.intent.action.SEND",
                categories = setOf("android.intent.category.DEFAULT"),
                isRestored = false,
            ),
        )
        assertFalse(
            StartupSessionPolicy.shouldAutoOpen(
                action = "android.intent.action.VIEW",
                categories = setOf("android.intent.category.BROWSABLE"),
                isRestored = false,
            ),
        )
    }

    @Test
    fun `main without launcher category does not auto open`() {
        assertFalse(
            StartupSessionPolicy.shouldAutoOpen(
                action = "android.intent.action.MAIN",
                categories = emptySet(),
                isRestored = false,
            ),
        )
    }

    @Test
    fun `designated session wins over more recent session`() {
        val designated = session("fixed", 1L)
        val latest = session("latest", 2L)

        assertEquals(
            designated,
            StartupSessionPolicy.select("fixed", listOf(latest, designated)),
        )
    }

    @Test
    fun `missing designation falls back to latest session`() {
        val old = session("old", 1L)
        val latest = session("latest", 3L)

        assertEquals(
            latest,
            StartupSessionPolicy.select("missing", listOf(old, latest)),
        )
        assertEquals(latest, StartupSessionPolicy.select(null, listOf(old, latest)))
    }

    @Test
    fun `empty history has no startup session`() {
        assertNull(StartupSessionPolicy.select("missing", emptyList()))
    }

    @Test
    fun `retention keeps designated session plus newest entries`() {
        val sessions = (1L..7L).map { timestamp -> session("id-$timestamp", timestamp) }

        val retained = StartupSessionPolicy.retainRecent(
            sessions = sessions,
            limit = 3,
            designatedSessionId = "id-1",
        )

        assertEquals(listOf("id-7", "id-6", "id-1"), retained.map(RemoteSession::id))
    }

    private fun session(id: String, lastUsedAt: Long) = RemoteSession(
        id = id,
        name = id,
        displayLocation = "https://zcode.z.ai/remote/v4",
        url = "https://zcode.z.ai/remote/v4?sid=$id&hash=x&t=1",
        lastUsedAt = lastUsedAt,
    )
}
