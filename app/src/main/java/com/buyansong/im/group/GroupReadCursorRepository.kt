package com.buyansong.im.group

import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.GroupReadCursorDao
import kotlinx.coroutines.flow.Flow

interface GroupReadCursorRepository {
    suspend fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long)

    fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>>
}

class DefaultGroupReadCursorRepository(
    private val dao: GroupReadCursorDao
) : GroupReadCursorRepository {
    override suspend fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long) {
        dao.upsertIfGreater(groupId, readerId, readUpToServerSeq, readAt)
    }

    override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> = dao.observeByGroup(groupId)
}
