package com.codex.im.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDaoContractTest {
    @Test
    fun upsertFromMessageOrdersByLatestMessageTime() {
        val dao = InMemoryConversationDao()

        dao.upsertFromMessage(message("c1", "u2", "old", createdAt = 100), incrementUnread = true)
        dao.upsertFromMessage(message("c2", "u3", "new", createdAt = 300), incrementUnread = true)
        dao.upsertFromMessage(message("c1", "u2", "latest", createdAt = 500), incrementUnread = false)

        val conversations = dao.listConversations(limit = 20)

        assertEquals(listOf("c1", "c2"), conversations.map { it.conversationId })
        assertEquals("latest", conversations.first().lastMessagePreview)
        assertEquals(1, conversations.first().unreadCount)
    }

    @Test
    fun clearUnreadResetsOnlyTargetConversation() {
        val dao = InMemoryConversationDao()
        dao.upsertFromMessage(message("c1", "u2", "one", createdAt = 100), incrementUnread = true)
        dao.upsertFromMessage(message("c2", "u3", "two", createdAt = 200), incrementUnread = true)

        dao.clearUnread("c1")

        val conversations = dao.listConversations(limit = 20)
        assertEquals(0, conversations.first { it.conversationId == "c1" }.unreadCount)
        assertEquals(1, conversations.first { it.conversationId == "c2" }.unreadCount)
    }

    private fun message(
        conversationId: String,
        peerId: String,
        content: String,
        createdAt: Long
    ): ChatMessage {
        return ChatMessage(
            messageId = "$conversationId-$createdAt",
            conversationId = conversationId,
            senderId = peerId,
            receiverId = "me",
            clientSeq = createdAt,
            serverSeq = createdAt,
            content = content,
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }
}
