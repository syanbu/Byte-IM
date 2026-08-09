package com.buyansong.im.storage

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

    override fun pendingMessageIds(limit: Int): List<String> {
        return database.query(
            "pending_messages",
            arrayOf("message_id"),
            null,
            null,
            null,
            null,
            "created_at ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
    }

    override fun earliestNextRetryAt(): Long? {
        return database.rawQuery("SELECT MIN(next_retry_at) FROM pending_messages", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }

    override fun accelerateRetryAtExcluding(receivedMessageIds: Collection<String>, now: Long): Int {
        val sql = StringBuilder("UPDATE pending_messages SET next_retry_at = ? WHERE next_retry_at > ?")
        if (receivedMessageIds.isNotEmpty()) {
            sql.append(" AND message_id NOT IN (")
            sql.append(receivedMessageIds.joinToString(",") { "?" })
            sql.append(")")
        }
        return database.compileStatement(sql.toString()).use { statement ->
            statement.bindLong(1, now)
            statement.bindLong(2, now)
            receivedMessageIds.forEachIndexed { index, messageId ->
                statement.bindString(index + 3, messageId)
            }
            statement.executeUpdateDelete()
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
