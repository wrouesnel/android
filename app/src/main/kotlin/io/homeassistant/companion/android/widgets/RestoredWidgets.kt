package io.homeassistant.companion.android.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import io.homeassistant.companion.android.database.widget.WidgetDao
import io.homeassistant.companion.android.database.widget.WidgetEntity
import io.homeassistant.companion.android.database.widget.moveToRestoredWidgetIds

/**
 * Widget option telling the home screen that a restored widget no longer needs to be set up.
 *
 * Same value as `AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED`, which the SDK only exposes from API 31 while
 * home screens such as Launcher3 read it on every version.
 */
private const val OPTION_APPWIDGET_RESTORE_COMPLETED = "appWidgetRestoreCompleted"

/**
 * Moves the configuration of restored widgets from their [oldWidgetIds] to their [newWidgetIds], as reported by
 * [android.appwidget.AppWidgetProvider.onRestored], and tells the home screen which widgets got their configuration
 * back.
 *
 * Home screens show a restored widget that has a configuration activity as a placeholder asking the user to set it
 * up again, until the app marks the restore of that widget as completed.
 */
suspend fun <T : WidgetEntity<T>> WidgetDao<T>.restoreWidgets(
    context: Context,
    oldWidgetIds: IntArray,
    newWidgetIds: IntArray,
) {
    val restoredWidgetIds = moveToRestoredWidgetIds(oldWidgetIds, newWidgetIds)
    if (restoredWidgetIds.isEmpty()) return
    val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
    val restoreCompleted = Bundle().apply { putBoolean(OPTION_APPWIDGET_RESTORE_COMPLETED, true) }
    restoredWidgetIds.forEach { appWidgetId ->
        appWidgetManager.updateAppWidgetOptions(appWidgetId, restoreCompleted)
    }
}
