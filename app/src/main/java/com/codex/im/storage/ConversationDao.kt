package com.codex.im.storage

interface ConversationDao {
    fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean)

    fun listConversations(limit: Int): List<Conversation>

    fun clearUnread(conversationId: String)

    fun totalUnreadCount(): Int
}

class InMemoryConversationDao : ConversationDao {
    private val conversations = linkedMapOf<String, Conversation>()

    override fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean) {
        val current = conversations[message.conversationId]
        val peerId = if (message.direction == MessageDirection.INCOMING) message.senderId else message.receiverId
        val shouldReplacePreview = current == null || message.createdAt >= current.lastMessageTime
        val unreadCount = (current?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        val preview = if (message.type == MessageType.IMAGE) "[图片]" else message.content
        val next = if (shouldReplacePreview) {
            Conversation(
                conversationId = message.conversationId,
                peerId = current?.peerId ?: peerId,
                peerName = current?.peerName ?: peerId,
                lastMessageId = message.messageId,
                lastMessagePreview = preview,
                lastMessageTime = message.createdAt,
                unreadCount = unreadCount,
                updatedAt = message.updatedAt
            )
        } else {
            current.copy(unreadCount = unreadCount, updatedAt = message.updatedAt)
        }
        conversations[message.conversationId] = next
    }

    override fun listConversations(limit: Int): List<Conversation> {
        return conversations.values
            .sortedWith(compareByDescending<Conversation> { it.lastMessageTime }.thenBy { it.conversationId })
            .take(limit)
    }

    override fun clearUnread(conversationId: String) {
        val current = conversations[conversationId] ?: return
        conversations[conversationId] = current.copy(unreadCount = 0)
    }

    override fun totalUnreadCount(): Int = conversations.values.sumOf { it.unreadCount }
}
