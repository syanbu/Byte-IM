package com.codex.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AndroidMessageDao(private val database: SQLiteDatabase) : MessageDao {
    override fun insertOrIgnore(message: ChatMessage): Boolean {
        return database.insertWithOnConflict(
            "messages",
            null,
            message.toValues(),
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    override fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
        val where = if (beforeTime == null) {
            "conversation_id = ?"
        } else {
            "conversation_id = ? AND created_at < ?"
        }
        val args = if (beforeTime == null) {
            arrayOf(conversationId)
        } else {
            arrayOf(conversationId, beforeTime.toString())
        }
        return database.query(
            "messages",
            null,
            where,
            args,
            null,
            null,
            """
            CASE
              WHEN server_seq IS NULL AND status = 'SENDING' THEN 0
              WHEN server_seq IS NOT NULL THEN 1
              ELSE 2
            END ASC,
            server_seq DESC,
            created_at DESC,
            client_seq DESC,
            message_id DESC
            """.trimIndent(),
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toChatMessage())
                }
            }
        }
    }

    override fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("server_seq", serverSeq)
            put("status", MessageStatus.SENT.name)
            put("updated_at", updatedAt)
        }
        return database.update("messages", values, "message_id = ?", arrayOf(messageId)) > 0
    }

    private fun ChatMessage.toValues(): ContentValues {
        return ContentValues().apply {
            put("message_id", messageId)
            put("conversation_id", conversationId)
            put("sender_id", senderId)
            put("receiver_id", receiverId)
            put("client_seq", clientSeq)
            if (serverSeq == null) putNull("server_seq") else put("server_seq", serverSeq)
            put("content", content)
            put("status", status.name)
            put("direction", direction.name)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    private fun Cursor.toChatMessage(): ChatMessage {
        val serverSeqIndex = getColumnIndexOrThrow("server_seq")
        return ChatMessage(
            messageId = getString(getColumnIndexOrThrow("message_id")),
            conversationId = getString(getColumnIndexOrThrow("conversation_id")),
            senderId = getString(getColumnIndexOrThrow("sender_id")),
            receiverId = getString(getColumnIndexOrThrow("receiver_id")),
            clientSeq = getLong(getColumnIndexOrThrow("client_seq")),
            serverSeq = if (isNull(serverSeqIndex)) null else getLong(serverSeqIndex),
            content = getString(getColumnIndexOrThrow("content")),
            status = MessageStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
            direction = MessageDirection.valueOf(getString(getColumnIndexOrThrow("direction"))),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
        )
    }
}
