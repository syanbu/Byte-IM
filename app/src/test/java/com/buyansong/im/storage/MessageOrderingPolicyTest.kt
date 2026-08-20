package com.buyansong.im.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOrderingPolicyTest {

    private fun localMessage(messageId: String, createdAt: Long): ChatMessage {
        return ChatMessage(
            messageId = messageId,
            conversationId = "single:u_a:u_b",
            senderId = "u_a",
            receiverId = "u_b",
            serverSeq = null,
            content = "x",
            status = MessageStatus.FAILED,
            direction = MessageDirection.OUTGOING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    @Test
    fun newestFirst_sameCreatedAtWithoutServerSeq_fallsBackToMessageId() {
        val messages = listOf(
            localMessage("m-2", createdAt = 1000L),
            localMessage("m-1", createdAt = 1000L),
            localMessage("m-3", createdAt = 1000L)
        )

        val sorted = MessageOrderingPolicy.sortNewestFirst(messages)

        assertEquals(listOf("m-3", "m-2", "m-1"), sorted.map { it.messageId })
    }

    @Test
    fun oldestFirst_sameCreatedAtWithoutServerSeq_fallsBackToMessageId() {
        val messages = listOf(
            localMessage("m-2", createdAt = 1000L),
            localMessage("m-1", createdAt = 1000L),
            localMessage("m-3", createdAt = 1000L)
        )

        val sorted = MessageOrderingPolicy.sortOldestFirst(messages)

        assertEquals(listOf("m-1", "m-2", "m-3"), sorted.map { it.messageId })
    }
}
