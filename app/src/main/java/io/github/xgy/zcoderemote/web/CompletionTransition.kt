package io.github.xgy.zcoderemote.web

class CompletionTransition {
    private var sawRunningState = false

    fun observe(state: PageState): Boolean = when (state) {
        PageState.RUNNING -> {
            sawRunningState = true
            false
        }

        PageState.IDLE -> {
            val completed = sawRunningState
            sawRunningState = false
            completed
        }

        PageState.UNKNOWN -> false
    }

    fun reset() {
        sawRunningState = false
    }

    enum class PageState {
        UNKNOWN,
        IDLE,
        RUNNING,
    }
}
