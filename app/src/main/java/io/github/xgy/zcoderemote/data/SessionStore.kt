package io.github.xgy.zcoderemote.data

import android.content.Context
import io.github.xgy.zcoderemote.security.CredentialCipher
import io.github.xgy.zcoderemote.security.RemoteUrlPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val cipher = CredentialCipher()

    @Synchronized
    fun list(): List<RemoteSession> = readEntries()
        .mapNotNull(::decryptEntry)
        .sortedByDescending(RemoteSession::lastUsedAt)

    @Synchronized
    fun find(id: String): RemoteSession? = list().firstOrNull { it.id == id }

    @Synchronized
    fun remember(parsed: RemoteUrlPolicy.Parsed): RemoteSession {
        val now = System.currentTimeMillis()
        val existing = list().firstOrNull { it.url == parsed.original }
        val session = RemoteSession(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = parsed.displayName,
            displayLocation = parsed.displayLocation,
            url = parsed.original,
            lastUsedAt = now,
        )

        val updated = list()
            .filterNot { it.id == session.id }
            .plus(session)
            .sortedByDescending(RemoteSession::lastUsedAt)
            .take(MAX_SESSIONS)
        persist(updated)
        return session
    }

    @Synchronized
    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        preferences.edit().clear().commit()
        cipher.deleteKey()
    }

    private fun readEntries(): List<JSONObject> {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decryptEntry(entry: JSONObject): RemoteSession? = runCatching {
        val url = cipher.decrypt(entry.getString("credential"))
        val parsed = RemoteUrlPolicy.parse(url)
        RemoteSession(
            id = entry.getString("id"),
            name = entry.optString("name").ifBlank { parsed.displayName },
            displayLocation = parsed.displayLocation,
            url = parsed.original,
            lastUsedAt = entry.optLong("lastUsedAt", 0L),
        )
    }.getOrNull()

    private fun persist(sessions: List<RemoteSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("name", session.name)
                    .put("lastUsedAt", session.lastUsedAt)
                    .put("credential", cipher.encrypt(session.url)),
            )
        }
        check(preferences.edit().putString(KEY_SESSIONS, array.toString()).commit()) {
            "failed to persist sessions"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_remote_sessions"
        const val KEY_SESSIONS = "sessions_v1"
        const val MAX_SESSIONS = 6
    }
}
