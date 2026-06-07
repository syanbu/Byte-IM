package com.buyansong.im.storage

interface MessageDao {
    fun insertOrIgnore(message: ChatMessage): Boolean

    fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage>

    fun queryIncomingImagesMissingLocalThumbnail(conversationId: String, limit: Int): List<ChatMessage>

    fun findByMessageId(messageId: String): ChatMessage?

    fun deleteByConversationId(conversationId: String): Int

    fun updateImageUploadResult(
        messageId: String,
        imageUrl: String,
        thumbnailUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        mimeType: String,
        fileSizeBytes: Long,
        status: MessageStatus,
        updatedAt: Long
    ): Boolean

    fun updateLocalThumbnailPath(messageId: String, localThumbnailPath: String, updatedAt: Long): Boolean

    fun markStatus(messageId: String, status: MessageStatus, updatedAt: Long): Boolean

    fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean

    fun markFailed(messageId: String, updatedAt: Long): Boolean

    fun markRecalled(messageId: String, recalledBy: String, recalledAt: Long): Boolean

    fun maxIncomingServerSeq(conversationId: String): Long?
}

class InMemoryMessageDao : MessageDao {
    private val messagesById = linkedMapOf<String, ChatMessage>()

    override fun insertOrIgnore(message: ChatMessage): Boolean {
        if (messagesById.containsKey(message.messageId)) {
            return false
        }
        messagesById[message.messageId] = message
        return true
    }

    override fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
        return messagesById.values
            .asSequence()
            .filter { it.conversationId == conversationId }
            .filter { beforeTime == null || it.createdAt < beforeTime }
            .let { MessageOrderingPolicy.sortNewestFirst(it.asIterable()) }
            .take(limit)
            .toList()
    }

    override fun queryIncomingImagesMissingLocalThumbnail(conversationId: String, limit: Int): List<ChatMessage> {
        return messagesById.values
            .asSequence()
            .filter { it.conversationId == conversationId }
            .filter { it.direction == MessageDirection.INCOMING }
            .filter { it.type == MessageType.IMAGE }
            .filter { it.localThumbnailPath == null }
            .let { MessageOrderingPolicy.sortNewestFirst(it.asIterable()) }
            .take(limit)
            .toList()
    }

    override fun findByMessageId(messageId: String): ChatMessage? = messagesById[messageId]

    override fun deleteByConversationId(conversationId: String): Int {
        val targetIds = messagesById.values
            .filter { it.conversationId == conversationId }
            .map { it.messageId }
        targetIds.forEach(messagesById::remove)
        return targetIds.size
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
        val current = messagesById[messageId] ?: return false
        messagesById[messageId] = current.copy(
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes,
            status = status,
            updatedAt = updatedAt
        )
        return true
    }

    override fun updateLocalThumbnailPath(messageId: String, localThumbnailPath: String, updatedAt: Long): Boolean {
        val current = messagesById[messageId] ?: return false
        messagesById[messageId] = current.copy(
            localThumbnailPath = localThumbnailPath,
            updatedAt = updatedAt
        )
        return true
    }

    override fun markStatus(messageId: String, status: MessageStatus, updatedAt: Long): Boolean {
        val current = messagesById[messageId] ?: return false
        messagesById[messageId] = current.copy(
            status = status,
            updatedAt = updatedAt
        )
        return true
    }

    override fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean {
        val current = messagesById[messageId] ?: return false
        messagesById[messageId] = current.copy(
            serverSeq = serverSeq,
            status = MessageStatus.SENT,
            updatedAt = updatedAt
        )
        return true
    }

    override fun markFailed(messageId: String, updatedAt: Long): Boolean {
        return markStatus(messageId, MessageStatus.FAILED, updatedAt)
    }

    override fun markRecalled(messageId: String, recalledBy: String, recalledAt: Long): Boolean {
        val current = messagesById[messageId] ?: return false
        if (current.isRecalled) {
            return false
        }
        messagesById[messageId] = current.copy(
            isRecalled = true,
            recalledAt = recalledAt,
            recalledBy = recalledBy,
            updatedAt = recalledAt
        )
        return true
    }

    override fun maxIncomingServerSeq(conversationId: String): Long? {
        return messagesById.values
            .asSequence()
            .filter { it.conversationId == conversationId }
            .filter { it.direction == MessageDirection.INCOMING }
            .mapNotNull { it.serverSeq }
            .maxOrNull()
    }
}
