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

    @Test
    fun totalUnreadCountSumsAllConversations() {
        val dao = InMemoryConversationDao()
        dao.upsertFromMessage(message("c1", "u2", "one", createdAt = 100), incrementUnread = true)
        dao.upsertFromMessage(message("c1", "u2", "two", createdAt = 200), incrementUnread = true)
        dao.upsertFromMessage(message("c2", "u3", "three", createdAt = 300), incrementUnread = true)

        assertEquals(3, dao.totalUnreadCount())
    }

    @Test
    fun peerReadCursorOnlyMovesForward() {
        val dao = InMemoryConversationDao()
        dao.upsertFromMessage(message("c1", "u2", "one", createdAt = 100), incrementUnread = false)

        assertEquals(true, dao.updatePeerReadCursor("c1", readUpToServerSeq = 8L, readAt = 1_000L))
        assertEquals(false, dao.updatePeerReadCursor("c1", readUpToServerSeq = 7L, readAt = 2_000L))
        assertEquals(false, dao.updatePeerReadCursor("c1", readUpToServerSeq = 8L, readAt = 3_000L))

        val conversation = dao.listConversations(limit = 20).single()
        assertEquals(8L, conversation.peerReadUpToServerSeq)
        assertEquals(1_000L, conversation.peerReadAt)
    }

    @Test
    fun newerMessageKeepsExistingPeerReadCursor() {
        val dao = InMemoryConversationDao()
        dao.upsertFromMessage(message("c1", "u2", "read", createdAt = 100), incrementUnread = false)
        dao.updatePeerReadCursor("c1", readUpToServerSeq = 3L, readAt = 1_000L)

        dao.upsertFromMessage(message("c1", "u2", "unread", createdAt = 200), incrementUnread = false)

        val conversation = dao.listConversations(limit = 20).single()
        assertEquals("unread", conversation.lastMessagePreview)
        assertEquals(3L, conversation.peerReadUpToServerSeq)
        assertEquals(1_000L, conversation.peerReadAt)
    }

    @Test
    fun recalledLatestMessageUpdatesPreview() {
        val dao = InMemoryConversationDao()
        dao.upsertFromMessage(message("c1", "u2", "visible", createdAt = 100), incrementUnread = false)

        assertEquals(true, dao.updatePreviewForRecalledMessage("c1-100", "你撤回了一条消息", updatedAt = 200L))

        val conversation = dao.listConversations(limit = 20).single()
        assertEquals("你撤回了一条消息", conversation.lastMessagePreview)
        assertEquals(200L, conversation.updatedAt)
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
