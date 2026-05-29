package com.codex.im.storage

interface PendingMessageDao {
    fun upsert(pendingMessage: PendingMessage)

    fun delete(messageId: String): Boolean

    fun findByMessageId(messageId: String): PendingMessage?

    fun dueMessages(now: Long, limit: Int): List<PendingMessage>
}

class InMemoryPendingMessageDao : PendingMessageDao {
    private val pendingById = linkedMapOf<String, PendingMessage>()

    override fun upsert(pendingMessage: PendingMessage) {
        pendingById[pendingMessage.messageId] = pendingMessage
    }

    override fun delete(messageId: String): Boolean {
        return pendingById.remove(messageId) != null
    }

    override fun findByMessageId(messageId: String): PendingMessage? = pendingById[messageId]

    override fun dueMessages(now: Long, limit: Int): List<PendingMessage> {
        return pendingById.values
            .filter { it.nextRetryAt <= now }
            .sortedBy { it.nextRetryAt }
            .take(limit)
    }
}
