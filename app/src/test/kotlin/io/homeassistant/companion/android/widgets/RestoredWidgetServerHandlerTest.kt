package io.homeassistant.companion.android.widgets

import dagger.hilt.android.testing.HiltTestApplication
import io.homeassistant.companion.android.common.data.authentication.SessionState
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.data.prefs.RemovedRestoredServer
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.Server
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.database.server.ServerSessionInfo
import io.homeassistant.companion.android.database.server.ServerUserInfo
import io.homeassistant.companion.android.database.widget.ButtonWidgetDao
import io.homeassistant.companion.android.database.widget.CameraWidgetDao
import io.homeassistant.companion.android.database.widget.MediaPlayerControlsWidgetDao
import io.homeassistant.companion.android.database.widget.StaticWidgetDao
import io.homeassistant.companion.android.database.widget.TemplateWidgetDao
import io.homeassistant.companion.android.database.widget.TodoWidgetDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val REMOVED_SERVER_ID = 1
private const val SIGNED_IN_SERVER_ID = 2

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class RestoredWidgetServerHandlerTest {
    private val serversFlow = MutableStateFlow<List<Server>>(emptyList())
    private val serverManager: ServerManager = mockk(relaxed = true) {
        coEvery { this@mockk.serversFlow } returns this@RestoredWidgetServerHandlerTest.serversFlow
        coEvery { authenticationRepository(any()).getSessionState() } returns SessionState.CONNECTED
    }

    private var rememberedServers = emptyList<RemovedRestoredServer>()
    private val prefsRepository: PrefsRepository = mockk {
        coEvery { getRemovedRestoredServers() } answers { rememberedServers }
        coEvery { setRemovedRestoredServers(any()) } answers { rememberedServers = firstArg() }
    }

    private val buttonWidgetDao: ButtonWidgetDao = mockk(relaxed = true)
    private val cameraWidgetDao: CameraWidgetDao = mockk(relaxed = true)
    private val mediaPlayerControlsWidgetDao: MediaPlayerControlsWidgetDao = mockk(relaxed = true)
    private val staticWidgetDao: StaticWidgetDao = mockk(relaxed = true)
    private val templateWidgetDao: TemplateWidgetDao = mockk(relaxed = true)
    private val todoWidgetDao: TodoWidgetDao = mockk(relaxed = true)

    private val handler = RestoredWidgetServerHandler(
        context = RuntimeEnvironment.getApplication(),
        serverManager = serverManager,
        prefsRepository = prefsRepository,
        widgetDaos = setOf(
            buttonWidgetDao,
            cameraWidgetDao,
            mediaPlayerControlsWidgetDao,
            staticWidgetDao,
            templateWidgetDao,
            todoWidgetDao,
        ),
    )

    private fun server(id: Int, externalUrl: String, registered: Boolean = true) = Server(
        id = id,
        _name = "Home",
        connection = ServerConnectionInfo(externalUrl = externalUrl, webhookId = if (registered) "webhook" else null),
        session = ServerSessionInfo(),
        user = ServerUserInfo(id = null, name = null, isOwner = false, isAdmin = false),
    )

    private fun widgetsUseServers(vararg serverIds: Int) {
        coEvery { staticWidgetDao.getServerIds() } returns serverIds.toList()
    }

    private fun TestScope.observeServers(servers: List<Server>) {
        backgroundScope.launch { handler.reassignWidgetsOnServerChanges() }
        serversFlow.value = servers
        // The collector runs in the background scope, which advanceUntilIdle doesn't wait for.
        runCurrent()
    }

    private fun verifyWidgetsMoved(oldServerId: Int, newServerId: Int) {
        listOf(
            buttonWidgetDao,
            cameraWidgetDao,
            mediaPlayerControlsWidgetDao,
            staticWidgetDao,
            templateWidgetDao,
            todoWidgetDao,
        ).forEach { dao -> coVerify(exactly = 1) { dao.updateServerId(oldServerId, newServerId) } }
    }

    private fun verifyNoWidgetMoved() {
        coVerify(exactly = 0) { staticWidgetDao.updateServerId(any(), any()) }
    }

    @Test
    fun `Given restored servers when remembering removed servers then only those used by widgets are kept with normalized URLs`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID)

        handler.rememberRemovedServers(
            listOf(server(id = REMOVED_SERVER_ID, externalUrl = "http://HA.local:8123/lovelace/0"), server(id = 5, externalUrl = "http://other.local")),
        )

        assertEquals(listOf(RemovedRestoredServer(id = REMOVED_SERVER_ID, urls = setOf("http://ha.local:8123/"))), rememberedServers)
    }

    @Test
    fun `Given a signed in server with the removed server's URL when servers change then widgets move to it and it is forgotten`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID)
        handler.rememberRemovedServers(listOf(server(id = REMOVED_SERVER_ID, externalUrl = "http://ha.local:8123")))

        observeServers(listOf(server(id = 3, externalUrl = "http://other.local"), server(id = SIGNED_IN_SERVER_ID, externalUrl = "http://HA.local:8123/")))

        verifyWidgetsMoved(oldServerId = REMOVED_SERVER_ID, newServerId = SIGNED_IN_SERVER_ID)
        assertEquals(emptyList<RemovedRestoredServer>(), rememberedServers)
    }

    @Test
    fun `Given one removed server and one signed in server with another URL when servers change then widgets move to it`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID)
        handler.rememberRemovedServers(listOf(server(id = REMOVED_SERVER_ID, externalUrl = "http://ha.local:8123")))

        observeServers(listOf(server(id = SIGNED_IN_SERVER_ID, externalUrl = "https://example.ui.nabu.casa")))

        verifyWidgetsMoved(oldServerId = REMOVED_SERVER_ID, newServerId = SIGNED_IN_SERVER_ID)
    }

    @Test
    fun `Given two removed servers and a signed in server matching neither when servers change then no widget moves`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID, 3)
        handler.rememberRemovedServers(
            listOf(server(id = REMOVED_SERVER_ID, externalUrl = "http://home.local"), server(id = 3, externalUrl = "http://cabin.local")),
        )

        observeServers(listOf(server(id = 4, externalUrl = "https://example.ui.nabu.casa")))

        verifyNoWidgetMoved()
        assertEquals(2, rememberedServers.size)
    }

    @Test
    fun `Given the removed server still exists when servers change then no widget moves and it stays remembered`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID)
        val restored = server(id = REMOVED_SERVER_ID, externalUrl = "http://ha.local:8123")
        handler.rememberRemovedServers(listOf(restored))

        observeServers(listOf(restored, server(id = SIGNED_IN_SERVER_ID, externalUrl = "http://ha.local:8123")))

        verifyNoWidgetMoved()
        assertEquals(1, rememberedServers.size)
    }

    @Test
    fun `Given the new server isn't registered yet when servers change then no widget moves`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID)
        handler.rememberRemovedServers(listOf(server(id = REMOVED_SERVER_ID, externalUrl = "http://ha.local:8123")))

        observeServers(listOf(server(id = SIGNED_IN_SERVER_ID, externalUrl = "http://ha.local:8123", registered = false)))

        verifyNoWidgetMoved()
        assertEquals(1, rememberedServers.size)
    }

    @Test
    fun `Given the removed server's widgets are gone when servers change then it is forgotten without moving widgets`() = runTest {
        widgetsUseServers(REMOVED_SERVER_ID)
        handler.rememberRemovedServers(listOf(server(id = REMOVED_SERVER_ID, externalUrl = "http://ha.local:8123")))
        widgetsUseServers()

        observeServers(listOf(server(id = SIGNED_IN_SERVER_ID, externalUrl = "http://ha.local:8123")))

        verifyNoWidgetMoved()
        assertEquals(emptyList<RemovedRestoredServer>(), rememberedServers)
    }
}
