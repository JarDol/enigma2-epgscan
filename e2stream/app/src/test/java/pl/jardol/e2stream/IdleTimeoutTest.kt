package pl.jardol.e2stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdleTimeoutTest {
    private var seconds = 30
    private var task: Runnable? = null
    private var delay = 0L
    private var closed = 0
    private val timer = IdleTimeout(
        { seconds },
        { runnable, ms -> task = runnable; delay = ms },
        { task = null },
    ) { closed++ }

    @Test
    fun interactionRestartsConfiguredTimeout() {
        timer.setActive(true)
        assertEquals(30_000L, delay)
        seconds = 7
        timer.reset()
        assertEquals(7_000L, delay)
        task!!.run()
        assertEquals(1, closed)
    }

    @Test
    fun focusLossCancelsAndFocusReturnRestartsTimeout() {
        timer.setActive(true)
        val previous = task!!
        timer.setActive(false)
        assertNull(task)
        previous.run()
        assertEquals(0, closed)
        timer.setActive(true)
        assertEquals(30_000L, delay)
        task!!.run()
        assertEquals(1, closed)
    }

    @Test
    fun zeroDisablesAutomaticClosing() {
        seconds = 0
        timer.setActive(true)
        assertNull(task)
        timer.reset()
        assertNull(task)
    }
}
