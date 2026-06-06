package com.codex.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AndroidConversationDao(private val database: SQLiteDatabase) : ConversationDao {
    override fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean, incrementMentionUnread: Boolean) {
        val current = find(message.conversationId)
        val isGroup = message.conversationType == ConversationType.GROUP
        val peerId = if (isGroup) {
            message.conversationId
        } else if (message.direction == MessageDirection.INCOMING) {
            message.senderId
        } else {
            message.receiverId
        }
        val displayName = if (isGroup) {
            message.groupName?.takeIf { it.isNotBlank() } ?: message.conversationId
        } else {
            peerId
        }
        val unreadCount = (current?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        val mentionUnreadCount = (current?.mentionUnreadCount ?: 0) + if (incrementMentionUnread) 1 else 0
        val preview = if (message.type == MessageType.IMAGE) "[图片]" else message.content
        val next = if (current == null || message.createdAt >= current.lastMessageTime) {
            Conversation(
                conversationId = message.conversationId,
                peerId = current?.peerId ?: peerId,
                peerName = current?.peerName ?: displayName,
                type = current?.type ?: message.conversationType,
                title = current?.title ?: displayName,
                avatarUrl = current?.avatarUrl,
                lastMessageId = message.messageId,
                lastMessagePreview = preview,
                lastMessageTime = message.createdAt,
                unreadCount = unreadCount,
                mentionUnreadCount = mentionUnreadCount,
                updatedAt = message.updatedAt,
                peerReadUpToServerSeq = current?.peerReadUpToServerSeq,
                peerReadAt = current?.peerReadAt
            )
        } else {
            current.copy(
                unreadCount = unreadCount,
                mentionUnreadCount = mentionUnreadCount,
                updatedAt = message.updatedAt
            )
        }
        database.insertWithOnConflict("conversations", null, next.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun upsertConversation(conversation: Conversation) {
        database.insertWithOnConflict("conversations", null, conversation.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listConversations(limit: Int): List<Conversation> {
        return listConversationsPage(
            beforeLastMessageTime = null,
            beforeConversationId = null,
            limit = limit
        )
    }

    override fun listConversationsPage(
        beforeLastMessageTime: Long?,
        beforeConversationId: String?,
        limit: Int
    ): List<Conversation> {
        if (beforeLastMessageTime == null) {
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
        return database.rawQuery(
            """
            SELECT *
            FROM conversations
            WHERE (last_message_time < ?)
               OR (last_message_time = ? AND conversation_id > ?)
            ORDER BY last_message_time DESC, conversation_id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                beforeLastMessageTime.toString(),
                beforeLastMessageTime.toString(),
                beforeConversationId.orEmpty(),
                limit.toString()
            )
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toConversation())
                }
            }
        }
    }

    override fun findConversation(conversationId: String): Conversation? = find(conversationId)

    override fun clearUnread(conversationId: String) {
        val values = ContentValues().apply {
            put("unread_count", 0)
            put("mention_unread_count", 0)
        }
        database.update("conversations", values, "conversation_id = ?", arrayOf(conversationId))
    }

    override fun deleteConversation(conversationId: String): Boolean {
        return database.delete("conversations", "conversation_id = ?", arrayOf(conversationId)) > 0
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
            put("conversation_type", type.name)
            put("title", title)
            if (avatarUrl == null) putNull("avatar_url") else put("avatar_url", avatarUrl)
            if (lastMessageId == null) putNull("last_message_id") else put("last_message_id", lastMessageId)
            put("last_message_preview", lastMessagePreview)
            put("last_message_time", lastMessageTime)
            put("unread_count", unreadCount)
            put("mention_unread_count", mentionUnreadCount)
            put("updated_at", updatedAt)
            if (peerReadUpToServerSeq == null) putNull("peer_read_up_to_server_seq") else put("peer_read_up_to_server_seq", peerReadUpToServerSeq)
            if (peerReadAt == null) putNull("peer_read_at") else put("peer_read_at", peerReadAt)
        }
    }

    private fun Cursor.toConversation(): Conversation {
        val lastMessageIdIndex = getColumnIndexOrThrow("last_message_id")
        val peerReadUpToServerSeqIndex = getColumnIndexOrThrow("peer_read_up_to_server_seq")
        val peerReadAtIndex = getColumnIndexOrThrow("peer_read_at")
        val avatarUrlIndex = getColumnIndexOrThrow("avatar_url")
        return Conversation(
            conversationId = getString(getColumnIndexOrThrow("conversation_id")),
            peerId = getString(getColumnIndexOrThrow("peer_id")),
            peerName = getString(getColumnIndexOrThrow("peer_name")),
            type = ConversationType.valueOf(getString(getColumnIndexOrThrow("conversation_type"))),
            title = getString(getColumnIndexOrThrow("title")) ?: getString(getColumnIndexOrThrow("peer_name")),
            avatarUrl = if (isNull(avatarUrlIndex)) null else getString(avatarUrlIndex),
            lastMessageId = if (isNull(lastMessageIdIndex)) null else getString(lastMessageIdIndex),
            lastMessagePreview = getString(getColumnIndexOrThrow("last_message_preview")),
            lastMessageTime = getLong(getColumnIndexOrThrow("last_message_time")),
            unreadCount = getInt(getColumnIndexOrThrow("unread_count")),
            mentionUnreadCount = getInt(getColumnIndexOrThrow("mention_unread_count")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            peerReadUpToServerSeq = if (isNull(peerReadUpToServerSeqIndex)) null else getLong(peerReadUpToServerSeqIndex),
            peerReadAt = if (isNull(peerReadAtIndex)) null else getLong(peerReadAtIndex)
        )
    }
}
