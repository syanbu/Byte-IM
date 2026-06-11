package com.buyansong.im.chat

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.message.MessageIdGenerator
import com.buyansong.im.message.MessageRepository
import com.buyansong.im.message.SeqGenerator
import com.buyansong.im.profile.ProfileApi
import com.buyansong.im.profile.ProfileBatchResult
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.profile.ProfileResult
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelInitialCacheTest {

    private class FakeConnection : ImConnection {
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImPacket>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket): Boolean = true
    }

    private class FakeProfileApi : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")
        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")
        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String?,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.buyansong.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    private fun session() = AuthSession(
        token = "token-u_a",
        userId = "u_a",
        username = "u_a",
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun message(id: String, createdAt: Long): ChatMessage {
        return ChatMessage(
            messageId = id,
            conversationId = "single:u_a:u_b",
            senderId = "u_b",
            receiverId = "u_a",
            clientSeq = createdAt,
            serverSeq = createdAt,
            content = id,
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    @Test
    fun constructor_populatesMessagesFromCachedInitialPageBeforeStart() {
        val messageDao = InMemoryMessageDao()
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
        listOf(message("m1", 1L), message("m2", 2L)).forEach(messageDao::insertOrIgnore)
        repository.preloadInitialPageSync("single:u_a:u_b")

        val viewModel = ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(InMemoryUserProfileDao(), FakeProfileApi()),
            initialPeerId = "u_b",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        assertEquals(listOf("m1", "m2"), viewModel.state.value.messages.map { it.messageId })
        viewModel.stop()
    }
}
