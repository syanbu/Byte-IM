package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.protocol.v2.ImEnvelope
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.PendingMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRepositoryOutboxReconcileTest {

    private class FakeConnection : ImConnection {
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImEnvelope>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(envelope: ImEnvelope): Boolean = true
    }

    private fun repo(pendingDao: InMemoryPendingMessageDao): MessageRepository {
        return MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = pendingDao,
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
    }

    private fun pending(messageId: String, nextRetryAt: Long, createdAt: Long = 0L): PendingMessage {
        return PendingMessage(
            messageId = messageId,
            packetCmd = 1,
            packetBody = "{}",
            retryCount = 0,
            nextRetryAt = nextRetryAt,
            createdAt = createdAt
        )
    }

    @Test
    fun reconcile_acceleratesOnlyIdsMissingFromServerReceipt() {
        val pendingDao = InMemoryPendingMessageDao()
        pendingDao.upsert(pending("m_known", nextRetryAt = 10_000L, createdAt = 0L))
        pendingDao.upsert(pending("m_missing", nextRetryAt = 20_000L, createdAt = 1L))
        val repository = repo(pendingDao)

        repository.reconcileUnackedWithServer(receivedMessageIds = listOf("m_known"), now = 5_000L)

        assertEquals(10_000L, pendingDao.findByMessageId("m_known")!!.nextRetryAt)
        assertEquals(5_000L, pendingDao.findByMessageId("m_missing")!!.nextRetryAt)
    }

    @Test
    fun reconcile_doesNotPostponeAlreadyDueMessages() {
        val pendingDao = InMemoryPendingMessageDao()
        pendingDao.upsert(pending("m_due", nextRetryAt = 1_000L))
        val repository = repo(pendingDao)

        repository.reconcileUnackedWithServer(receivedMessageIds = emptyList(), now = 5_000L)

        assertEquals(1_000L, pendingDao.findByMessageId("m_due")!!.nextRetryAt)
    }

    @Test
    fun reconcile_notifiesSignalOnlyWhenSomethingChanged() {
        val pendingDao = InMemoryPendingMessageDao()
        pendingDao.upsert(pending("m_missing", nextRetryAt = 20_000L))
        val repository = repo(pendingDao)
        val signal = repository.outboxChangeSignal

        val before = signal.revisions.value
        repository.reconcileUnackedWithServer(receivedMessageIds = emptyList(), now = 5_000L)
        assertEquals(before + 1, signal.revisions.value)

        repository.reconcileUnackedWithServer(receivedMessageIds = emptyList(), now = 5_000L)
        assertEquals(before + 1, signal.revisions.value)
    }

    @Test
    fun pendingOutgoingMessageIds_respectsLimit() {
        val pendingDao = InMemoryPendingMessageDao()
        repeat(5) { index ->
            pendingDao.upsert(pending("m_$index", nextRetryAt = 10_000L, createdAt = index.toLong()))
        }
        val repository = repo(pendingDao)

        assertEquals(listOf("m_0", "m_1", "m_2"), repository.pendingOutgoingMessageIds(limit = 3))
    }

    @Test
    fun earliestPendingRetryAt_reflectsEarliestRow() {
        val pendingDao = InMemoryPendingMessageDao()
        val repository = repo(pendingDao)
        assertEquals(null, repository.earliestPendingRetryAt())

        pendingDao.upsert(pending("m_1", nextRetryAt = 10_000L))
        pendingDao.upsert(pending("m_2", nextRetryAt = 5_000L))
        assertEquals(5_000L, repository.earliestPendingRetryAt())
    }
}
