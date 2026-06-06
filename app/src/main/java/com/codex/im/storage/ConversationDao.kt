package com.codex.im.storage

interface ConversationDao {
    fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean, incrementMentionUnread: Boolean = false)

    fun upsertConversation(conversation: Conversation)

    fun listConversations(limit: Int): List<Conversation>

    fun listConversationsPage(
        beforeLastMessageTime: Long?,
        beforeConversationId: String?,
        limit: Int
    ): List<Conversation>

    fun findConversation(conversationId: String): Conversation?

    fun clearUnread(conversationId: String)

    fun deleteConversation(conversationId: String): Boolean

    fun totalUnreadCount(): Int

    fun updatePeerReadCursor(conversationId: String, readUpToServerSeq: Long, readAt: Long): Boolean

    fun updatePreviewForRecalledMessage(messageId: String, preview: String, updatedAt: Long): Boolean
}

class InMemoryConversationDao : ConversationDao {
    private val conversations = linkedMapOf<String, Conversation>()

    override fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean, incrementMentionUnread: Boolean) {
        val current = conversations[message.conversationId]
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
        val shouldReplacePreview = current == null || message.createdAt >= current.lastMessageTime
        val unreadCount = (current?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        val mentionUnreadCount = (current?.mentionUnreadCount ?: 0) + if (incrementMentionUnread) 1 else 0
        val preview = if (message.type == MessageType.IMAGE) "[图片]" else message.content
        val next = if (shouldReplacePreview) {
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
        conversations[message.conversationId] = next
    }

    override fun upsertConversation(conversation: Conversation) {
        conversations[conversation.conversationId] = conversation
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
        return conversations.values
            .sortedWith(compareByDescending<Conversation> { it.lastMessageTime }.thenBy { it.conversationId })
            .filter { conversation ->
                beforeLastMessageTime == null ||
                    conversation.lastMessageTime < beforeLastMessageTime ||
                    (conversation.lastMessageTime == beforeLastMessageTime &&
                        conversation.conversationId > (beforeConversationId ?: ""))
            }
            .take(limit)
    }

    override fun findConversation(conversationId: String): Conversation? = conversations[conversationId]

    override fun clearUnread(conversationId: String) {
        val current = conversations[conversationId] ?: return
        conversations[conversationId] = current.copy(unreadCount = 0, mentionUnreadCount = 0)
    }

    override fun deleteConversation(conversationId: String): Boolean {
        return conversations.remove(conversationId) != null
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
