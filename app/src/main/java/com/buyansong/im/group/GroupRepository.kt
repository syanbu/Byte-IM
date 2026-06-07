package com.buyansong.im.group

import com.buyansong.im.storage.Conversation
import com.buyansong.im.storage.ConversationDao
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupDao
import com.buyansong.im.storage.GroupInfo
import com.buyansong.im.storage.GroupMember

interface GroupRepository {
    suspend fun createGroup(
        accessToken: String,
        ownerId: String,
        name: String,
        memberUserIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): GroupCreateResult

    suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult

    suspend fun syncGroups(accessToken: String): List<GroupInfo>

    suspend fun syncMembers(accessToken: String, groupId: String): List<GroupMember>

    fun localMembers(groupId: String): List<GroupMember>

    fun localGroup(groupId: String): GroupInfo?

    fun joinedGroups(userId: String): List<GroupInfo>
}

class DefaultGroupRepository(
    private val groupApi: GroupApi,
    private val groupDao: GroupDao,
    private val conversationDao: ConversationDao
) : GroupRepository {
    override suspend fun createGroup(
        accessToken: String,
        ownerId: String,
        name: String,
        memberUserIds: List<String>,
        now: Long
    ): GroupCreateResult {
        val normalizedMemberUserIds = memberUserIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != ownerId }
            .distinct()
        if (normalizedMemberUserIds.isEmpty()) {
            return GroupCreateResult.Failure("请选择至少一个联系人")
        }
        return when (val result = groupApi.createGroup(accessToken, name, normalizedMemberUserIds)) {
            is GroupCreateResult.Success -> {
                persist(result, now)
                result
            }
            is GroupCreateResult.Failure -> result
        }
    }

    override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return GroupResult.Failure("群名称不能为空")
        }
        return when (val result = groupApi.renameGroup(accessToken, groupId, trimmedName)) {
            is GroupResult.Success -> {
                persistGroupInfo(result.group)
                result
            }
            is GroupResult.Failure -> result
        }
    }

    override suspend fun syncGroups(accessToken: String): List<GroupInfo> {
        return when (val result = groupApi.groups(accessToken)) {
            is GroupListResult.Success -> {
                result.groups.forEach(::persistGroupInfo)
                result.groups
            }
            is GroupListResult.Failure -> emptyList()
        }
    }

    override suspend fun syncMembers(accessToken: String, groupId: String): List<GroupMember> {
        return when (val result = groupApi.members(accessToken, groupId)) {
            is GroupMembersResult.Success -> {
                persistGroupInfo(result.group)
                groupDao.replaceMembers(result.group.groupId, result.members)
                result.members
            }
            is GroupMembersResult.Failure -> localMembers(groupId)
        }
    }

    override fun localMembers(groupId: String): List<GroupMember> = groupDao.members(groupId)

    override fun localGroup(groupId: String): GroupInfo? = groupDao.findGroup(groupId)

    override fun joinedGroups(userId: String): List<GroupInfo> = groupDao.groupsForUser(userId)

    private fun persist(result: GroupCreateResult.Success, now: Long) {
        val group = result.group
        val conversationId = "group:${group.groupId}"
        groupDao.upsertGroup(group)
        groupDao.replaceMembers(group.groupId, result.members)
        conversationDao.upsertConversation(
            Conversation(
                conversationId = conversationId,
                peerId = conversationId,
                peerName = group.name,
                type = ConversationType.GROUP,
                title = group.name,
                avatarUrl = group.avatarUrl,
                lastMessageId = null,
                lastMessagePreview = "已创建群聊",
                lastMessageTime = now,
                unreadCount = 0,
                mentionUnreadCount = 0,
                updatedAt = now
            )
        )
    }

    private fun persistGroupInfo(group: GroupInfo) {
        groupDao.upsertGroup(group)
        val conversationId = "group:${group.groupId}"
        val current = conversationDao.findConversation(conversationId)
        if (current != null) {
            conversationDao.upsertConversation(
                current.copy(
                    peerName = group.name,
                    title = group.name,
                    avatarUrl = group.avatarUrl,
                    updatedAt = group.updatedAt
                )
            )
        }
    }
}
