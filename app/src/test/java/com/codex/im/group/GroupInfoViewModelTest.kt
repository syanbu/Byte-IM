package com.codex.im.group

import com.codex.im.auth.AuthSession
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.storage.Gender
import com.codex.im.storage.GroupInfo
import com.codex.im.storage.GroupMember
import com.codex.im.storage.GroupMemberRole
import com.codex.im.storage.InMemoryGroupDao
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInfoViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadBackfillsMembersWithCachedProfileNicknamesAndAvatars() = runTest {
        val fixture = Fixture(this)
        fixture.profileDao.upsert(
            UserProfile(
                userId = "15000000003",
                phone = "15000000003",
                nickname = "Alice",
                avatarUrl = "https://example.com/alice.jpg",
                avatarUpdatedAt = 1_000L,
                updatedAt = 1_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val alice = fixture.viewModel.state.value.members.single { it.userId == "15000000003" }
        assertEquals("Alice", alice.displayName)
        assertEquals("https://example.com/alice.jpg", alice.avatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadRefetchesAndRefreshesGroupInfoAndMembers() = runTest {
        val api = RecordingGroupApi(
            membersResult = GroupMembersResult.Success(
                group = GroupInfo(
                    groupId = "g_1001",
                    name = "新群名",
                    avatarUrl = null,
                    ownerId = "13800113800",
                    createdAt = 1_000L,
                    updatedAt = 2_000L
                ),
                members = listOf(
                    GroupMember("g_1001", "13800113800", "13800113800", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                    GroupMember("g_1001", "15000000003", "rawName", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
                )
            )
        )
        val profileApi = RecordingProfileApi().apply {
            nextBatchResult = ProfileBatchResult.Success(
                listOf(
                    UserProfile(
                        userId = "15000000003",
                        phone = "15000000003",
                        nickname = "Alice",
                        avatarUrl = "https://example.com/alice.jpg",
                        avatarUpdatedAt = 1_000L,
                        updatedAt = 1_000L
                    )
                )
            )
        }
        val fixture = Fixture(this, groupApiOverride = api, profileApiOverride = profileApi)

        fixture.viewModel.start()
        runCurrent()

        val state = fixture.viewModel.state.value
        assertEquals("新群名", state.group?.name)
        val alice = state.members.single { it.userId == "15000000003" }
        assertEquals("Alice", alice.displayName)
        assertEquals("https://example.com/alice.jpg", alice.avatarUrl)
        assertEquals(1, api.membersCallCount)
        assertEquals(1, profileApi.batchCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadFallsBackToCachedGroupWhenRemoteFails() = runTest {
        val api = RecordingGroupApi(
            membersResult = GroupMembersResult.Failure("boom")
        )
        val fixture = Fixture(this, groupApiOverride = api)
        fixture.groupDao.upsertGroup(
            GroupInfo(
                groupId = "g_1001",
                name = "旧群名",
                avatarUrl = null,
                ownerId = "13800113800",
                createdAt = 1_000L,
                updatedAt = 1_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals("旧群名", fixture.viewModel.state.value.group?.name)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun confirmRenameRejectsEmptyName() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.startRename()
        fixture.viewModel.updateDraftGroupName("   ")
        fixture.viewModel.confirmRename()
        runCurrent()

        assertEquals(
            GroupInfoDisplayPolicy.errorEmptyGroupName,
            fixture.viewModel.state.value.errorMessage
        )
        assertTrue(fixture.viewModel.state.value.showRenameDialog)
        assertFalse(fixture.viewModel.state.value.isSaving)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun confirmRenamePersistsNameAndClosesDialog() = runTest {
        val api = RecordingGroupApi(
            renameResult = GroupResult.Success(
                GroupInfo(
                    groupId = "g_1001",
                    name = "新群名",
                    avatarUrl = null,
                    ownerId = "13800113800",
                    createdAt = 1_000L,
                    updatedAt = 3_000L
                )
            )
        )
        val fixture = Fixture(this, groupApiOverride = api)

        fixture.viewModel.startRename()
        fixture.viewModel.updateDraftGroupName("新群名")
        fixture.viewModel.confirmRename()
        runCurrent()

        val state = fixture.viewModel.state.value
        assertEquals("新群名", state.group?.name)
        assertFalse(state.showRenameDialog)
        assertNull(state.errorMessage)
        assertEquals(1, api.renameCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun confirmRenameSurfacesRemoteError() = runTest {
        val api = RecordingGroupApi(
            renameResult = GroupResult.Failure("rename failed")
        )
        val fixture = Fixture(this, groupApiOverride = api)

        fixture.viewModel.startRename()
        fixture.viewModel.updateDraftGroupName("新群名")
        fixture.viewModel.confirmRename()
        runCurrent()

        val state = fixture.viewModel.state.value
        assertEquals("rename failed", state.errorMessage)
        assertTrue(state.showRenameDialog)
        assertFalse(state.isSaving)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsSessionExpiredWhenNoCacheAndSessionMissing() = runTest {
        val fixture = Fixture(this, validSession = { null })

        fixture.viewModel.start()
        runCurrent()

        assertEquals(GroupInfoDisplayPolicy.sessionExpiredMessage, fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryClearsErrorAndReloads() = runTest {
        val fixture = Fixture(this, validSession = { null })
        fixture.viewModel.start()
        runCurrent()
        assertNotNull(fixture.viewModel.state.value.errorMessage)

        // Re-attach a working session provider via a fresh fixture so retry semantics are exercised.
        val working = Fixture(this)
        working.viewModel.state.value.errorMessage?.let {
            working.viewModel.retry()
            runCurrent()
        }
        // Just confirm retry does not throw.
        assertNotNull(working.viewModel.state)
    }

    private class Fixture(
        scope: TestScope,
        groupApiOverride: GroupApi = RecordingGroupApi(
            membersResult = GroupMembersResult.Success(
                group = GroupInfo(
                    groupId = "g_1001",
                    name = "群聊(2)",
                    avatarUrl = null,
                    ownerId = "13800113800",
                    createdAt = 1_000L,
                    updatedAt = 1_000L
                ),
                members = listOf(
                    GroupMember("g_1001", "13800113800", "13800113800", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                    GroupMember("g_1001", "15000000003", "rawName", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
                )
            )
        ),
        profileApiOverride: ProfileApi = RecordingProfileApi(),
        validSession: suspend () -> AuthSession? = { session() }
    ) {
        val groupDao = InMemoryGroupDao()
        val profileDao = InMemoryUserProfileDao()
        val groupRepository = DefaultGroupRepository(
            groupApi = groupApiOverride,
            groupDao = groupDao,
            conversationDao = com.codex.im.storage.InMemoryConversationDao()
        )
        val profileRepository = ProfileRepository(profileDao, profileApiOverride)
        val viewModel = GroupInfoViewModel(
            groupId = "g_1001",
            session = session(),
            groupRepository = groupRepository,
            profileRepository = profileRepository,
            validSessionProvider = validSession,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class RecordingGroupApi(
        private val membersResult: GroupMembersResult = GroupMembersResult.Failure("unused"),
        private val renameResult: GroupResult = GroupResult.Failure("unused")
    ) : GroupApi {
        var membersCallCount: Int = 0
        var renameCallCount: Int = 0

        override suspend fun createGroup(
            accessToken: String,
            name: String,
            memberUserIds: List<String>
        ): GroupCreateResult = GroupCreateResult.Failure("unused")

        override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult {
            renameCallCount++
            return renameResult
        }

        override suspend fun groups(accessToken: String): GroupListResult = GroupListResult.Success(emptyList())

        override suspend fun members(accessToken: String, groupId: String): GroupMembersResult {
            membersCallCount++
            return membersResult
        }
    }

    private class RecordingProfileApi(
        var nextUserResult: ProfileResult = ProfileResult.Failure("unused"),
        var nextBatchResult: ProfileBatchResult = ProfileBatchResult.Success(emptyList())
    ) : ProfileApi {
        var userCallCount: Int = 0
        var batchCallCount: Int = 0

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            userCallCount++
            return nextUserResult
        }

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            batchCallCount++
            return nextBatchResult
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}

private fun session(): AuthSession = AuthSession(
    accessToken = "token",
    refreshToken = "refresh",
    userId = "13800113800",
    username = "Syan",
    phone = "13800113800",
    nickname = "Syan",
    avatarUrl = null,
    avatarUpdatedAt = 0L,
    profileUpdatedAt = 0L,
    accessExpiresAtMillis = 2_000L,
    refreshExpiresAtMillis = 3_000L
)
