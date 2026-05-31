package com.codex.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AndroidConversationDao(private val database: SQLiteDatabase) : ConversationDao {
    override fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean) {
        val current = find(message.conversationId)
        val peerId = if (message.direction == MessageDirection.INCOMING) message.senderId else message.receiverId
        val unreadCount = (current?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        val preview = if (message.type == MessageType.IMAGE) "[图片]" else message.content
        val next = if (current == null || message.createdAt >= current.lastMessageTime) {
            Conversation(
                conversationId = message.conversationId,
                peerId = current?.peerId ?: peerId,
                peerName = current?.peerName ?: peerId,
                lastMessageId = message.messageId,
                lastMessagePreview = preview,
                lastMessageTime = message.createdAt,
                unreadCount = unreadCount,
                updatedAt = message.updatedAt,
                peerReadUpToServerSeq = current?.peerReadUpToServerSeq,
                peerReadAt = current?.peerReadAt
            )
        } else {
            current.copy(unreadCount = unreadCount, updatedAt = message.updatedAt)
        }
        database.insertWithOnConflict("conversations", null, next.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun upsertConversation(conversation: Conversation) {
        database.insertWithOnConflict("conversations", null, conversation.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listConversations(limit: Int): List<Conversation> {
        return database.query(
            "conversations",
            null,
            null,
            null,
            null,
            null,
            "last_message_time DESC, conversation_id ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toConversation())
                }
            }
        }
    }

    override fun clearUnread(conversationId: String) {
        val values = ContentValues().apply { put("unread_count", 0) }
        database.update("conversations", values, "conversation_id = ?", arrayOf(conversationId))
    }

    override fun totalUnreadCount(): Int {
        return database.rawQuery(
            "SELECT COALESCE(SUM(unread_count), 0) AS total_unread_count FROM conversations",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow("total_unread_count")) else 0
        }
    }

    override fun updatePeerReadCursor(conversationId: String, readUpToServerSeq: Long, readAt: Long): Boolean {
        val values = ContentValues().apply {
            put("peer_read_up_to_server_seq", readUpToServerSeq)
            put("peer_read_at", readAt)
            put("updated_at", readAt)
        }
        return database.update(
            "conversations",
            values,
            """
            conversation_id = ?
              AND (peer_read_up_to_server_seq IS NULL OR peer_read_up_to_server_seq < ?)
            """.trimIndent(),
            arrayOf(conversationId, readUpToServerSeq.toString())
        ) > 0
    }

    override fun updatePreviewForRecalledMessage(messageId: String, preview: String, updatedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("last_message_preview", preview)
            put("updated_at", updatedAt)
        }
        return database.update(
            "conversations",
            values,
            "last_message_id = ?",
            arrayOf(messageId)
        ) > 0
    }

    private fun find(conversationId: String): Conversation? {
        return database.query(
            "conversations",
            null,
            "conversation_id = ?",
            arrayOf(conversationId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toConversation() else null
        }
    }

    private fun Conversation.toValues(): ContentValues {
        return ContentValues().apply {
            put("conversation_id", conversationId)
            put("peer_id", peerId)
            put("peer_name", peerName)
            if (lastMessageId == null) putNull("last_message_id") else put("last_message_id", lastMessageId)
            put("last_message_preview", lastMessagePreview)
            put("last_message_time", lastMessageTime)
            put("unread_count", unreadCount)
            put("updated_at", updatedAt)
            if (peerReadUpToServerSeq == null) putNull("peer_read_up_to_server_seq") else put("peer_read_up_to_server_seq", peerReadUpToServerSeq)
            if (peerReadAt == null) putNull("peer_read_at") else put("peer_read_at", peerReadAt)
        }
    }

    private fun Cursor.toConversation(): Conversation {
        val lastMessageIdIndex = getColumnIndexOrThrow("last_message_id")
        val peerReadUpToServerSeqIndex = getColumnIndexOrThrow("peer_read_up_to_server_seq")
        val peerReadAtIndex = getColumnIndexOrThrow("peer_read_at")
        return Conversation(
            conversationId = getString(getColumnIndexOrThrow("conversation_id")),
            peerId = getString(getColumnIndexOrThrow("peer_id")),
            peerName = getString(getColumnIndexOrThrow("peer_name")),
            lastMessageId = if (isNull(lastMessageIdIndex)) null else getString(lastMessageIdIndex),
            lastMessagePreview = getString(getColumnIndexOrThrow("last_message_preview")),
            lastMessageTime = getLong(getColumnIndexOrThrow("last_message_time")),
            unreadCount = getInt(getColumnIndexOrThrow("unread_count")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            peerReadUpToServerSeq = if (isNull(peerReadUpToServerSeqIndex)) null else getLong(peerReadUpToServerSeqIndex),
            peerReadAt = if (isNull(peerReadAtIndex)) null else getLong(peerReadAtIndex)
        )
    }
}
