package com.codex.im.message

import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryGroupConversationTest {
    @Test
    fun createLocalGroupConversationAddsGroupRowWithoutUnread() {
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )

        val conversation = repository.createLocalGroupConversation(
            creatorUserId = "13800113800",
            memberUserIds = listOf("13900113900", "17724734511"),
            now = 1_000L
        )

        assertTrue(conversation.conversationId.startsWith("group:13800113800:1000:"))
        assertEquals(conversation.conversationId, conversation.peerId)
        assertEquals("群聊(3)", conversation.peerName)
        assertEquals("已创建群聊", conversation.lastMessagePreview)
        assertEquals(1_000L, conversation.lastMessageTime)
        assertEquals(0, conversation.unreadCount)
        assertEquals(listOf(conversation), repository.conversations(limit = 20))
    }

    private class FakeConnection : ImConnection {
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean = true
    }
}
