package com.buyansong.im.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGroupReadCursorDaoContractTest {
    private fun newDao(): GroupReadCursorDao = InMemoryGroupReadCursorDao()

    @Test
    fun upsertIfGreater_persistsAndIgnoresStale() {
        val dao = newDao()
        assertTrue(dao.upsertIfGreater("g_1", "u_a", 100L, 1_000L))
        assertFalse(dao.upsertIfGreater("g_1", "u_a", 100L, 1_500L))
        assertFalse(dao.upsertIfGreater("g_1", "u_a", 50L, 2_000L))
        assertTrue(dao.upsertIfGreater("g_1", "u_a", 101L, 3_000L))
    }

    @Test
    fun findByGroup_returnsAllRowsForThatGroup() {
        val dao = newDao()
        dao.upsertIfGreater("g_1", "u_a", 1L, 1L)
        dao.upsertIfGreater("g_1", "u_b", 2L, 2L)
        dao.upsertIfGreater("g_2", "u_a", 3L, 3L)
        val rows = dao.findByGroup("g_1")
        assertEquals(2, rows.size)
        assertEquals(setOf("u_a", "u_b"), rows.map { it.readerId }.toSet())
    }

    @Test
    fun observeByGroup_emitsInitialThenChanges() = runBlocking {
        val dao = newDao()
        val emitted = mutableListOf<List<GroupReadCursor>>()
        val job: Job = GlobalScope.launch(Dispatchers.Unconfined) {
            dao.observeByGroup("g_1").collect { emitted += it }
        }
        dao.upsertIfGreater("g_1", "u_a", 1L, 1L)
        dao.upsertIfGreater("g_1", "u_b", 2L, 2L)
        job.cancel()
        assertEquals(3, emitted.size)
        assertEquals(0, emitted[0].size)
        assertEquals(1, emitted[1].size)
        assertEquals(2, emitted[2].size)
    }
}
