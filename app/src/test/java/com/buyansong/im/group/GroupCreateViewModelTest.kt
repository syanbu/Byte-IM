package com.buyansong.im.group

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.contacts.ContactApi
import com.buyansong.im.contacts.ContactIdsResult
import com.buyansong.im.contacts.ContactRepository
import com.buyansong.im.message.MessageIdGenerator
import com.buyansong.im.message.MessageRepository
import com.buyansong.im.message.SeqGenerator
import com.buyansong.im.profile.ProfileApi
import com.buyansong.im.profile.ProfileBatchResult
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.profile.ProfileResult
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.GroupInfo
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.InMemoryUserProfileDao
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
            listOf("15000000003", "15000000004"),
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

        fixture.viewModel.toggleContact("15000000003")

        val item = fixture.viewModel.state.value.contacts.single { it.userId == "15000000003" }
        assertTrue(item.isSelected)
        assertTrue(fixture.viewModel.state.value.canCreate)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun createGroupCreatesServerBackedConversationAndExposesCompletion() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.toggleContact("15000000003")
        fixture.viewModel.toggleContact("15000000004")

        fixture.viewModel.createGroup(now = 1_000L)
        runCurrent()

        val conversation = fixture.messageRepository.conversations(limit = 20).single()
        assertEquals(listOf("15000000003", "15000000004"), fixture.groupRepository.createdMemberUserIds)
        assertEquals("群聊(3)", conversation.peerName)
        assertEquals("group:g_1001", conversation.conversationId)
        assertEquals(conversation.conversationId, fixture.viewModel.state.value.createdConversationId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun createGroupWithoutValidSessionDoesNotCreateLocalConversation() = runTest {
        val fixture = Fixture(this, validSessionProvider = { null })
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.toggleContact("15000000003")

        fixture.viewModel.createGroup(now = 1_000L)
        runCurrent()

        assertTrue(fixture.messageRepository.conversations(limit = 20).isEmpty())
        assertEquals(null, fixture.viewModel.state.value.createdConversationId)
    }

    private class Fixture(
        scope: TestScope,
        validSessionProvider: suspend () -> AuthSession? = {
            AuthSession("fresh-token", "13800113800", "13800113800", expiresAtMillis = 3_000L)
        }
    ) {
        private val profileRepository = ProfileRepository(InMemoryUserProfileDao(), FakeProfileApi())
        private val contactRepository = ContactRepository(FakeContactApi(listOf("15000000003", "15000000004")))
        val messageRepository = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val groupRepository = FakeGroupRepository(messageRepository)
        val viewModel = GroupCreateViewModel(
            session = AuthSession("mock-token", "13800113800", "13800113800", expiresAtMillis = 2_000L),
            profileRepository = profileRepository,
            groupRepository = groupRepository,
            contactRepository = contactRepository,
            validSessionProvider = validSessionProvider,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )

    }

    private class FakeGroupRepository(
        private val messageRepository: MessageRepository
    ) : GroupRepository {
        var createdMemberUserIds: List<String> = emptyList()

        override suspend fun createGroup(
            accessToken: String,
            ownerId: String,
            name: String,
            memberUserIds: List<String>,
            now: Long
        ): GroupCreateResult {
            createdMemberUserIds = memberUserIds
            val group = GroupInfo(
                groupId = "g_1001",
                name = name,
                avatarUrl = null,
                ownerId = ownerId,
                createdAt = now,
                updatedAt = now
            )
            val members = listOf(
                GroupMember("g_1001", ownerId, ownerId, null, GroupMemberRole.OWNER, now, now)
            ) + memberUserIds.map {
                GroupMember("g_1001", it, it, null, GroupMemberRole.MEMBER, now, now)
            }
            val result = GroupCreateResult.Success(group, members)
            messageRepository.persistServerGroup(result, now)
            return result
        }

        override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult {
            return GroupResult.Failure("not supported")
        }

        override suspend fun syncGroups(accessToken: String): List<GroupInfo> {
            return emptyList()
        }

        override suspend fun syncMembers(accessToken: String, groupId: String): List<GroupMember> {
            return emptyList()
        }

        override fun localMembers(groupId: String): List<GroupMember> {
            return emptyList()
        }

        override fun localGroup(groupId: String): GroupInfo? {
            return null
        }

        override fun joinedGroups(userId: String): List<GroupInfo> {
            return emptyList()
        }
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
            avatarObjectKey: String?,
            gender: com.buyansong.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    private class FakeContactApi(private val friendUserIds: List<String>) : ContactApi {
        override suspend fun friends(accessToken: String): ContactIdsResult {
            return ContactIdsResult.Success(friendUserIds)
        }
    }
}
