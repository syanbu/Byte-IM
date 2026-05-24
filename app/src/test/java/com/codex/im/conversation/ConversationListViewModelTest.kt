package com.codex.im.conversation

import com.codex.im.DefaultPeerResolver
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
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.UserProfile
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
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

class ConversationListViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsFixedMockContactWhenThereAreNoConversations() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("13900113900", item.peerId)
        assertEquals("13900113900", item.peerName)
        assertEquals(null, item.peerAvatarUrl)
        assertEquals("", item.lastMessagePreview)
        assertEquals(0, item.unreadCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startUsesProfileNicknameAndAvatarForConversationRows() = runTest {
        val fixture = Fixture(this)
        fixture.profileDao.upsert(
            UserProfile(
                userId = "13900113900",
                phone = "13900113900",
                nickname = "Megumi",
                avatarUrl = "https://example.com/megumi.jpg",
                avatarUpdatedAt = 2_000L,
                updatedAt = 2_000L
            )
        )
        fixture.repository.sendText("13800113800", "13900113900", "hello", now = 1_000L)

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("Megumi", item.peerName)
        assertEquals("https://example.com/megumi.jpg", item.peerAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsRecentConversationsBeforeMockContact() = runTest {
        val fixture = Fixture(this)
        fixture.repository.sendText("13800113800", "13900113900", "older", now = 1_000L)
        fixture.repository.sendText("13800113800", "13700113700", "newer", now = 2_000L)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("13700113700", "13900113900"),
            fixture.viewModel.state.value.items.map { it.peerId }
        )
        assertEquals("newer", fixture.viewModel.state.value.items.first().lastMessagePreview)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startDoesNotAddDuplicateDefaultConversationWhenCanonicalConversationAlreadyExists() = runTest {
        val fixture = Fixture(this)
        fixture.repository.sendText("13900113900", "13800113800", "hello from other login", now = 1_000L)

        fixture.viewModel.start()
        runCurrent()

        val items = fixture.viewModel.state.value.items
        assertEquals(items.map { it.conversationId }.distinct(), items.map { it.conversationId })
        assertEquals(listOf("13900113900"), items.map { it.peerId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryConversationUpdateRefreshesVisibleList() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-latest",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":3,
                      "serverSeq":6,
                      "content":"Hello, You are my Hero",
                      "timestamp":3000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("Hello, You are my Hero", item.lastMessagePreview)
        assertEquals(1, item.unreadCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openConversationClearsUnreadAndExposesNavigationTarget() = runTest {
        val fixture = Fixture(this)
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-1",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"hello",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.openConversation("13900113900")
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals(0, item.unreadCount)
        assertEquals("13900113900", fixture.viewModel.state.value.navigationTargetPeerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeNavigationTargetClearsIt() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.openConversation("13900113900")
        runCurrent()

        fixture.viewModel.consumeNavigationTarget()

        assertEquals(null, fixture.viewModel.state.value.navigationTargetPeerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopCancelsIncomingPacketCollection() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.stop()
        fixture.connection.incoming.emit(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-after-stop",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":5,
                      "serverSeq":9,
                      "content":"after stop",
                      "timestamp":4000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("", item.lastMessagePreview)
        assertEquals(0, item.unreadCount)
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        private val conversationDao = InMemoryConversationDao()
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, FakeProfileApi())
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = conversationDao,
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val viewModel = ConversationListViewModel(
            session = AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L),
            repository = repository,
            connection = connection,
            defaultPeerResolver = DefaultPeerResolver::resolve,
            profileRepository = profileRepository,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        var connectedToken: String? = null
        val incoming = MutableSharedFlow<ImPacket>()
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) {
            connectedToken = token
        }

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
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}
