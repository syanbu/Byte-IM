package com.codex.im.chat

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageRepository
import com.codex.im.message.SeqGenerator
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.UserProfile
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
import org.junit.Assert.assertFalse
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
    fun startExposesPeerNicknameAndAvatarFromProfileCache() = runTest {
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

        fixture.viewModel.start()
        runCurrent()

        assertEquals("Megumi", fixture.viewModel.state.value.peerName)
        assertEquals("https://example.com/megumi.jpg", fixture.viewModel.state.value.peerAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoadsLatestTwentyLocalMessages() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(25)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(20, fixture.viewModel.state.value.messages.size)
        assertEquals((25 downTo 6).map { "local-$it" }, fixture.viewModel.state.value.messages.map { it.messageId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMoreHistoryUsesOldestMessageTimeAndMergesEarlierPage() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(45)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.loadMoreHistory()

        assertEquals(40, fixture.viewModel.state.value.messages.size)
        assertEquals((45 downTo 6).map { "local-$it" }, fixture.viewModel.state.value.messages.map { it.messageId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repeatedLoadMoreHistoryDoesNotDuplicateMessagesAndStopsAtLocalEnd() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(25)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.loadMoreHistory()
        fixture.viewModel.loadMoreHistory()

        val messageIds = fixture.viewModel.state.value.messages.map { it.messageId }
        assertEquals((25 downTo 1).map { "local-$it" }, messageIds)
        assertEquals(messageIds.distinct(), messageIds)
        assertFalse(fixture.viewModel.state.value.hasMoreLocal)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMoreHistoryStopsAtMemoryLimitWithoutDroppingLoadedMessages() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(2_025)
        fixture.viewModel.start()
        runCurrent()

        repeat(100) {
            fixture.viewModel.loadMoreHistory()
        }

        val state = fixture.viewModel.state.value
        assertEquals(2_000, state.messages.size)
        assertEquals("local-2025", state.messages.first().messageId)
        assertEquals("local-26", state.messages.last().messageId)
        assertEquals(state.messages.map { it.messageId }.distinct(), state.messages.map { it.messageId })
        assertEquals(true, state.isHistoryMemoryLimitReached)
        assertEquals(true, state.hasMoreLocal)
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
    fun incomingPacketKeepsPreviouslyLoadedHistory() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(25)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.loadMoreHistory()

        fixture.connection.incoming.emit(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-latest",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"newest incoming",
                      "timestamp":30000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val messageIds = fixture.viewModel.state.value.messages.map { it.messageId }
        assertEquals("remote-latest", messageIds.first())
        assertEquals("local-1", messageIds.last())
        assertEquals(26, messageIds.size)
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
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"after stop",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(emptyList<String>(), fixture.viewModel.state.value.messages.map { it.content })
    }

    private class Fixture(scope: TestScope) {
        val connection = FakeConnection()
        private val messageDao = InMemoryMessageDao()
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, FakeProfileApi())
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
            profileRepository = profileRepository,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        fun seedMessages(count: Int) {
            repeat(count) { index ->
                val number = index + 1
                val createdAt = number * 1_000L
                messageDao.insertOrIgnore(
                    ChatMessage(
                        messageId = "local-$number",
                        conversationId = "single:13800113800:13900113900",
                        senderId = "13800113800",
                        receiverId = "13900113900",
                        clientSeq = number.toLong(),
                        serverSeq = null,
                        content = "message $number",
                        status = MessageStatus.SENT,
                        direction = MessageDirection.OUTGOING,
                        createdAt = createdAt,
                        updatedAt = createdAt
                    )
                )
            }
        }
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
