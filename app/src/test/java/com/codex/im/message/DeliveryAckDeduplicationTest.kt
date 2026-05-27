package com.codex.im.message

import com.codex.im.auth.AuthSession
import com.codex.im.chat.ChatViewModel
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.conversation.ConversationListViewModel
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.InMemoryUserProfileDao
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

class DeliveryAckDeduplicationTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun singleIncomingPacketProducesOneDeliveryAckWhenUiViewModelsAreStarted() = runTest {
        val fixture = Fixture(this)

        fixture.packetProcessor.start()
        fixture.chatViewModel.start()
        fixture.conversationListViewModel.start()
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
                      "content":"hi",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(1, fixture.connection.sentPackets.count { it.cmd == ImCommand.DELIVERY_ACK.value })
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        private val profileRepository = ProfileRepository(InMemoryUserProfileDao(), FakeProfileApi())
        private val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val packetProcessor = MessagePacketProcessor(
            repository = repository,
            connection = connection,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
        val chatViewModel = ChatViewModel(
            session = SESSION,
            repository = repository,
            connection = connection,
            profileRepository = profileRepository,
            initialPeerId = "13900113900",
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
        val conversationListViewModel = ConversationListViewModel(
            session = SESSION,
            repository = repository,
            connection = connection,
            profileRepository = profileRepository,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        val incoming = MutableSharedFlow<ImPacket>()
        val sentPackets = mutableListOf<ImPacket>()
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Authenticated)
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean {
            sentPackets += packet
            return true
        }
    }

    private class FakeProfileApi : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    private companion object {
        val SESSION = AuthSession(
            token = "mock-token-13800113800",
            userId = "13800113800",
            username = "13800113800",
            expiresAtMillis = 2_000L
        )
    }
}
