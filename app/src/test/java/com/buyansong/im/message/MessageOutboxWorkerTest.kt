package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.PendingMessage
import com.buyansong.im.storage.PendingMessageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOutboxWorkerTest {

    private class FakeConnection : ImConnection {
        val sent = mutableListOf<ImPacket>()
        override val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImPacket>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket): Boolean {
            sent += packet
            return true
        }
    }

    private class CountingPendingMessageDao(
        private val delegate: InMemoryPendingMessageDao = InMemoryPendingMessageDao()
    ) : PendingMessageDao by delegate {
        var dueMessagesCalls = 0
            private set

        override fun dueMessages(now: Long, limit: Int): List<PendingMessage> {
            dueMessagesCalls += 1
            return delegate.dueMessages(now, limit)
        }
    }

    private data class Fixture(
        val repository: MessageRepository,
        val connection: FakeConnection,
        val pendingDao: CountingPendingMessageDao,
        val worker: MessageOutboxWorker
    )

    private fun TestScope.fixture(): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val pendingDao = CountingPendingMessageDao()
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = pendingDao,
            connection = connection,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
        val worker = MessageOutboxWorker(
            repository = repository,
            connection = connection,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
            nowProvider = { testScheduler.currentTime }
        )
        return Fixture(repository, connection, pendingDao, worker)
    }

    @Test
    fun enqueuedMessage_isRetriedOnlyAfterDeadline() = runTest {
        val (repository, connection, _, worker) = fixture()
        repository.sendText("u_a", "u_b", "hello", now = 0L)
        assertEquals(1, connection.sent.size)
        connection.states.value = ConnectionState.Authenticated
        worker.start()
        testScheduler.runCurrent()
        assertEquals(1, connection.sent.size)

        testScheduler.advanceTimeBy(4_999L)
        testScheduler.runCurrent()
        assertEquals(1, connection.sent.size)

        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertEquals(2, connection.sent.size)
        worker.stop()
    }

    @Test
    fun ackedMessage_isNotRetriedAgain() = runTest {
        val (repository, connection, _, worker) = fixture()
        val message = repository.sendText("u_a", "u_b", "hello", now = 0L)
        connection.states.value = ConnectionState.Authenticated
        worker.start()
        testScheduler.runCurrent()

        repository.handlePacket(
            ImPacket(
                cmd = ImCommand.MESSAGE_ACK.value,
                body = """{"messageId":"${message.messageId}","serverSeq":1001,"serverTime":100}"""
                    .toByteArray()
            )
        )
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(120_000L)
        testScheduler.runCurrent()
        assertEquals(1, connection.sent.size)
        worker.stop()
    }

    @Test
    fun disconnectedConnection_doesNotRetry() = runTest {
        val (repository, connection, pendingDao, worker) = fixture()
        repository.sendText("u_a", "u_b", "hello", now = 0L)
        connection.states.value = ConnectionState.Authenticated
        worker.start()
        testScheduler.runCurrent()
        val scansBeforeDisconnect = pendingDao.dueMessagesCalls

        connection.states.value = ConnectionState.Disconnected
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(120_000L)
        testScheduler.runCurrent()
        assertEquals(1, connection.sent.size)
        assertEquals(scansBeforeDisconnect, pendingDao.dueMessagesCalls)
        worker.stop()
    }

    @Test
    fun reauthenticatedConnection_rescansImmediately() = runTest {
        val (repository, connection, _, worker) = fixture()
        repository.sendText("u_a", "u_b", "hello", now = 0L)
        connection.states.value = ConnectionState.Authenticated
        worker.start()
        testScheduler.runCurrent()

        connection.states.value = ConnectionState.Disconnected
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(6_000L)
        testScheduler.runCurrent()
        assertEquals(1, connection.sent.size)

        connection.states.value = ConnectionState.Authenticated
        testScheduler.runCurrent()
        assertEquals(2, connection.sent.size)
        worker.stop()
    }

    @Test
    fun idleOutbox_doesNotQueryAgain() = runTest {
        val (_, connection, pendingDao, worker) = fixture()
        connection.states.value = ConnectionState.Authenticated
        worker.start()
        testScheduler.runCurrent()
        assertEquals(1, pendingDao.dueMessagesCalls)

        testScheduler.advanceTimeBy(300_000L)
        testScheduler.runCurrent()
        assertEquals(1, pendingDao.dueMessagesCalls)
        worker.stop()
    }
}
