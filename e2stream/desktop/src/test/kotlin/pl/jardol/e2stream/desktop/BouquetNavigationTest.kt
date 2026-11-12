package pl.jardol.e2stream.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BouquetNavigationTest {
    private val bouquets = listOf(
        Bouquet("TV", "tv"),
        Bouquet("Sport", "sport"),
        Bouquet("Movies", "movies"),
    )

    @Test
    fun movesBetweenBouquetsAndWrapsAround() {
        assertEquals(bouquets[1], bouquetOffset(bouquets, bouquets[0], 1))
        assertEquals(bouquets[2], bouquetOffset(bouquets, bouquets[0], -1))
        assertEquals(bouquets[0], bouquetOffset(bouquets, bouquets[2], 1))
        assertEquals(bouquets[2], bouquetOffset(bouquets, bouquets[0], -1))
    }

    @Test
    fun leavesBouquetUnchangedWhenSelectionIsUnavailable() {
        assertNull(bouquetOffset(emptyList(), bouquets[0], 1))
        assertNull(bouquetOffset(bouquets, null, 1))
        assertNull(bouquetOffset(bouquets, bouquets[0], 0))
    }
}
