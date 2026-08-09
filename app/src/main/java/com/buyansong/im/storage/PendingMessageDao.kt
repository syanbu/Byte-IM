package com.buyansong.im.storage

interface PendingMessageDao {
    fun upsert(pendingMessage: PendingMessage)

    fun delete(messageId: String): Boolean

    fun findByMessageId(messageId: String): PendingMessage?

    fun dueMessages(now: Long, limit: Int): List<PendingMessage>

    fun pendingMessageIds(limit: Int): List<String>

    fun earliestNextRetryAt(): Long?

    fun accelerateRetryAtExcluding(receivedMessageIds: Collection<String>, now: Long): Int
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

    override fun pendingMessageIds(limit: Int): List<String> {
        return pendingById.values
            .sortedBy { it.createdAt }
            .take(limit)
            .map { it.messageId }
    }

    override fun earliestNextRetryAt(): Long? {
        return pendingById.values.minOfOrNull { it.nextRetryAt }
    }

    override fun accelerateRetryAtExcluding(receivedMessageIds: Collection<String>, now: Long): Int {
        val received = receivedMessageIds.toSet()
        var changed = 0
        pendingById.values.toList().forEach { pending ->
            if (pending.messageId !in received && pending.nextRetryAt > now) {
                pendingById[pending.messageId] = pending.copy(nextRetryAt = now)
                changed += 1
            }
        }
        return changed
    }
}
