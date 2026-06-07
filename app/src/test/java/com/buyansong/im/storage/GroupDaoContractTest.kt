package com.buyansong.im.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupDaoContractTest {
    @Test
    fun upsertGroupAndMembersCanBeReadBack() {
        val dao = InMemoryGroupDao()
        val group = GroupInfo(
            groupId = "g_1001",
            name = "产品群",
            avatarUrl = null,
            ownerId = "13800113800",
            createdAt = 1_000L,
            updatedAt = 1_000L
        )
        val members = listOf(
            GroupMember("g_1001", "13800113800", "我", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
            GroupMember("g_1001", "13900113900", "张三", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
        )

        dao.upsertGroup(group)
        dao.replaceMembers("g_1001", members)

        assertEquals(group, dao.findGroup("g_1001"))
        assertEquals(members, dao.members("g_1001"))
        assertEquals(listOf("g_1001"), dao.groupIdsForUser("13900113900"))
    }

    @Test
    fun groupsForUserReturnsAllCachedGroupsSortedByUpdatedAtDesc() {
        val dao = InMemoryGroupDao()
        dao.upsertGroup(GroupInfo("g_1", "群1", null, "alice", 1_000L, 1_000L, memberCount = 2))
        dao.upsertGroup(GroupInfo("g_2", "群2", null, "bob", 1_000L, 2_000L, memberCount = 1))
        dao.upsertGroup(GroupInfo("g_3", "群3", null, "carol", 1_000L, 3_000L, memberCount = 1))
        // 不设置 group_members —— groups 表本身就是"我作为成员"的本地缓存

        val result = dao.groupsForUser("bob")

        assertEquals(listOf("g_3", "g_2", "g_1"), result.map { it.groupId })
    }

    @Test
    fun groupsForUserDoesNotRequireGroupMembersRow() {
        val dao = InMemoryGroupDao()
        // 群已写入 groups,但 group_members 表里完全没有数据
        dao.upsertGroup(
            GroupInfo("g_x", "远端知道但本地未 syncMembers", null, "13800113800", 1_000L, 2_000L, memberCount = 3)
        )

        val result = dao.groupsForUser("13800113800")

        assertEquals(1, result.size)
        assertEquals("g_x", result.single().groupId)
    }
}
