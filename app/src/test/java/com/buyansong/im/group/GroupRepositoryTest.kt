package com.buyansong.im.group

import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupInfo
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryGroupDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRepositoryTest {
    @Test
    fun createGroupPersistsGroupMembersAndConversation() = kotlinx.coroutines.test.runTest {
        val groupDao = InMemoryGroupDao()
        val conversationDao = InMemoryConversationDao()
        val repository = DefaultGroupRepository(
            groupApi = FakeGroupApi(
                GroupCreateResult.Success(
                    group = GroupInfo(
                        groupId = "g_1001",
                        name = "群聊(3)",
                        avatarUrl = null,
                        ownerId = "13800113800",
                        createdAt = 1_000L,
                        updatedAt = 1_000L
                    ),
                    members = listOf(
                        GroupMember("g_1001", "13800113800", "13800113800", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                        GroupMember("g_1001", "13900113900", "13900113900", null, GroupMemberRole.MEMBER, 1_000L, 1_000L),
                        GroupMember("g_1001", "13700113700", "13700113700", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
                    )
                )
            ),
            groupDao = groupDao,
            conversationDao = conversationDao
        )

        val result = repository.createGroup(
            accessToken = "token",
            ownerId = "13800113800",
            name = "群聊(3)",
            memberUserIds = listOf("13900113900", "13700113700"),
            now = 2_000L
        )

        assertTrue(result is GroupCreateResult.Success)
        assertEquals("g_1001", groupDao.findGroup("g_1001")?.groupId)
        assertEquals(
            listOf("13800113800", "13900113900", "13700113700"),
            groupDao.members("g_1001").map { it.userId }
        )
        val conversation = conversationDao.listConversations(limit = 20).single()
        assertEquals("group:g_1001", conversation.conversationId)
        assertEquals(ConversationType.GROUP, conversation.type)
        assertEquals("群聊(3)", conversation.title)
        assertEquals("已创建群聊", conversation.lastMessagePreview)
    }

    @Test
    fun renameGroupUpdatesLocalGroupAndConversationTitle() = kotlinx.coroutines.test.runTest {
        val groupDao = InMemoryGroupDao()
        val conversationDao = InMemoryConversationDao()
        val repository = DefaultGroupRepository(
            groupApi = FakeGroupApi(
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
            ),
            groupDao = groupDao,
            conversationDao = conversationDao
        )
        groupDao.upsertGroup(GroupInfo("g_1001", "旧群名", null, "13800113800", 1_000L, 1_000L))
        conversationDao.upsertConversation(
            com.buyansong.im.storage.Conversation(
                conversationId = "group:g_1001",
                peerId = "group:g_1001",
                peerName = "旧群名",
                type = ConversationType.GROUP,
                title = "旧群名",
                avatarUrl = null,
                lastMessageId = null,
                lastMessagePreview = "hello",
                lastMessageTime = 2_000L,
                unreadCount = 0,
                updatedAt = 2_000L
            )
        )

        val result = repository.renameGroup("token", "g_1001", "新群名")

        assertTrue(result is GroupResult.Success)
        assertEquals("新群名", groupDao.findGroup("g_1001")?.name)
        val conversation = conversationDao.findConversation("group:g_1001")
        assertEquals("新群名", conversation?.title)
        assertEquals("新群名", conversation?.peerName)
        assertEquals("hello", conversation?.lastMessagePreview)
    }

    @Test
    fun syncMembersPersistsRemoteMembers() = kotlinx.coroutines.test.runTest {
        val groupDao = InMemoryGroupDao()
        val repository = DefaultGroupRepository(
            groupApi = FakeGroupApi(
                membersResult = GroupMembersResult.Success(
                    group = GroupInfo("g_1001", "群聊(2)", null, "13800113800", 1_000L, 1_000L),
                    members = listOf(
                        GroupMember("g_1001", "13800113800", "13800113800", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                        GroupMember("g_1001", "13900113900", "ByteDance2", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
                    )
                )
            ),
            groupDao = groupDao,
            conversationDao = InMemoryConversationDao()
        )

        val members = repository.syncMembers("token", "g_1001")

        assertEquals(listOf("13800113800", "13900113900"), members.map { it.userId })
        assertEquals("ByteDance2", groupDao.members("g_1001").single { it.userId == "13900113900" }.displayName)
    }

    private class FakeGroupApi(
        private val result: GroupCreateResult = GroupCreateResult.Failure("not configured"),
        private val renameResult: GroupResult = GroupResult.Failure("not configured"),
        private val membersResult: GroupMembersResult = GroupMembersResult.Failure("not configured")
    ) : GroupApi {
        override suspend fun createGroup(
            accessToken: String,
            name: String,
            memberUserIds: List<String>
        ): GroupCreateResult = result

        override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult = renameResult

        override suspend fun groups(accessToken: String): GroupListResult = GroupListResult.Success(emptyList())

        override suspend fun members(accessToken: String, groupId: String): GroupMembersResult = membersResult
    }
}
