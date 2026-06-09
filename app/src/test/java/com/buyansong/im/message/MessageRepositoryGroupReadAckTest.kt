package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.group.DefaultGroupReadCursorRepository
import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryGroupReadCursorDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.TransactionRunner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryGroupReadAckTest {

    private class FakeConnection : ImConnection {
        val sent = mutableListOf<ImPacket>()
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImPacket>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket): Boolean {
            sent += packet
            return true
        }
    }

    private fun repo(): Triple<MessageRepository, FakeConnection, InMemoryGroupReadCursorDao> {
        val conn = FakeConnection()
        val cursorDao = InMemoryGroupReadCursorDao()
        val repo = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = conn,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator(),
            transactionRunner = object : TransactionRunner {
                override fun runInTransaction(block: () -> Unit) = block()
            },
            groupReadCursorRepository = DefaultGroupReadCursorRepository(cursorDao)
        )
        return Triple(repo, conn, cursorDao)
    }

    @Test
    fun applyIncomingGroupReadAck_isMonotonic() = runBlocking {
        val (repository, _, dao) = repo()
        repository.applyIncomingGroupReadAck("g_1", "u_b", 100L, 1_000L)
        repository.applyIncomingGroupReadAck("g_1", "u_b", 50L, 2_000L)
        repository.applyIncomingGroupReadAck("g_1", "u_b", 100L, 3_000L)
        repository.applyIncomingGroupReadAck("g_1", "u_b", 200L, 4_000L)
        val rows = dao.findByGroup("g_1")
        assertEquals(1, rows.size)
        assertEquals(200L, rows.single().readUpToServerSeq)
        assertEquals(4_000L, rows.single().readAt)
    }

    @Test
    fun sendGroupReadAck_isMonotonicAndCarriesGroupMarker() {
        val messageDao = InMemoryMessageDao()
        val conn = FakeConnection()
        val cursorDao = InMemoryGroupReadCursorDao()
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = conn,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator(),
            transactionRunner = object : TransactionRunner {
                override fun runInTransaction(block: () -> Unit) = block()
            },
            groupReadCursorRepository = DefaultGroupReadCursorRepository(cursorDao)
        )
        messageDao.insertOrIgnore(
            ChatMessage(
                messageId = "m1",
                conversationId = "group:g_1",
                senderId = "u_b",
                receiverId = "u_a",
                clientSeq = 1L,
                serverSeq = 100L,
                content = "hello",
                status = MessageStatus.RECEIVED,
                direction = MessageDirection.INCOMING,
                createdAt = 1L,
                updatedAt = 1L,
                conversationType = ConversationType.GROUP,
                groupId = "g_1"
            )
        )

        repository.sendGroupReadAck("g_1", "u_a", now = 1L)
        repository.sendGroupReadAck("g_1", "u_a", now = 2L)

        assertEquals(1, conn.sent.size)
        val packet = conn.sent.single()
        assertEquals(ImCommand.READ_ACK.value, packet.cmd)
        val body = packet.body.decodeToString()
        assertTrue(body.contains("\"conversationType\":\"GROUP\""))
    }
}
