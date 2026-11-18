package pl.jardol.e2stream

import java.util.ArrayDeque

/** Separate from the buffer lock: the player can check while a disk write is blocked. */
internal class StorageWriteHealth {
    private var startedAt: Long? = null
    private val slowWrites = ArrayDeque<Long>(3)
    private var lastStall: Pair<Long, Long>? = null

    @Synchronized fun begin(now: Long) {
        startedAt = now
    }

    @Synchronized fun end(now: Long) {
        val duration = now - checkNotNull(startedAt)
        if (duration > 1_000) {
            if (slowWrites.size == 3) slowWrites.removeFirst()
            slowWrites.addLast(now)
        }
        if (duration > 5_000) lastStall = now to duration
        startedAt = null
    }

    @Synchronized fun storageTrouble(now: Long, after: Long): String? {
        startedAt?.let { if (now - it > 5_000) return "zapis trwa ${(now - it) / 1000} s" }
        lastStall?.let { (at, duration) ->
            if (at > after && now - at <= 60_000) return "zapis trwał $duration ms"
        }
        if (slowWrites.size == 3 && now - slowWrites.first <= 60_000 && slowWrites.last > after) {
            return "co najmniej 3 zapisy > 1 s w minucie"
        }
        return null
    }

    @Synchronized fun reset() {
        startedAt = null
        slowWrites.clear()
        lastStall = null
    }
}

internal class StorageGuard {
    data class Background<T>(val buffer: T, val recording: Boolean, val failed: Boolean, val priorityTime: Long)

    var lastDropAt = -60_000L
        private set
    private var lastWarningAt = -60_000L

    fun <T> chooseDrop(now: Long, backgrounds: List<Background<T>>): T? {
        val victim = backgrounds.asSequence()
            .filterNot { it.recording }
            .minWithOrNull(compareBy<Background<T>> { !it.failed }.thenBy { it.priorityTime })
            ?: return null
        lastDropAt = now
        return victim.buffer
    }

    fun shouldWarn(now: Long): Boolean {
        if (now - lastDropAt < CHECK_MS || now - lastWarningAt < 60_000) return false
        lastWarningAt = now
        return true
    }

    companion object {
        const val CHECK_MS = 5_000L
    }
}
