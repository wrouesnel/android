package io.homeassistant.companion.android.database.widget

import kotlinx.coroutines.flow.Flow
import timber.log.Timber

interface WidgetDao<T : WidgetEntity<T>> {
    suspend fun get(id: Int): T?
    suspend fun add(entity: T)
    suspend fun delete(id: Int)
    suspend fun deleteAll(ids: IntArray)
    fun getWidgetCountFlow(): Flow<Int>
}

/**
 * Moves the configuration of each widget in [oldWidgetIds] to the widget ID at the same position in [newWidgetIds].
 *
 * Android gives restored widgets new IDs, for example after moving to a new device, and reports the mapping through
 * [android.appwidget.AppWidgetProvider.onRestored]. Widgets without a stored configuration are skipped. The two
 * lists may share IDs: every old configuration is read before anything is written.
 *
 * @return the new IDs of the widgets whose configuration was moved
 */
suspend fun <T : WidgetEntity<T>> WidgetDao<T>.moveToRestoredWidgetIds(
    oldWidgetIds: IntArray,
    newWidgetIds: IntArray,
): List<Int> {
    if (oldWidgetIds.size != newWidgetIds.size) {
        Timber.w("Ignoring widget restore with ${oldWidgetIds.size} old IDs but ${newWidgetIds.size} new IDs")
        return emptyList()
    }
    val restored = oldWidgetIds.zip(newWidgetIds).mapNotNull { (oldId, newId) ->
        get(oldId)?.copyWithWidgetId(newId)
    }
    if (restored.isNotEmpty()) {
        deleteAll(oldWidgetIds)
        restored.forEach { add(it) }
    }
    return restored.map { it.id }
}
