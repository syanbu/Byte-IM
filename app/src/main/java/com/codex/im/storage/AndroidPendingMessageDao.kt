package com.codex.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AndroidPendingMessageDao(private val database: SQLiteDatabase) : PendingMessageDao {
    override fun upsert(pendingMessage: PendingMessage) {
        database.insertWithOnConflict(
            "pending_messages",
            null,
            pendingMessage.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override fun delete(messageId: String): Boolean {
        return database.delete("pending_messages", "message_id = ?", arrayOf(messageId)) > 0
    }

    override fun findByMessageId(messageId: String): PendingMessage? {
        return database.query(
            "pending_messages",
            null,
            "message_id = ?",
            arrayOf(messageId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPendingMessage() else null
        }
    }

    override fun dueMessages(now: Long, limit: Int): List<PendingMessage> {
        return database.query(
            "pending_messages",
            null,
            "next_retry_at <= ?",
            arrayOf(now.toString()),
            null,
            null,
            "next_retry_at ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toPendingMessage())
                }
            }
        }
    }

    private fun PendingMessage.toValues(): ContentValues {
        return ContentValues().apply {
            put("message_id", messageId)
            put("packet_cmd", packetCmd)
            put("packet_body", packetBody)
            put("retry_count", retryCount)
            put("next_retry_at", nextRetryAt)
            put("created_at", createdAt)
        }
    }

    private fun Cursor.toPendingMessage(): PendingMessage {
        return PendingMessage(
            messageId = getString(getColumnIndexOrThrow("message_id")),
            packetCmd = getInt(getColumnIndexOrThrow("packet_cmd")),
            packetBody = getString(getColumnIndexOrThrow("packet_body")),
            retryCount = getInt(getColumnIndexOrThrow("retry_count")),
            nextRetryAt = getLong(getColumnIndexOrThrow("next_retry_at")),
            createdAt = getLong(getColumnIndexOrThrow("created_at"))
        )
    }
}
