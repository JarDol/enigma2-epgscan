package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class LatestValueDispatcherTest {
    @Test
    fun coalescesPendingUpdatesToTheLatestValue() {
        val pending = ArrayDeque<() -> Unit>()
        val received = mutableListOf<String>()
        val dispatcher = LatestValueDispatcher<String>(
            dispatch = { action -> pending.addLast(action) },
            onValue = { value -> received.add(value) },
        )

        dispatcher.submit("first")
        dispatcher.submit("second")

        assertEquals(1, pending.size)
        pending.removeFirst().invoke()
        assertEquals(listOf("second"), received)
    }

    @Test
    fun schedulesAnUpdateSubmittedWhileDispatching() {
        val pending = ArrayDeque<() -> Unit>()
        val received = mutableListOf<String>()
        lateinit var dispatcher: LatestValueDispatcher<String>
        dispatcher = LatestValueDispatcher<String>(dispatch = { action -> pending.addLast(action) }) { value ->
            received += value
            if (value == "first") dispatcher.submit("second")
        }

        dispatcher.submit("first")
        pending.removeFirst().invoke()
        assertEquals(1, pending.size)

        pending.removeFirst().invoke()
        assertEquals(listOf("first", "second"), received)
    }
}
