package dev.enov.ytm

internal enum class PlaybackOrigin { HOME, SEARCH, COLLECTION, LIBRARY, EXPLORE, RADIO }
internal fun recommendsSongs(origin: PlaybackOrigin, local: Boolean) = !local && origin in setOf(PlaybackOrigin.HOME, PlaybackOrigin.SEARCH, PlaybackOrigin.RADIO)

internal data class QueueDragRow(val index: Int, val offset: Int, val size: Int)
internal fun queueDropIndex(rows: List<QueueDragRow>, center: Float, fallback: Int): Int =
    rows.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) }?.index ?: fallback
