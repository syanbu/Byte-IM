package com.buyansong.im.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

class InMemoryGroupReadCursorDao : GroupReadCursorDao {
    private data class Key(val groupId: String, val readerId: String)

    private val rows = linkedMapOf<Key, GroupReadCursor>()
    private val groupFlows = mutableMapOf<String, MutableSharedFlow<List<GroupReadCursor>>>()

    @Synchronized
    override fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long): Boolean {
        val key = Key(groupId, readerId)
        val existing = rows[key]
        if (existing != null && existing.readUpToServerSeq >= readUpToServerSeq) {
            return false
        }
        rows[key] = GroupReadCursor(groupId, readerId, readUpToServerSeq, readAt)
        emit(groupId)
        return true
    }

    @Synchronized
    override fun findByGroup(groupId: String): List<GroupReadCursor> {
        return rows.entries
            .filter { it.key.groupId == groupId }
            .map { it.value }
    }

    override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> {
        val flow = groupFlows.getOrPut(groupId) { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }
        return flow.asSharedFlow()
            .onStart { emit(snapshotFor(groupId)) }
            .distinctUntilChanged()
    }

    private fun snapshotFor(groupId: String): List<GroupReadCursor> = findByGroup(groupId)

    private fun emit(groupId: String) {
        val flow = groupFlows[groupId] ?: return
        flow.tryEmit(snapshotFor(groupId))
    }
}
