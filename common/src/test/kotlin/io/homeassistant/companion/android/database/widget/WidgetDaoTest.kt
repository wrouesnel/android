package io.homeassistant.companion.android.database.widget

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private class FakeWidgetDao(vararg entities: TodoWidgetEntity) : WidgetDao<TodoWidgetEntity> {
    val stored = entities.associateBy { it.id }.toMutableMap()

    override suspend fun get(id: Int): TodoWidgetEntity? = stored[id]
    override suspend fun getServerIds(): List<Int> = stored.values.map { it.serverId }.distinct()
    override suspend fun updateServerId(oldServerId: Int, newServerId: Int) {
        stored.replaceAll { _, entity -> if (entity.serverId == oldServerId) entity.copy(serverId = newServerId) else entity }
    }
    override suspend fun add(entity: TodoWidgetEntity) {
        stored[entity.id] = entity
    }
    override suspend fun delete(id: Int) {
        stored.remove(id)
    }
    override suspend fun deleteAll(ids: IntArray) {
        ids.forEach { stored.remove(it) }
    }
    override fun getWidgetCountFlow(): Flow<Int> = MutableStateFlow(stored.size)
}

private fun widget(id: Int, entityId: String) = TodoWidgetEntity(id = id, serverId = 1, entityId = entityId)

class WidgetDaoTest {
    @Test
    fun `Given a restored widget when moving to restored widget IDs then its configuration is under the new ID only`() = runTest {
        val dao = FakeWidgetDao(widget(id = 11, entityId = "todo.shopping"))

        val restoredWidgetIds = dao.moveToRestoredWidgetIds(oldWidgetIds = intArrayOf(11), newWidgetIds = intArrayOf(84))

        assertEquals(listOf(84), restoredWidgetIds)
        assertEquals(mapOf(84 to widget(id = 84, entityId = "todo.shopping")), dao.stored)
    }

    @Test
    fun `Given old and new widget IDs overlapping when moving to restored widget IDs then no configuration is overwritten`() = runTest {
        val dao = FakeWidgetDao(widget(id = 1, entityId = "todo.first"), widget(id = 2, entityId = "todo.second"))

        dao.moveToRestoredWidgetIds(oldWidgetIds = intArrayOf(1, 2), newWidgetIds = intArrayOf(2, 3))

        assertEquals(
            mapOf(2 to widget(id = 2, entityId = "todo.first"), 3 to widget(id = 3, entityId = "todo.second")),
            dao.stored,
        )
    }

    @Test
    fun `Given restored widgets without configuration when moving to restored widget IDs then other widgets are untouched`() = runTest {
        val dao = FakeWidgetDao(widget(id = 5, entityId = "todo.restored"), widget(id = 7, entityId = "todo.other"))

        val restoredWidgetIds = dao.moveToRestoredWidgetIds(oldWidgetIds = intArrayOf(5, 6), newWidgetIds = intArrayOf(20, 21))

        assertEquals(listOf(20), restoredWidgetIds)
        assertEquals(
            mapOf(20 to widget(id = 20, entityId = "todo.restored"), 7 to widget(id = 7, entityId = "todo.other")),
            dao.stored,
        )
    }

    @Test
    fun `Given old and new widget ID lists of different sizes when moving to restored widget IDs then nothing changes`() = runTest {
        val dao = FakeWidgetDao(widget(id = 5, entityId = "todo.restored"))

        val restoredWidgetIds = dao.moveToRestoredWidgetIds(oldWidgetIds = intArrayOf(5), newWidgetIds = intArrayOf(20, 21))

        assertEquals(emptyList<Int>(), restoredWidgetIds)
        assertEquals(mapOf(5 to widget(id = 5, entityId = "todo.restored")), dao.stored)
    }
}
