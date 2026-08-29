package io.github.xgy.zcoderemote.web

class AutoReconnectPolicy(
    private val delaysMillis: List<Long> = DEFAULT_DELAYS_MILLIS,
) {
    private var attempt = 0

    fun nextDelay(kind: TrustedRemoteWebViewClient.ErrorKind): Long? {
        if (kind !in RETRYABLE_ERRORS) return null
        return delaysMillis.getOrNull(attempt++)
    }

    fun reset() {
        attempt = 0
    }

    companion object {
        val DEFAULT_DELAYS_MILLIS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        private val RETRYABLE_ERRORS = setOf(
            TrustedRemoteWebViewClient.ErrorKind.NETWORK,
            TrustedRemoteWebViewClient.ErrorKind.RENDERER,
        )
    }
}
