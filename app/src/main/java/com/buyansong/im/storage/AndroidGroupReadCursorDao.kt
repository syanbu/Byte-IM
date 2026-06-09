package com.buyansong.im.storage

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

class AndroidGroupReadCursorDao(
    private val database: SQLiteDatabase
) : GroupReadCursorDao {
    private val groupFlows = mutableMapOf<String, MutableSharedFlow<List<GroupReadCursor>>>()

    @Synchronized
    override fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long): Boolean {
        val existing = readUpToServerSeqOf(groupId, readerId)
        if (existing != null && existing >= readUpToServerSeq) {
            return false
        }
        database.execSQL(
            """
            INSERT INTO group_read_cursors(group_id, reader_id, read_up_to_server_seq, read_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(group_id, reader_id) DO UPDATE SET
              read_up_to_server_seq = excluded.read_up_to_server_seq,
              read_at = excluded.read_at
            """.trimIndent(),
            arrayOf(groupId, readerId, readUpToServerSeq, readAt)
        )
        emit(groupId)
        return true
    }

    @Synchronized
    override fun findByGroup(groupId: String): List<GroupReadCursor> {
        return database.rawQuery(
            "SELECT group_id, reader_id, read_up_to_server_seq, read_at FROM group_read_cursors WHERE group_id = ?",
            arrayOf(groupId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GroupReadCursor(
                            groupId = cursor.getString(0),
                            readerId = cursor.getString(1),
                            readUpToServerSeq = cursor.getLong(2),
                            readAt = cursor.getLong(3)
                        )
                    )
                }
            }
        }
    }

    override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> {
        val flow = synchronized(this) {
            groupFlows.getOrPut(groupId) { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }
        }
        return flow.asSharedFlow()
            .onStart { emit(findByGroup(groupId)) }
            .distinctUntilChanged()
    }

    private fun readUpToServerSeqOf(groupId: String, readerId: String): Long? {
        return database.rawQuery(
            "SELECT read_up_to_server_seq FROM group_read_cursors WHERE group_id = ? AND reader_id = ?",
            arrayOf(groupId, readerId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun emit(groupId: String) {
        val flow = synchronized(this) { groupFlows[groupId] } ?: return
        flow.tryEmit(findByGroup(groupId))
    }
}
