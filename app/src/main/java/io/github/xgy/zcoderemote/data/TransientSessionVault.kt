package io.github.xgy.zcoderemote.data

import io.github.xgy.zcoderemote.security.RemoteUrlPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TransientSessionVault {
    private val sessions = ConcurrentHashMap<String, RemoteSession>()

    fun put(parsed: RemoteUrlPolicy.Parsed): RemoteSession {
        val session = RemoteSession(
            id = "volatile-${UUID.randomUUID()}",
            name = parsed.displayName,
            displayLocation = parsed.displayLocation,
            url = parsed.original,
            lastUsedAt = System.currentTimeMillis(),
        )
        sessions[session.id] = session
        return session
    }

    fun find(id: String): RemoteSession? = sessions[id]

    fun remove(id: String) {
        sessions.remove(id)
    }

    fun clear() {
        sessions.clear()
    }
}
