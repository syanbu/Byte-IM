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
import com.buyansong.im.storage.UserProfile
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

    private class FakeProfileApi(
        var profilesById: Map<String, UserProfile> = emptyMap()
    ) : ProfileApi {
        val batchRequests = mutableListOf<List<String>>()

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")
        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            return profilesById[userId]?.let(ProfileResult::Success) ?: ProfileResult.Failure("unused")
        }
        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            batchRequests += userIds
            return ProfileBatchResult.Success(userIds.mapNotNull(profilesById::get))
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

    private fun message(id: String, createdAt: Long, senderProfileVersion: Long? = null): ChatMessage {
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
            updatedAt = createdAt,
            senderProfileVersion = senderProfileVersion
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

    @Test
    fun constructor_usesCachedPeerProfileBeforeNetworkRefreshEvenWithoutMessageCache() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(profile("u_b", nickname = "Bee", avatarUrl = "https://avatar.example/u_b.png"))
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )

        val viewModel = ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(profileDao, FakeProfileApi()),
            initialPeerId = "u_b",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        assertEquals("Bee", viewModel.state.value.peerName)
        assertEquals("https://avatar.example/u_b.png", viewModel.state.value.peerAvatarUrl)
        viewModel.stop()
    }

    @Test
    fun constructor_usesCachedCurrentUserProfileBeforeNetworkRefresh() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(profile("u_a", nickname = "Alice", avatarUrl = "https://avatar.example/u_a.png"))
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )

        val viewModel = ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(profileDao, FakeProfileApi()),
            initialPeerId = "u_b",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        assertEquals("https://avatar.example/u_a.png", viewModel.state.value.currentUserAvatarUrl)
        viewModel.stop()
    }

    @Test
    fun selectPeer_usesCachedPeerProfileInsteadOfFlashingPeerId() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(profile("u_c", nickname = "Cee", avatarUrl = "https://avatar.example/u_c.png"))
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
        val viewModel = ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(profileDao, FakeProfileApi()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.selectPeer("u_c")

        assertEquals("Cee", viewModel.state.value.peerName)
        assertEquals("https://avatar.example/u_c.png", viewModel.state.value.peerAvatarUrl)
        viewModel.stop()
    }

    @Test
    fun start_singleChatUsesMessageProfileVersionToRefreshSenderProfileMap() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(
            profile(
                "u_b",
                nickname = "Old Bee",
                avatarUrl = "old.png",
                profileVersion = 1L
            )
        )
        val profileApi = FakeProfileApi(
            profilesById = mapOf(
                "u_b" to profile(
                    "u_b",
                    nickname = "New Bee",
                    avatarUrl = "new.png",
                    profileVersion = 2L
                )
            )
        )
        val messageDao = InMemoryMessageDao()
        messageDao.insertOrIgnore(message("m1", createdAt = 1L, senderProfileVersion = 2L))
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )

        val viewModel = ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(profileDao, profileApi),
            initialPeerId = "u_b",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.start()

        assertEquals(listOf(listOf("u_b")), profileApi.batchRequests)
        assertEquals("New Bee", viewModel.state.value.senderProfiles["u_b"]?.nickname)
        assertEquals("new.png", viewModel.state.value.peerAvatarUrl)
        viewModel.stop()
    }

    @Test
    fun start_singleChatForceRefreshesCachedPeerWhenMessagesHaveNoProfileVersionHint() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(
            profile(
                "u_b",
                nickname = "Old Bee",
                avatarUrl = "old.png",
                profileVersion = 1L
            )
        )
        val profileApi = FakeProfileApi(
            profilesById = mapOf(
                "u_b" to profile(
                    "u_b",
                    nickname = "New Bee",
                    avatarUrl = "new.png",
                    profileVersion = 2L
                )
            )
        )
        val messageDao = InMemoryMessageDao()
        messageDao.insertOrIgnore(message("m1", createdAt = 1L, senderProfileVersion = null))
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )

        val viewModel = ChatViewModel(
            session = session(),
            repository = repository,
            connection = FakeConnection(),
            profileRepository = ProfileRepository(profileDao, profileApi),
            initialPeerId = "u_b",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.start()

        assertEquals("New Bee", viewModel.state.value.senderProfiles["u_b"]?.nickname)
        assertEquals("new.png", viewModel.state.value.peerAvatarUrl)
        viewModel.stop()
    }

    private fun profile(
        userId: String,
        nickname: String,
        avatarUrl: String? = null,
        profileVersion: Long = 1L
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
