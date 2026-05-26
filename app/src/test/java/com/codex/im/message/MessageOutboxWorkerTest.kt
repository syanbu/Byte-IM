package com.codex.im.message

import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.PendingMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOutboxWorkerTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun authenticatedStateTriggersDuePendingResend() = runTest {
        val fixture = Fixture(this)
        fixture.pendingDao.upsert(
            PendingMessage(
                messageId = "pending-1",
                packetCmd = ImCommand.SEND_MESSAGE.value,
                packetBody = """{"messageId":"pending-1"}""",
                retryCount = 0,
                nextRetryAt = 0L,
                createdAt = 0L
            )
        )

        fixture.worker.start()
        fixture.connection.state.value = ConnectionState.Authenticated
        runCurrent()

        assertEquals(listOf("""{"messageId":"pending-1"}"""), fixture.connection.sentPackets.map { it.body.decodeToString() })
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        val pendingDao = InMemoryPendingMessageDao()
        private val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = pendingDao,
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val worker = MessageOutboxWorker(
            repository = repository,
            connection = connection,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val sentPackets = mutableListOf<ImPacket>()
        override val states: StateFlow<ConnectionState> = state
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean {
            sentPackets.add(packet)
            return true
        }
    }
}
