package com.codex.im.chat

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageRepository
import com.codex.im.message.SeqGenerator
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelTest {
    @Test
    fun startConnectsWebSocketWithSessionToken() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()

        assertEquals("mock-token-13800113800", fixture.connection.connectedToken)
    }

    @Test
    fun sendTextRefreshesVisibleMessages() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendText("hello", now = 1_000L)

        assertEquals(listOf("hello"), fixture.viewModel.state.value.messages.map { it.content })
        assertEquals("13900113900", fixture.viewModel.state.value.peerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun incomingPacketRefreshesVisibleMessages() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.start()
        runCurrent()

        fixture.connection.incoming.emit(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-1",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"hi 13800113800",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(listOf("hi 13800113800"), fixture.viewModel.state.value.messages.map { it.content })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun connectionStateChangesDoNotChangeChatUiState() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()
        val before = fixture.viewModel.state.value

        fixture.connection.state.value = ConnectionState.Authenticated
        runCurrent()

        assertEquals(before, fixture.viewModel.state.value)
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        private val messageDao = InMemoryMessageDao()
        private val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val viewModel = ChatViewModel(
            session = AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L),
            repository = repository,
            connection = connection,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        var connectedToken: String? = null
        val incoming = MutableSharedFlow<ImPacket>()
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val states: StateFlow<ConnectionState> = state
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) {
            connectedToken = token
        }

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean = true
    }
}
