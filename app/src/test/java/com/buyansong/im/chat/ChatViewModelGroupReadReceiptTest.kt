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
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import com.buyansong.im.storage.TransactionRunner
import com.buyansong.im.protocol.ImPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelGroupReadReceiptTest {

    private class FakeConnection : ImConnection {
        override val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
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

    private fun session(userId: String = "u_receiver") = AuthSession(
        token = "token-$userId",
        userId = userId,
        username = userId,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun member(userId: String) = GroupMember(
        groupId = "g_1",
        userId = userId,
        displayName = userId,
        avatarUrl = null,
        role = GroupMemberRole.MEMBER,
        joinedAt = 0L,
        updatedAt = 0L
    )

    private fun msg(id: String, senderId: String, serverSeq: Long?) = ChatMessage(
        messageId = id,
        conversationId = "group:g_1",
        senderId = senderId,
        receiverId = "g_1",
        clientSeq = 0L,
        serverSeq = serverSeq,
        content = "x",
        status = MessageStatus.SENT,
        direction = MessageDirection.OUTGOING,
        createdAt = 0L,
        updatedAt = 0L,
        type = MessageType.TEXT,
        conversationType = ConversationType.GROUP
    )

    private fun newRepository(messageDao: InMemoryMessageDao = InMemoryMessageDao()): MessageRepository {
        return MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator(),
            transactionRunner = object : TransactionRunner {
                override fun runInTransaction(block: () -> Unit) = block()
            }
        )
    }

    private fun newViewModel(repository: MessageRepository, initialPeerId: String): ChatViewModel {
        return ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(InMemoryUserProfileDao(), FakeProfileApi()),
            initialPeerId = initialPeerId,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun start_groupConversationInitialPage_keepsOldestFirstOrderForReceiver() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val repository = newRepository(messageDao)
        val viewModel = newViewModel(repository, initialPeerId = "group:g_1")
        listOf(
            msg("m1", "u_sender", 1L).copy(createdAt = 1L, updatedAt = 1L, direction = MessageDirection.INCOMING),
            msg("m2", "u_sender", 2L).copy(createdAt = 2L, updatedAt = 2L, direction = MessageDirection.INCOMING),
            msg("m3", "u_sender", 3L).copy(createdAt = 3L, updatedAt = 3L, direction = MessageDirection.INCOMING)
        ).forEach(messageDao::insertOrIgnore)

        viewModel.start()

        assertEquals(listOf("m1", "m2", "m3"), viewModel.state.value.messages.map { it.messageId })
        viewModel.stop()
    }

    @Test
    fun start_singleConversationInitialPage_keepsOldestFirstOrderLikeGroup() = runBlocking {
        val messageDao = InMemoryMessageDao()
        val repository = newRepository(messageDao)
        val conversationId = repository.conversationIdFor("u_receiver", "u_sender")
        val viewModel = newViewModel(repository, initialPeerId = "u_sender")
        listOf(
            msg("m1", "u_sender", 1L).copy(
                conversationId = conversationId,
                receiverId = "u_receiver",
                createdAt = 1L,
                updatedAt = 1L,
                direction = MessageDirection.INCOMING,
                conversationType = ConversationType.SINGLE,
                groupId = null
            ),
            msg("m2", "u_sender", 2L).copy(
                conversationId = conversationId,
                receiverId = "u_receiver",
                createdAt = 2L,
                updatedAt = 2L,
                direction = MessageDirection.INCOMING,
                conversationType = ConversationType.SINGLE,
                groupId = null
            ),
            msg("m3", "u_sender", 3L).copy(
                conversationId = conversationId,
                receiverId = "u_receiver",
                createdAt = 3L,
                updatedAt = 3L,
                direction = MessageDirection.INCOMING,
                conversationType = ConversationType.SINGLE,
                groupId = null
            )
        ).forEach(messageDao::insertOrIgnore)

        viewModel.start()

        assertEquals(listOf("m1", "m2", "m3"), viewModel.state.value.messages.map { it.messageId })
        viewModel.stop()
    }

    @Test
    fun policy_drivesIndicatorStateForCurrentUser() {
        val messages = listOf(
            msg("m1", "u_a", 1L),
            msg("m2", "u_a", 2L)
        )
        val cursors = listOf(
            GroupReadCursor("g_1", "u_b", 2L, 100L),
            GroupReadCursor("g_1", "u_c", 2L, 200L)
        )
        val members = listOf(member("u_a"), member("u_b"), member("u_c"))

        val latestOwnId = GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a")
        val latestOwn = messages.firstOrNull { it.messageId == latestOwnId }
        val readers = GroupReadReceiptPolicy.readersOf(
            messageSenderId = latestOwn!!.senderId,
            messageServerSeq = latestOwn.serverSeq,
            cursors = cursors,
            members = members
        )
        assertEquals("m2", latestOwnId)
        assertEquals(2, readers.size)
        assertEquals(listOf("u_c", "u_b"), readers.map { it.userId })
    }
}
