package com.buyansong.im.storage

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
            "created_at DESC, server_seq DESC, client_seq DESC, message_id DESC",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toChatMessage())
                }
            }
                .let { MessageOrderingPolicy.sortNewestFirst(it) }
                .take(limit)
        }
    }

    override fun queryIncomingImagesMissingLocalThumbnail(conversationId: String, limit: Int): List<ChatMessage> {
        return database.query(
            "messages",
            null,
            """
            conversation_id = ?
              AND direction = ?
              AND message_type = ?
              AND local_thumbnail_path IS NULL
            """.trimIndent(),
            arrayOf(conversationId, MessageDirection.INCOMING.name, MessageType.IMAGE.name),
            null,
            null,
            "created_at DESC, server_seq DESC, message_id DESC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toChatMessage())
                }
            }
        }
    }

    override fun findByMessageId(messageId: String): ChatMessage? {
        return database.query(
            "messages",
            null,
            "message_id = ?",
            arrayOf(messageId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toChatMessage() else null
        }
    }

    override fun deleteByConversationId(conversationId: String): Int {
        return database.delete("messages", "conversation_id = ?", arrayOf(conversationId))
    }

    override fun updateImageUploadResult(
        messageId: String,
        imageUrl: String,
        thumbnailUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        mimeType: String,
        fileSizeBytes: Long,
        status: MessageStatus,
        updatedAt: Long
    ): Boolean {
        val values = ContentValues().apply {
            put("image_url", imageUrl)
            put("thumbnail_url", thumbnailUrl)
            put("image_width", imageWidth)
            put("image_height", imageHeight)
            put("mime_type", mimeType)
            put("file_size_bytes", fileSizeBytes)
            put("status", status.name)
            put("updated_at", updatedAt)
        }
        return database.update("messages", values, "message_id = ?", arrayOf(messageId)) > 0
    }

    override fun updateLocalThumbnailPath(messageId: String, localThumbnailPath: String, updatedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("local_thumbnail_path", localThumbnailPath)
            put("updated_at", updatedAt)
        }
        return database.update("messages", values, "message_id = ?", arrayOf(messageId)) > 0
    }

    override fun markStatus(messageId: String, status: MessageStatus, updatedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("status", status.name)
            put("updated_at", updatedAt)
        }
        return database.update("messages", values, "message_id = ?", arrayOf(messageId)) > 0
    }

    override fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("server_seq", serverSeq)
            put("status", MessageStatus.SENT.name)
            put("updated_at", updatedAt)
        }
        return database.update("messages", values, "message_id = ?", arrayOf(messageId)) > 0
    }

    override fun markFailed(messageId: String, updatedAt: Long): Boolean {
        return markStatus(messageId, MessageStatus.FAILED, updatedAt)
    }

    override fun markRecalled(messageId: String, recalledBy: String, recalledAt: Long): Boolean {
        val values = ContentValues().apply {
            put("is_recalled", 1)
            put("recalled_at", recalledAt)
            put("recalled_by", recalledBy)
            put("updated_at", recalledAt)
        }
        return database.update(
            "messages",
            values,
            "message_id = ? AND is_recalled = 0",
            arrayOf(messageId)
        ) > 0
    }

    override fun maxIncomingServerSeq(conversationId: String): Long? {
        return database.rawQuery(
            """
            SELECT MAX(server_seq) AS max_server_seq
            FROM messages
            WHERE conversation_id = ?
              AND direction = ?
              AND server_seq IS NOT NULL
            """.trimIndent(),
            arrayOf(conversationId, MessageDirection.INCOMING.name)
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(cursor.getColumnIndexOrThrow("max_server_seq"))) {
                null
            } else {
                cursor.getLong(cursor.getColumnIndexOrThrow("max_server_seq"))
            }
        }
    }

    private fun ChatMessage.toValues(): ContentValues {
        return ContentValues().apply {
            put("message_id", messageId)
            put("conversation_id", conversationId)
            put("conversation_type", conversationType.name)
            if (groupId == null) putNull("group_id") else put("group_id", groupId)
            put("sender_id", senderId)
            put("receiver_id", receiverId)
            put("client_seq", clientSeq)
            if (serverSeq == null) putNull("server_seq") else put("server_seq", serverSeq)
            put("content", content)
            put("mentions_json", mentionedUserIds.toJsonArrayString())
            put("message_type", type.name)
            if (imageUrl == null) putNull("image_url") else put("image_url", imageUrl)
            if (thumbnailUrl == null) putNull("thumbnail_url") else put("thumbnail_url", thumbnailUrl)
            if (imageWidth == null) putNull("image_width") else put("image_width", imageWidth)
            if (imageHeight == null) putNull("image_height") else put("image_height", imageHeight)
            if (mimeType == null) putNull("mime_type") else put("mime_type", mimeType)
            if (fileSizeBytes == null) putNull("file_size_bytes") else put("file_size_bytes", fileSizeBytes)
            if (localOriginalPath == null) putNull("local_original_path") else put("local_original_path", localOriginalPath)
            if (localThumbnailPath == null) putNull("local_thumbnail_path") else put("local_thumbnail_path", localThumbnailPath)
            put("is_recalled", if (isRecalled) 1 else 0)
            if (recalledAt == null) putNull("recalled_at") else put("recalled_at", recalledAt)
            if (recalledBy == null) putNull("recalled_by") else put("recalled_by", recalledBy)
            if (senderProfileVersion == null) putNull("sender_profile_version") else put("sender_profile_version", senderProfileVersion)
            put("status", status.name)
            put("direction", direction.name)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    private fun Cursor.toChatMessage(): ChatMessage {
        val serverSeqIndex = getColumnIndexOrThrow("server_seq")
        val groupIdIndex = getColumnIndexOrThrow("group_id")
        val imageUrlIndex = getColumnIndexOrThrow("image_url")
        val thumbnailUrlIndex = getColumnIndexOrThrow("thumbnail_url")
        val imageWidthIndex = getColumnIndexOrThrow("image_width")
        val imageHeightIndex = getColumnIndexOrThrow("image_height")
        val mimeTypeIndex = getColumnIndexOrThrow("mime_type")
        val fileSizeBytesIndex = getColumnIndexOrThrow("file_size_bytes")
        val localOriginalPathIndex = getColumnIndexOrThrow("local_original_path")
        val localThumbnailPathIndex = getColumnIndexOrThrow("local_thumbnail_path")
        val recalledAtIndex = getColumnIndexOrThrow("recalled_at")
        val recalledByIndex = getColumnIndexOrThrow("recalled_by")
        val senderProfileVersionIndex = getColumnIndexOrThrow("sender_profile_version")
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
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            type = MessageType.valueOf(getString(getColumnIndexOrThrow("message_type"))),
            imageUrl = if (isNull(imageUrlIndex)) null else getString(imageUrlIndex),
            thumbnailUrl = if (isNull(thumbnailUrlIndex)) null else getString(thumbnailUrlIndex),
            imageWidth = if (isNull(imageWidthIndex)) null else getInt(imageWidthIndex),
            imageHeight = if (isNull(imageHeightIndex)) null else getInt(imageHeightIndex),
            mimeType = if (isNull(mimeTypeIndex)) null else getString(mimeTypeIndex),
            fileSizeBytes = if (isNull(fileSizeBytesIndex)) null else getLong(fileSizeBytesIndex),
            localOriginalPath = if (isNull(localOriginalPathIndex)) null else getString(localOriginalPathIndex),
            localThumbnailPath = if (isNull(localThumbnailPathIndex)) null else getString(localThumbnailPathIndex),
            isRecalled = getInt(getColumnIndexOrThrow("is_recalled")) == 1,
            recalledAt = if (isNull(recalledAtIndex)) null else getLong(recalledAtIndex),
            recalledBy = if (isNull(recalledByIndex)) null else getString(recalledByIndex),
            conversationType = ConversationType.valueOf(getString(getColumnIndexOrThrow("conversation_type"))),
            groupId = if (isNull(groupIdIndex)) null else getString(groupIdIndex),
            mentionedUserIds = getString(getColumnIndexOrThrow("mentions_json")).fromJsonArrayString(),
            senderProfileVersion = if (isNull(senderProfileVersionIndex)) null else getLong(senderProfileVersionIndex)
        )
    }

    private fun List<String>.toJsonArrayString(): String {
        return joinToString(prefix = "[", postfix = "]") { value ->
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private fun String?.fromJsonArrayString(): List<String> {
        if (isNullOrBlank() || this == "[]") {
            return emptyList()
        }
        return trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
            .filter { it.isNotEmpty() }
    }
}
