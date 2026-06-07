package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
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
        val originalPacketBody = fixture.createPendingMessageBody()

        fixture.worker.start()
        fixture.connection.state.value = ConnectionState.Authenticated
        runCurrent()

        assertEquals(listOf(originalPacketBody), fixture.connection.sentPackets.map { it.body.decodeToString() })
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        private val pendingDao = InMemoryPendingMessageDao()
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

        fun createPendingMessageBody(): String {
            repository.sendText(senderId = "u1", receiverId = "u2", content = "hello", now = 0L)
            val originalPacketBody = connection.sentPackets.single().body.decodeToString()
            connection.sentPackets.clear()
            return originalPacketBody
        }
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
