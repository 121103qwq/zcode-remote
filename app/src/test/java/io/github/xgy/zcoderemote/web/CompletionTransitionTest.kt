package io.github.xgy.zcoderemote.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionTransitionTest {
    @Test
    fun `initial completed page does not notify`() {
        val transition = CompletionTransition()

        assertFalse(transition.observe(CompletionTransition.PageState.IDLE))
    }

    @Test
    fun `running to idle notifies exactly once`() {
        val transition = CompletionTransition()

        assertFalse(transition.observe(CompletionTransition.PageState.RUNNING))
        assertTrue(transition.observe(CompletionTransition.PageState.IDLE))
        assertFalse(transition.observe(CompletionTransition.PageState.IDLE))
    }

    @Test
    fun `unknown samples do not erase running state`() {
        val transition = CompletionTransition()

        transition.observe(CompletionTransition.PageState.RUNNING)
        assertFalse(transition.observe(CompletionTransition.PageState.UNKNOWN))
        assertTrue(transition.observe(CompletionTransition.PageState.IDLE))
    }

    @Test
    fun `reset prevents stale completion`() {
        val transition = CompletionTransition()
        transition.observe(CompletionTransition.PageState.RUNNING)

        transition.reset()

        assertFalse(transition.observe(CompletionTransition.PageState.IDLE))
    }
}
