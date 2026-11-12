package pl.jardol.e2stream.desktop

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HidUsageRegistryTest {
    private val first = 12.toShort() to 1.toShort()
    private val second = 1.toShort() to 6.toShort()

    @Test
    fun registersOnlyNewUsagesAndIgnoresReentrantNotifications() {
        val calls = mutableListOf<Set<Pair<Short, Short>>>()
        lateinit var registry: HidUsageRegistry
        registry = HidUsageRegistry { usages ->
            calls += usages
            registry.registerNew(usages)
        }
        registry.registerNew(listOf(first))
        registry.registerNew(listOf(first, second))
        registry.registerNew(listOf(second))
        assertEquals(listOf(setOf(first), setOf(second)), calls)
    }

    @Test
    fun retriesAfterRegistrationFailure() {
        var attempts = 0
        val registry = HidUsageRegistry {
            attempts++
            if (attempts == 1) throw IOException("registration failed")
        }
        assertFailsWith<IOException> { registry.registerNew(listOf(first)) }
        registry.registerNew(listOf(first))
        assertEquals(2, attempts)
    }
}
