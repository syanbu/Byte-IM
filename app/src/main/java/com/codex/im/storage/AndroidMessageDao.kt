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
              WHEN server_seq IS NULL AND status IN ('UPLOADING', 'UPLOAD_FAILED', 'SENDING', 'FAILED') THEN 0
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

    private fun ChatMessage.toValues(): ContentValues {
        return ContentValues().apply {
            put("message_id", messageId)
            put("conversation_id", conversationId)
            put("sender_id", senderId)
            put("receiver_id", receiverId)
            put("client_seq", clientSeq)
            if (serverSeq == null) putNull("server_seq") else put("server_seq", serverSeq)
            put("content", content)
            put("message_type", type.name)
            if (imageUrl == null) putNull("image_url") else put("image_url", imageUrl)
            if (thumbnailUrl == null) putNull("thumbnail_url") else put("thumbnail_url", thumbnailUrl)
            if (imageWidth == null) putNull("image_width") else put("image_width", imageWidth)
            if (imageHeight == null) putNull("image_height") else put("image_height", imageHeight)
            if (mimeType == null) putNull("mime_type") else put("mime_type", mimeType)
            if (fileSizeBytes == null) putNull("file_size_bytes") else put("file_size_bytes", fileSizeBytes)
            if (localOriginalPath == null) putNull("local_original_path") else put("local_original_path", localOriginalPath)
            if (localThumbnailPath == null) putNull("local_thumbnail_path") else put("local_thumbnail_path", localThumbnailPath)
            put("status", status.name)
            put("direction", direction.name)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        }
    }

    private fun Cursor.toChatMessage(): ChatMessage {
        val serverSeqIndex = getColumnIndexOrThrow("server_seq")
        val imageUrlIndex = getColumnIndexOrThrow("image_url")
        val thumbnailUrlIndex = getColumnIndexOrThrow("thumbnail_url")
        val imageWidthIndex = getColumnIndexOrThrow("image_width")
        val imageHeightIndex = getColumnIndexOrThrow("image_height")
        val mimeTypeIndex = getColumnIndexOrThrow("mime_type")
        val fileSizeBytesIndex = getColumnIndexOrThrow("file_size_bytes")
        val localOriginalPathIndex = getColumnIndexOrThrow("local_original_path")
        val localThumbnailPathIndex = getColumnIndexOrThrow("local_thumbnail_path")
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
            localThumbnailPath = if (isNull(localThumbnailPathIndex)) null else getString(localThumbnailPathIndex)
        )
    }
}
