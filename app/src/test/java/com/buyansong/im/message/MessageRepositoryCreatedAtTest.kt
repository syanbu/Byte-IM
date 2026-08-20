package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.protocol.v2.ImEnvelope
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryCreatedAtTest {

    private class FakeConnection : ImConnection {
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImEnvelope>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(envelope: ImEnvelope): Boolean = true
    }

    private fun repository(): MessageRepository {
        return MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator()
        )
    }

    private fun image(index: Int): SelectedChatImage {
        return SelectedChatImage(
            originalBytes = byteArrayOf(1),
            thumbnailBytes = byteArrayOf(2),
            localOriginalPath = "/tmp/o$index.jpg",
            localThumbnailPath = "/tmp/t$index.jpg",
            width = 10,
            height = 10,
            mimeType = "image/jpeg",
            selectionOrder = index
        )
    }

    @Test
    fun sendText_sameMillisecond_createdAtIsStrictlyIncreasing() {
        val repository = repository()

        val first = repository.sendText("u_a", "u_b", "one", now = 5_000L)
        val second = repository.sendText("u_a", "u_b", "two", now = 5_000L)

        assertEquals(5_000L, first.createdAt)
        assertEquals(5_001L, second.createdAt)
        assertTrue(second.createdAt > first.createdAt)
    }

    @Test
    fun createLocalImageMessages_createdAtIsStrictlyIncreasingWithinBatch() {
        val repository = repository()

        val messages = repository.createLocalImageMessages(
            senderId = "u_a",
            receiverId = "u_b",
            groupId = null,
            selectedImages = listOf(image(0), image(1), image(2)),
            nowBase = 7_000L
        )

        assertEquals(listOf(7_000L, 7_001L, 7_002L), messages.map { it.createdAt })
    }

    @Test
    fun createLocalImageMessages_afterEarlierSend_createdAtContinuesAbovePrevious() {
        val repository = repository()
        repository.sendText("u_a", "u_b", "earlier", now = 9_000L)

        val messages = repository.createLocalImageMessages(
            senderId = "u_a",
            receiverId = "u_b",
            groupId = null,
            selectedImages = listOf(image(0), image(1)),
            nowBase = 8_000L
        )

        assertEquals(listOf(9_001L, 9_002L), messages.map { it.createdAt })
    }
}
