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
import com.codex.im.storage.TransactionRunner
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
    fun sendTextSendsNetworkPacketOnlyAfterLocalTransactionCommits() {
        val events = mutableListOf<String>()
        val fixture = Fixture(
            transactionRunner = RecordingTransactionRunner(events),
            events = events
        )

        fixture.repository.sendText(
            senderId = "u1",
            receiverId = "u2",
            content = "hello",
            now = 1_000L
        )

        assertEquals(listOf("begin", "commit", "send"), events)
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
    fun retryDuePendingMessagesResendsOriginalPacketBodyAndSchedulesBackoff() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        val originalPacket = fixture.connection.sentPackets.single()

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        assertEquals(2, fixture.connection.sentPackets.size)
        val retriedPacket = fixture.connection.sentPackets.last()
        assertEquals(originalPacket.cmd, retriedPacket.cmd)
        assertEquals(originalPacket.body.decodeToString(), retriedPacket.body.decodeToString())
        val pending = fixture.pendingDao.dueMessages(now = 16_000L, limit = 10).single()
        assertEquals(message.messageId, pending.messageId)
        assertEquals(1, pending.retryCount)
        assertEquals(11_000L, pending.nextRetryAt)
    }

    @Test
    fun retryDuePendingMessagesMarksFailedAfterRetryExhaustion() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        val exhausted = fixture.pendingDao.dueMessages(now = 6_000L, limit = 10)
            .single()
            .copy(retryCount = 5, nextRetryAt = 6_000L)
        fixture.pendingDao.upsert(exhausted)

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.FAILED, stored.status)
        assertTrue(fixture.pendingDao.dueMessages(now = 60_000L, limit = 10).isEmpty())
        assertEquals(1, fixture.connection.sentPackets.size)
    }

    @Test
    fun retryDuePendingMessagesCountsAttemptWhenSendReturnsFalse() {
        val fixture = Fixture()
        fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        fixture.connection.sendSucceeds = false

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        val pending = fixture.pendingDao.dueMessages(now = 11_000L, limit = 10).single()
        assertEquals(1, pending.retryCount)
        assertEquals(2, fixture.connection.sentPackets.size)
    }

    @Test
    fun retryDuePendingMessagesMarksFailedAfterRepeatedSendFailures() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        fixture.connection.sendSucceeds = false

        fixture.repository.retryDuePendingMessages(now = 6_000L)
        fixture.repository.retryDuePendingMessages(now = 11_000L)
        fixture.repository.retryDuePendingMessages(now = 21_000L)
        fixture.repository.retryDuePendingMessages(now = 41_000L)
        fixture.repository.retryDuePendingMessages(now = 81_000L)
        fixture.repository.retryDuePendingMessages(now = 141_000L)

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.FAILED, stored.status)
        assertTrue(fixture.pendingDao.dueMessages(now = 141_000L, limit = 10).isEmpty())
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
        assertEquals(ImCommand.DELIVERY_ACK.value, fixture.connection.sentPackets.single().cmd)
        assertEquals(
            """{"messageId":"remote-1","conversationId":"single:u1:u2","serverSeq":90,"receiverId":"u1"}""",
            fixture.connection.sentPackets.single().body.decodeToString()
        )
    }

    @Test
    fun incomingMessageForOpenConversationDoesNotIncrementUnread() {
        val fixture = Fixture()
        fixture.repository.openConversation(currentUserId = "u1", peerId = "u2")

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-open-1",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":8,
                      "serverSeq":91,
                      "content":"already reading",
                      "timestamp":1600
                    }
                """.trimIndent().toByteArray()
            )
        )

        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals("already reading", conversation.lastMessagePreview)
        assertEquals(0, conversation.unreadCount)
    }

    @Test
    fun duplicateIncomingMessageStillSendsDeliveryAckWithoutDuplicatingLocalRow() {
        val fixture = Fixture()
        val packet = ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """
                {
                  "messageId":"remote-dup-1",
                  "conversationId":"single:u2:u1",
                  "senderId":"u2",
                  "receiverId":"u1",
                  "clientSeq":9,
                  "serverSeq":92,
                  "content":"dup",
                  "timestamp":1700
                }
            """.trimIndent().toByteArray()
        )

        fixture.repository.handlePacket(packet)
        fixture.repository.handlePacket(packet)

        val stored = fixture.messageDao.queryPage("single:u1:u2", beforeTime = null, limit = 20)
        assertEquals(listOf("remote-dup-1"), stored.map { it.messageId })
        assertEquals(2, fixture.connection.sentPackets.size)
        assertTrue(fixture.connection.sentPackets.all { it.cmd == ImCommand.DELIVERY_ACK.value })
    }

    @Test
    fun totalUnreadCountReturnsUnreadAcrossAllConversations() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            incomingMessagePacket(
                messageId = "remote-1",
                senderId = "u2",
                receiverId = "u1",
                clientSeq = 7,
                serverSeq = 90,
                content = "hi",
                timestamp = 1_500L
            )
        )
        fixture.repository.handlePacket(
            incomingMessagePacket(
                messageId = "remote-2",
                senderId = "u3",
                receiverId = "u1",
                clientSeq = 8,
                serverSeq = 91,
                content = "hello",
                timestamp = 1_600L
            )
        )

        assertEquals(2, fixture.repository.totalUnreadCount())
    }

    private class Fixture(
        transactionRunner: TransactionRunner? = null,
        events: MutableList<String> = mutableListOf()
    ) {
        val events = events
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val pendingDao = InMemoryPendingMessageDao()
        val connection = FakeConnection(events)
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = pendingDao,
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator(),
            transactionRunner = transactionRunner ?: TransactionRunner.immediate()
        )
    }

    private class FakeConnection(
        private val events: MutableList<String> = mutableListOf()
    ) : ImConnection {
        val sentPackets = mutableListOf<ImPacket>()
        var sendSucceeds = true
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean {
            events += "send"
            sentPackets.add(packet)
            return sendSucceeds
        }
    }

    private class RecordingTransactionRunner(
        private val events: MutableList<String>
    ) : TransactionRunner {
        override fun runInTransaction(block: () -> Unit) {
            events += "begin"
            block()
            events += "commit"
        }
    }

    private fun incomingMessagePacket(
        messageId: String,
        senderId: String,
        receiverId: String,
        clientSeq: Long,
        serverSeq: Long,
        content: String,
        timestamp: Long
    ): ImPacket {
        return ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """
                {
                  "messageId":"$messageId",
                  "conversationId":"single:$senderId:$receiverId",
                  "senderId":"$senderId",
                  "receiverId":"$receiverId",
                  "clientSeq":$clientSeq,
                  "serverSeq":$serverSeq,
                  "content":"$content",
                  "timestamp":$timestamp
                }
            """.trimIndent().toByteArray()
        )
    }
}
