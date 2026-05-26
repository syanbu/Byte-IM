package com.codex.im.message

import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
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

class MessagePacketProcessorTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun processorPersistsIncomingPacketsEvenWhenNoScreenViewModelIsCollecting() = runTest {
        val fixture = Fixture(this)
        fixture.processor.start()
        runCurrent()

        fixture.connection.incoming.emit(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-while-on-contacts",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13800113800",
                      "receiverId":"13900113900",
                      "clientSeq":1,
                      "serverSeq":1779785107743,
                      "content":"1",
                      "timestamp":1779785107743
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val stored = fixture.messageDao.queryPage("single:13800113800:13900113900", beforeTime = null, limit = 20)
        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(listOf("remote-while-on-contacts"), stored.map { it.messageId })
        assertEquals("1", conversation.lastMessagePreview)
        assertEquals(1, conversation.unreadCount)
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        private val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val processor = MessagePacketProcessor(
            repository = repository,
            connection = connection,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        val incoming = MutableSharedFlow<ImPacket>()
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Authenticated)
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean = true
    }
}
