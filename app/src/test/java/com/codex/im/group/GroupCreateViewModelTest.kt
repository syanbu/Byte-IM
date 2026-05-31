package com.codex.im.group

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.contacts.DemoContactResolver
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageRepository
import com.codex.im.message.SeqGenerator
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCreateViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoadsContactsForSelection() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("13267100423", "13900113900", "17724734511"),
            fixture.viewModel.state.value.contacts.map { it.userId }
        )
        assertFalse(fixture.viewModel.state.value.canCreate)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun toggleContactSelectionEnablesCreation() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.toggleContact("13900113900")

        val item = fixture.viewModel.state.value.contacts.single { it.userId == "13900113900" }
        assertTrue(item.isSelected)
        assertTrue(fixture.viewModel.state.value.canCreate)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun createGroupCreatesLocalConversationAndExposesCompletion() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.toggleContact("13900113900")
        fixture.viewModel.toggleContact("17724734511")

        fixture.viewModel.createGroup(now = 1_000L)
        runCurrent()

        val conversation = fixture.repository.conversations(limit = 20).single()
        assertEquals("群聊(3)", conversation.peerName)
        assertEquals(conversation.conversationId, fixture.viewModel.state.value.createdConversationId)
    }

    private class Fixture(scope: TestScope) {
        private val profileRepository = ProfileRepository(InMemoryUserProfileDao(), FakeProfileApi())
        val repository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val viewModel = GroupCreateViewModel(
            session = AuthSession("mock-token", "13800113800", "13800113800", expiresAtMillis = 2_000L),
            profileRepository = profileRepository,
            messageRepository = repository,
            contactResolver = DemoContactResolver::contactsFor,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()

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
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}
