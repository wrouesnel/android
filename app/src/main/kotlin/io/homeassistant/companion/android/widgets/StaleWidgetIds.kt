package io.homeassistant.companion.android.widgets

/**
 * Returns the IDs in [storedWidgetIds] whose configuration can be deleted because Android no longer has a widget
 * with that ID, given the [systemWidgetIds] that Android reports for the widget provider.
 *
 * When widgets are restored, for example after moving to a new device, Android reports the restored widgets under
 * new IDs straight away but only calls [android.appwidget.AppWidgetProvider.onRestored] with the mapping from the
 * old IDs later, once the whole restore has finished. Until then the configuration is still stored under the old
 * IDs. So while Android reports a widget that has no stored configuration, nothing is considered stale: the
 * configuration that looks orphaned may be waiting to move to that widget.
 */
fun staleWidgetIds(storedWidgetIds: Set<Int>, systemWidgetIds: Set<Int>): Set<Int> {
    val widgetsWithoutConfiguration = systemWidgetIds - storedWidgetIds
    if (widgetsWithoutConfiguration.isNotEmpty()) return emptySet()
    return storedWidgetIds - systemWidgetIds
}
