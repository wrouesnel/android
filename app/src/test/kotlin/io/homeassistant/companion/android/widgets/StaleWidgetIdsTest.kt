package io.homeassistant.companion.android.widgets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StaleWidgetIdsTest {
    @Test
    fun `Given stored widgets missing from Android when finding stale widget IDs then they are stale`() {
        assertEquals(setOf(2, 3), staleWidgetIds(storedWidgetIds = setOf(1, 2, 3), systemWidgetIds = setOf(1)))
    }

    @Test
    fun `Given every stored widget known to Android when finding stale widget IDs then none are stale`() {
        assertEquals(emptySet<Int>(), staleWidgetIds(storedWidgetIds = setOf(1, 2), systemWidgetIds = setOf(1, 2)))
    }

    @Test
    fun `Given a widget without configuration pending restore when finding stale widget IDs then none are stale`() {
        assertEquals(emptySet<Int>(), staleWidgetIds(storedWidgetIds = setOf(11, 12), systemWidgetIds = setOf(84)))
    }

    @Test
    fun `Given no stored widgets when finding stale widget IDs then none are stale`() {
        assertEquals(emptySet<Int>(), staleWidgetIds(storedWidgetIds = emptySet(), systemWidgetIds = setOf(1)))
    }
}
