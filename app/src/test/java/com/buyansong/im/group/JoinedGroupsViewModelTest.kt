package com.buyansong.im.group

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.storage.GroupInfo
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryGroupDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JoinedGroupsViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun localCacheRendersBeforeSyncReturns() = runTest {
        val fixture = Fixture(this)
        val dao = fixture.groupDao
        dao.upsertGroup(
            GroupInfo("g_1", "产品群", null, "13800113800", 1_000L, 1_000L, memberCount = 3)
        )
        dao.replaceMembers(
            "g_1",
            listOf(
                GroupMember("g_1", "13800113800", "我", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                GroupMember("g_1", "13900113900", "Alice", null, GroupMemberRole.MEMBER, 1_000L, 1_000L),
                GroupMember("g_1", "13700113700", "Bob", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
            )
        )
        dao.upsertGroup(
            GroupInfo("g_2", "前端群", null, "13800113800", 500L, 1_500L, memberCount = 2)
        )
        dao.replaceMembers(
            "g_2",
            listOf(
                GroupMember("g_2", "13800113800", "我", null, GroupMemberRole.OWNER, 500L, 1_500L),
                GroupMember("g_2", "13900113900", "Alice", null, GroupMemberRole.MEMBER, 500L, 1_500L)
            )
        )
        fixture.fakeApi.groupsResult = GroupListResult.Failure("network")

        fixture.viewModel.start()
        runCurrent()

        val items = fixture.viewModel.state.value.items
        assertEquals(2, items.size)
        // updatedAt DESC: g_2 (1500) then g_1 (1000)
        assertEquals(listOf("g_2", "g_1"), items.map { it.groupId })
        assertEquals(listOf(2, 3), items.map { it.memberCount })
        assertFalse(fixture.viewModel.state.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun syncAddsRemoteGroupsToList() = runTest {
        val fixture = Fixture(this)
        fixture.fakeApi.groupsResult = GroupListResult.Success(
            listOf(
                GroupInfo("g_1", "远端群A", null, "13800113800", 1_000L, 2_000L, memberCount = 3),
                GroupInfo("g_2", "远端群B", null, "13800113800", 1_000L, 1_500L, memberCount = 3)
            )
        )
        // 标记当前用户为成员,这样 groupsForUser 能命中
        fixture.groupDao.replaceMembers(
            "g_1",
            listOf(
                GroupMember("g_1", "13800113800", "我", null, GroupMemberRole.OWNER, 1_000L, 2_000L),
                GroupMember("g_1", "13900113900", "Alice", null, GroupMemberRole.MEMBER, 1_000L, 2_000L),
                GroupMember("g_1", "13700113700", "Bob", null, GroupMemberRole.MEMBER, 1_000L, 2_000L)
            )
        )
        fixture.groupDao.replaceMembers(
            "g_2",
            listOf(
                GroupMember("g_2", "13800113800", "我", null, GroupMemberRole.OWNER, 1_000L, 1_500L),
                GroupMember("g_2", "13900113900", "Alice", null, GroupMemberRole.MEMBER, 1_000L, 1_500L),
                GroupMember("g_2", "13700113700", "Bob", null, GroupMemberRole.MEMBER, 1_000L, 1_500L)
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val items = fixture.viewModel.state.value.items
        assertEquals(2, items.size)
        assertEquals(listOf("g_1", "g_2"), items.map { it.groupId })
        assertEquals(listOf(3, 3), items.map { it.memberCount })
        assertEquals("远端群A", items[0].name)
        assertFalse(fixture.viewModel.state.value.isLoading)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun syncFailureKeepsLocalCache() = runTest {
        val fixture = Fixture(this)
        val dao = fixture.groupDao
        dao.upsertGroup(
            GroupInfo("g_local", "本地群", null, "13800113800", 1_000L, 1_000L, memberCount = 2)
        )
        dao.replaceMembers(
            "g_local",
            listOf(
                GroupMember("g_local", "13800113800", "我", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                GroupMember("g_local", "13900113900", "Alice", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
            )
        )
        fixture.fakeApi.groupsResult = GroupListResult.Failure("network")

        fixture.viewModel.start()
        runCurrent()

        val items = fixture.viewModel.state.value.items
        assertEquals(1, items.size)
        assertEquals("g_local", items[0].groupId)
        assertEquals("本地群", items[0].name)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupsInGroupsTableShowEvenWithoutGroupMembersRow() = runTest {
        // 回归用例:修复前 group_members 未同步时,远端知道的群会被 JOIN 过滤掉,UI 显示空。
        // 修复后 groups 表里的行就是"我是成员"的本地缓存,直接读出来即可。
        val fixture = Fixture(this)
        val dao = fixture.groupDao
        dao.upsertGroup(
            GroupInfo("g_remote_only", "远端加入的群", null, "13800113800", 1_000L, 2_000L, memberCount = 3)
        )
        // 注意:没有调 dao.replaceMembers(...) —— 模拟"syncGroups 写入了 groups,但 group_members 未同步"
        fixture.fakeApi.groupsResult = GroupListResult.Failure("network")

        fixture.viewModel.start()
        runCurrent()

        val items = fixture.viewModel.state.value.items
        assertEquals(1, items.size)
        assertEquals("g_remote_only", items[0].groupId)
        assertEquals("远端加入的群", items[0].name)
        assertEquals(3, items[0].memberCount)
    }

    private class Fixture(
        scope: TestScope
    ) {
        val groupDao = InMemoryGroupDao()
        val conversationDao = InMemoryConversationDao()
        val fakeApi = FakeGroupApi()
        val repository = DefaultGroupRepository(
            groupApi = fakeApi,
            groupDao = groupDao,
            conversationDao = conversationDao
        )
        val viewModel = JoinedGroupsViewModel(
            session = AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L),
            groupRepository = repository,
            validSessionProvider = { AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L) },
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeGroupApi(
        var groupsResult: GroupListResult = GroupListResult.Success(emptyList())
    ) : GroupApi {
        override suspend fun createGroup(
            accessToken: String,
            name: String,
            memberUserIds: List<String>
        ): GroupCreateResult = GroupCreateResult.Failure("not configured")

        override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult =
            GroupResult.Failure("not configured")

        override suspend fun groups(accessToken: String): GroupListResult = groupsResult

        override suspend fun members(accessToken: String, groupId: String): GroupMembersResult =
            GroupMembersResult.Failure("not configured")
    }
}
