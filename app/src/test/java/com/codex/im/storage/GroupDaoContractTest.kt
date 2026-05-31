package com.codex.im.storage

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
}
