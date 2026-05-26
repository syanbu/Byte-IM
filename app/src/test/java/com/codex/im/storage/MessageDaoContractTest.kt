package com.codex.im.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDaoContractTest {
    @Test
    fun insertOrIgnoreDeduplicatesByMessageId() {
        val dao = InMemoryMessageDao()
        val message = sampleMessage(messageId = "m1", createdAt = 100)

        assertTrue(dao.insertOrIgnore(message))
        assertFalse(dao.insertOrIgnore(message.copy(content = "duplicate")))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)
        assertEquals(1, page.size)
        assertEquals("hello", page.single().content)
    }

    @Test
    fun queryPageReturnsMessagesBeforeCursorNewestFirst() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "m1", createdAt = 100))
        dao.insertOrIgnore(sampleMessage(messageId = "m2", createdAt = 200))
        dao.insertOrIgnore(sampleMessage(messageId = "m3", createdAt = 300))

        val page = dao.queryPage("single:u1:u2", beforeTime = 300, limit = 2)

        assertEquals(listOf("m2", "m1"), page.map { it.messageId })
    }

    @Test
    fun queryPageOrdersServerSequencedMessagesBeforeLocalTime() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "seq-2", createdAt = 1_000, serverSeq = 2))
        dao.insertOrIgnore(sampleMessage(messageId = "seq-1", createdAt = 2_000, serverSeq = 1))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("seq-2", "seq-1"), page.map { it.messageId })
    }

    @Test
    fun queryPageKeepsSendingMessagesVisibleWithoutBreakingServerSeqOrder() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "seq-1", createdAt = 4_000, serverSeq = 1))
        dao.insertOrIgnore(sampleMessage(messageId = "sending", createdAt = 5_000, serverSeq = null))
        dao.insertOrIgnore(sampleMessage(messageId = "seq-2", createdAt = 1_000, serverSeq = 2))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("sending", "seq-2", "seq-1"), page.map { it.messageId })
    }

    @Test
    fun ackUpdatesMessageStatusAndServerSeq() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "m1", createdAt = 100))

        assertTrue(dao.markAcked(messageId = "m1", serverSeq = 42L, updatedAt = 150L))

        val message = dao.queryPage("single:u1:u2", beforeTime = null, limit = 1).single()
        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(42L, message.serverSeq)
        assertEquals(150L, message.updatedAt)
    }

    private fun sampleMessage(messageId: String, createdAt: Long, serverSeq: Long? = null): ChatMessage {
        return ChatMessage(
            messageId = messageId,
            conversationId = "single:u1:u2",
            senderId = "u1",
            receiverId = "u2",
            clientSeq = createdAt,
            serverSeq = serverSeq,
            content = "hello",
            status = MessageStatus.SENDING,
            direction = MessageDirection.OUTGOING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }
}
