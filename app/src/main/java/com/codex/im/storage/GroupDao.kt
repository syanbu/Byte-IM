package com.codex.im.storage

interface GroupDao {
    fun upsertGroup(group: GroupInfo)

    fun replaceMembers(groupId: String, members: List<GroupMember>)

    fun findGroup(groupId: String): GroupInfo?

    fun members(groupId: String): List<GroupMember>

    fun groupIdsForUser(userId: String): List<String>
}

class InMemoryGroupDao : GroupDao {
    private val groups = linkedMapOf<String, GroupInfo>()
    private val membersByGroup = linkedMapOf<String, List<GroupMember>>()

    override fun upsertGroup(group: GroupInfo) {
        groups[group.groupId] = group
    }

    override fun replaceMembers(groupId: String, members: List<GroupMember>) {
        membersByGroup[groupId] = members
    }

    override fun findGroup(groupId: String): GroupInfo? = groups[groupId]

    override fun members(groupId: String): List<GroupMember> = membersByGroup[groupId].orEmpty()

    override fun groupIdsForUser(userId: String): List<String> {
        return membersByGroup
            .filterValues { members -> members.any { it.userId == userId } }
            .keys
            .sorted()
    }
}
