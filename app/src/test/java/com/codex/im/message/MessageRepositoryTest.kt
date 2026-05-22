package com.codex.im.message

import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryTest {
    @Test
    fun sendTextStoresSendingMessageAndSendsPacket() {
        val fixture = Fixture()

        val message = fixture.repository.sendText(
            senderId = "u1",
            receiverId = "u2",
            content = "hello",
            now = 1_000L
        )

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.SENDING, stored.status)
        assertEquals(MessageDirection.OUTGOING, stored.direction)
        assertEquals("hello", stored.content)
        assertEquals(ImCommand.SEND_MESSAGE.value, fixture.connection.sentPackets.single().cmd)
        assertEquals(message.messageId, fixture.pendingDao.dueMessages(now = 6_000L, limit = 10).single().messageId)
    }

    @Test
    fun messageAckMarksMessageSentAndRemovesPendingRecord() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.MESSAGE_ACK.value,
                body = """{"messageId":"${message.messageId}","serverSeq":88,"serverTime":1200}""".toByteArray()
            )
        )

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.SENT, stored.status)
        assertEquals(88L, stored.serverSeq)
        assertTrue(fixture.pendingDao.dueMessages(now = 6_000L, limit = 10).isEmpty())
    }

    @Test
    fun incomingMessageIsPersistedAndIncrementsUnread() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-1",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":7,
                      "serverSeq":90,
                      "content":"hi",
                      "timestamp":1500
                    }
                """.trimIndent().toByteArray()
            )
        )

        val stored = fixture.messageDao.queryPage("single:u1:u2", beforeTime = null, limit = 20).single()
        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(MessageStatus.RECEIVED, stored.status)
        assertEquals(MessageDirection.INCOMING, stored.direction)
        assertEquals("hi", conversation.lastMessagePreview)
        assertEquals(1, conversation.unreadCount)
    }

    private class Fixture {
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val pendingDao = InMemoryPendingMessageDao()
        val connection = FakeConnection()
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = pendingDao,
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
    }

    private class FakeConnection : ImConnection {
        val sentPackets = mutableListOf<ImPacket>()
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean {
            sentPackets.add(packet)
            return true
        }
    }
}
