package com.codex.im.storage

interface ConversationDao {
    fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean)

    fun upsertConversation(conversation: Conversation)

    fun listConversations(limit: Int): List<Conversation>

    fun clearUnread(conversationId: String)

    fun totalUnreadCount(): Int

    fun updatePeerReadCursor(conversationId: String, readUpToServerSeq: Long, readAt: Long): Boolean

    fun updatePreviewForRecalledMessage(messageId: String, preview: String, updatedAt: Long): Boolean
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
                updatedAt = message.updatedAt,
                peerReadUpToServerSeq = current?.peerReadUpToServerSeq,
                peerReadAt = current?.peerReadAt
            )
        } else {
            current.copy(unreadCount = unreadCount, updatedAt = message.updatedAt)
        }
        conversations[message.conversationId] = next
    }

    override fun upsertConversation(conversation: Conversation) {
        conversations[conversation.conversationId] = conversation
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

    override fun updatePeerReadCursor(conversationId: String, readUpToServerSeq: Long, readAt: Long): Boolean {
        val current = conversations[conversationId] ?: return false
        val currentCursor = current.peerReadUpToServerSeq
        if (currentCursor != null && readUpToServerSeq <= currentCursor) {
            return false
        }
        conversations[conversationId] = current.copy(
            peerReadUpToServerSeq = readUpToServerSeq,
            peerReadAt = readAt,
            updatedAt = readAt
        )
        return true
    }

    override fun updatePreviewForRecalledMessage(messageId: String, preview: String, updatedAt: Long): Boolean {
        val entry = conversations.entries.firstOrNull { it.value.lastMessageId == messageId } ?: return false
        conversations[entry.key] = entry.value.copy(
            lastMessagePreview = preview,
            updatedAt = updatedAt
        )
        return true
    }
}
