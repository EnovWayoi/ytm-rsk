package dev.enov.ytm

import org.junit.Assert.*
import org.junit.Test

class QueuePolicyTest {
    @Test fun homeAndSearchRecommendButCollectionsKeepTheirQueue() {
        assertTrue(recommendsSongs(PlaybackOrigin.HOME, false))
        assertTrue(recommendsSongs(PlaybackOrigin.SEARCH, false))
        assertTrue(recommendsSongs(PlaybackOrigin.RADIO, false))
        listOf(PlaybackOrigin.COLLECTION, PlaybackOrigin.LIBRARY, PlaybackOrigin.EXPLORE).forEach { assertFalse(recommendsSongs(it, false)) }
        PlaybackOrigin.entries.forEach { assertFalse(recommendsSongs(it, true)) }
    }
    @Test fun dragUsesActualVariableRowHeightsInBothDirections() {
        val rows = listOf(QueueDragRow(0, 130, 180), QueueDragRow(1, 310, 300), QueueDragRow(2, 610, 150))
        assertEquals(1, queueDropIndex(rows, 490f, 0))
        assertEquals(0, queueDropIndex(rows, 200f, 2))
        assertEquals(2, queueDropIndex(rows, 900f, 0))
        assertEquals(1, queueDropIndex(emptyList(), 10f, 1))
    }
}
