package io.github.xgy.zcoderemote.data

/**
 * Keeps startup routing independent from Android lifecycle classes so every entry condition can
 * be covered by local unit tests.
 */
object StartupSessionPolicy {
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    fun shouldAutoOpen(
        action: String?,
        categories: Set<String>,
        isRestored: Boolean,
    ): Boolean =
        !isRestored && action == ACTION_MAIN && CATEGORY_LAUNCHER in categories

    fun select(
        designatedSessionId: String?,
        sessions: List<RemoteSession>,
    ): RemoteSession? {
        if (sessions.isEmpty()) return null
        return sessions.firstOrNull { it.id == designatedSessionId }
            ?: sessions.maxByOrNull(RemoteSession::lastUsedAt)
    }

    fun retainRecent(
        sessions: List<RemoteSession>,
        limit: Int,
        designatedSessionId: String?,
    ): List<RemoteSession> {
        require(limit > 0) { "limit must be positive" }
        val sorted = sessions.sortedByDescending(RemoteSession::lastUsedAt)
        if (sorted.size <= limit) return sorted

        val recent = sorted.take(limit)
        val designated = sorted.firstOrNull { it.id == designatedSessionId }
        if (designated == null || recent.any { it.id == designated.id }) return recent

        return recent.dropLast(1)
            .plus(designated)
            .sortedByDescending(RemoteSession::lastUsedAt)
    }
}
