package com.buyansong.im.conversation

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
import com.buyansong.im.storage.Conversation
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationListViewModelTest {
    private class FakeConnection : ImConnection {
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImPacket>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket): Boolean = true
    }

    private class FakeProfileApi(
        private val profilesById: Map<String, UserProfile>
    ) : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")
        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")
        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(userIds.mapNotNull(profilesById::get))
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String?,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    @Test
    fun normalizeConversationListScrollPosition_clampsNegativeValues() {
        val normalized = normalizeConversationListScrollPosition(
            firstVisibleItemIndex = -3,
            firstVisibleItemScrollOffset = -40
        )

        assertEquals(0, normalized.firstVisibleItemIndex)
        assertEquals(0, normalized.firstVisibleItemScrollOffset)
    }

    @Test
    fun normalizeConversationListScrollPosition_keepsPositiveValues() {
        val normalized = normalizeConversationListScrollPosition(
            firstVisibleItemIndex = 18,
            firstVisibleItemScrollOffset = 96
        )

        assertEquals(18, normalized.firstVisibleItemIndex)
        assertEquals(96, normalized.firstVisibleItemScrollOffset)
    }

    @Test
    fun start_refreshesCachedSingleChatAvatarWhenLastIncomingMessageHasNewerProfileVersion() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(profile("u_b", nickname = "Old Bee", avatarUrl = "old.png", profileVersion = 1L))
        val profileRepository = ProfileRepository(
            profileDao,
            FakeProfileApi(
                profilesById = mapOf(
                    "u_b" to profile("u_b", nickname = "New Bee", avatarUrl = "new.png", profileVersion = 2L)
                )
            )
        )
        val messageDao = InMemoryMessageDao()
        messageDao.insertOrIgnore(
            ChatMessage(
                messageId = "m1",
                conversationId = "single:u_a:u_b",
                senderId = "u_b",
                receiverId = "u_a",
                clientSeq = 1L,
                serverSeq = 1L,
                content = "hello",
                status = MessageStatus.RECEIVED,
                direction = MessageDirection.INCOMING,
                createdAt = 1L,
                updatedAt = 1L,
                senderProfileVersion = 2L
            )
        )
        val conversationDao = InMemoryConversationDao()
        conversationDao.upsertConversation(
            Conversation(
                conversationId = "single:u_a:u_b",
                peerId = "u_b",
                peerName = "u_b",
                type = ConversationType.SINGLE,
                title = "u_b",
                avatarUrl = null,
                lastMessageId = "m1",
                lastMessagePreview = "hello",
                lastMessageTime = 1L,
                unreadCount = 0,
                updatedAt = 1L
            )
        )
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
        val viewModel = ConversationListViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = profileRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.start()

        assertEquals("New Bee", viewModel.state.value.items.single().peerName)
        assertEquals("new.png", viewModel.state.value.items.single().peerAvatarUrl)
        viewModel.stop()
    }

    private fun session() = AuthSession(
        token = "token-u_a",
        userId = "u_a",
        username = "u_a",
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun profile(
        userId: String,
        nickname: String,
        avatarUrl: String?,
        profileVersion: Long
    ): UserProfile {
        return UserProfile(
            userId = userId,
            phone = userId,
            nickname = nickname,
            avatarUrl = avatarUrl,
            avatarUpdatedAt = 0L,
            updatedAt = 0L,
            profileVersion = profileVersion
        )
    }
}
