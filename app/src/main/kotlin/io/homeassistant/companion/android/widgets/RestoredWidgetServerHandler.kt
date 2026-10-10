package io.homeassistant.companion.android.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.common.data.authentication.SessionState
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.data.prefs.RemovedRestoredServer
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.widget.WidgetDao
import io.homeassistant.companion.android.widgets.button.ButtonWidget
import io.homeassistant.companion.android.widgets.camera.CameraWidget
import io.homeassistant.companion.android.widgets.entity.EntityWidget
import io.homeassistant.companion.android.widgets.mediaplayer.MediaPlayerControlsWidget
import io.homeassistant.companion.android.widgets.template.TemplateWidget
import io.homeassistant.companion.android.widgets.todo.TodoWidget
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/** Widget providers that redraw their widgets and watch their entities again on [BaseWidgetProvider.UPDATE_WIDGETS]. */
private val UPDATE_WIDGETS_PROVIDERS = listOf(
    ButtonWidget::class.java,
    CameraWidget::class.java,
    EntityWidget::class.java,
    MediaPlayerControlsWidget::class.java,
    TemplateWidget::class.java,
)

/** A signed in server taking over the widgets configured for a server removed after a restore. */
private data class ServerReplacement(val removedServerId: Int, val serverId: Int)

/**
 * Keeps widgets connected to their server after the app is restored from a backup, for example on a new device.
 *
 * Backups don't include the session of a server, so the app removes restored servers and the user signs in again,
 * which adds the server under a new ID. Widgets restored with their configuration still point at the removed
 * server's ID until this points them at the server that replaces it.
 */
@Singleton
class RestoredWidgetServerHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverManager: ServerManager,
    private val prefsRepository: PrefsRepository,
    private val widgetDaos: Set<@JvmSuppressWildcards WidgetDao<*>>,
) {
    private val mutex = Mutex()

    /**
     * Remembers the restored [servers] that are about to be removed because they have no session, when widgets are
     * configured for them, so those widgets can follow the server once the user signs in again.
     */
    suspend fun rememberRemovedServers(servers: List<Server>) {
        mutex.withLock {
            val widgetServerIds = widgetServerIds()
            val removed = servers
                .filter { it.id in widgetServerIds }
                .map { RemovedRestoredServer(id = it.id, urls = it.normalizedUrls()) }
            if (removed.isEmpty()) return
            val removedIds = removed.map { it.id }.toSet()
            val alreadyRemembered = prefsRepository.getRemovedRestoredServers().filter { it.id !in removedIds }
            prefsRepository.setRemovedRestoredServers(alreadyRemembered + removed)
        }
    }

    /**
     * Each time the servers change, points the widgets configured for a remembered removed server at the signed in
     * server replacing it, then redraws the widgets. Suspends until cancelled.
     *
     * The replacement is the signed in server sharing one of the removed server's URLs. When none does, a single
     * signed in server replaces a single removed server, since most users only have one server.
     */
    suspend fun reassignWidgetsOnServerChanges() {
        serverManager.serversFlow.collect { servers ->
            if (reassignWidgets(servers)) redrawAllWidgets()
        }
    }

    private suspend fun reassignWidgets(servers: List<Server>): Boolean = mutex.withLock {
        val remembered = prefsRepository.getRemovedRestoredServers()
        if (remembered.isEmpty()) return false
        // Forget removed servers whose widgets are all gone, they have nothing left to reassign.
        val widgetServerIds = widgetServerIds()
        val stillUsed = remembered.filter { it.id in widgetServerIds }
        // Skip a removed server while its ID still exists, for example before the restored server is removed.
        val existingServerIds = servers.map { it.id }.toSet()
        val replacements = findReplacements(
            removedServers = stillUsed.filter { it.id !in existingServerIds },
            signedInServers = servers.filter { isSignedIn(it) },
        )
        replacements.forEach { replacement ->
            Timber.i("Pointing widgets of removed server ${replacement.removedServerId} at ${replacement.serverId}")
            widgetDaos.forEach {
                it.updateServerId(oldServerId = replacement.removedServerId, newServerId = replacement.serverId)
            }
        }
        val replacedIds = replacements.map { it.removedServerId }.toSet()
        val remaining = stillUsed.filter { it.id !in replacedIds }
        if (remaining != remembered) prefsRepository.setRemovedRestoredServers(remaining)
        replacements.isNotEmpty()
    }

    private suspend fun widgetServerIds(): Set<Int> = widgetDaos.flatMap { it.getServerIds() }.toSet()

    private suspend fun isSignedIn(server: Server): Boolean = server.connection.isRegistered &&
        try {
            serverManager.authenticationRepository(server.id).getSessionState() == SessionState.CONNECTED
        } catch (e: IllegalStateException) {
            Timber.w(e, "Failed to get the session state of server ${server.id}")
            false
        }

    private fun redrawAllWidgets() {
        UPDATE_WIDGETS_PROVIDERS.forEach { provider ->
            context.sendBroadcast(Intent(context, provider).setAction(BaseWidgetProvider.UPDATE_WIDGETS))
        }
        // The to-do widget only redraws here, it watches its entities again on the next screen on.
        val todoWidget = ComponentName(context, TodoWidget::class.java)
        val todoWidgetIds = AppWidgetManager.getInstance(context)?.getAppWidgetIds(todoWidget) ?: return
        if (todoWidgetIds.isNotEmpty()) {
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(todoWidget)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, todoWidgetIds),
            )
        }
    }
}

private fun findReplacements(
    removedServers: List<RemovedRestoredServer>,
    signedInServers: List<Server>,
): List<ServerReplacement> {
    val byUrl = removedServers.mapNotNull { removed ->
        signedInServers
            .singleOrNull { server -> server.normalizedUrls().any { it in removed.urls } }
            ?.let { ServerReplacement(removedServerId = removed.id, serverId = it.id) }
    }
    if (byUrl.isNotEmpty()) return byUrl
    val removed = removedServers.singleOrNull()
    val server = signedInServers.singleOrNull()
    return if (removed != null && server != null) {
        listOf(ServerReplacement(removedServerId = removed.id, serverId = server.id))
    } else {
        emptyList()
    }
}

/** The server's URLs reduced to scheme, host and port, so the same server matches however its URLs were typed. */
private fun Server.normalizedUrls(): Set<String> =
    listOfNotNull(connection.externalUrl, connection.internalUrl, connection.cloudUrl)
        .mapNotNull { it.toHttpUrlOrNull()?.resolve("/")?.toString() }
        .toSet()
