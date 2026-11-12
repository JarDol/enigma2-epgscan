package pl.jardol.e2stream.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class LatestValueDispatcher<T : Any>(
    private val dispatch: (() -> Unit) -> Unit,
    private val onValue: (T) -> Unit,
) {
    private val latestValue = AtomicReference<T?>()
    private val dispatchPending = AtomicBoolean()

    fun submit(value: T) {
        latestValue.set(value)
        scheduleDispatch()
    }

    private fun scheduleDispatch() {
        if (!dispatchPending.compareAndSet(false, true)) return
        try {
            dispatch {
                try {
                    latestValue.getAndSet(null)?.let(onValue)
                } finally {
                    dispatchPending.set(false)
                    if (latestValue.get() != null) scheduleDispatch()
                }
            }
        } catch (e: RuntimeException) {
            dispatchPending.set(false)
            throw e
        }
    }
}
