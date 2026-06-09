package com.buyansong.im.storage

import kotlinx.coroutines.flow.Flow

interface GroupReadCursorDao {
    fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long): Boolean

    fun findByGroup(groupId: String): List<GroupReadCursor>

    fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>>
}
