package com.codex.im.storage

interface MessageDao {
    fun insertOrIgnore(message: ChatMessage): Boolean

    fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage>

    fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean

    fun markFailed(messageId: String, updatedAt: Long): Boolean
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
            .sortedWith(MessageOrderingPolicy.newestFirst)
            .take(limit)
            .toList()
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
        val current = messagesById[messageId] ?: return false
        messagesById[messageId] = current.copy(
            status = MessageStatus.FAILED,
            updatedAt = updatedAt
        )
        return true
    }
}
