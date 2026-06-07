package com.codex.im.storage

interface GroupDao {
    fun upsertGroup(group: GroupInfo)

    fun replaceMembers(groupId: String, members: List<GroupMember>)

    fun findGroup(groupId: String): GroupInfo?

    fun members(groupId: String): List<GroupMember>

    fun groupIdsForUser(userId: String): List<String>

    /**
     * 返回"我作为成员加入的所有群"的本地缓存视图。
     *
     * 实现只读 [groups] 表，不与 [group_members] JOIN —— 两者概念不同：
     * - [groups] 是"我作为成员加入的群"的本地缓存（[upsertGroup] 只由 syncGroups / createGroup 调用，
     *   两者写入前都已确认"我是成员"或"我是 owner"），因此该表里的每一行都意味着我是成员。
     * - [group_members] 是每个群的成员名册（用于 @ 选择器、群成员列表等），与"我是否在该群"无关，
     *   且不会被 syncGroups 同步，所以拿来做 JOIN 过滤会漏显示远端知道但本地未 syncMembers 的群。
     *
     * 每账号独立 DB（[com.codex.im.storage.AccountScopedDatabaseName.forUser]），全表即"我"的视图。
     * [userId] 参数为接口兼容保留，目前未使用。
     */
    fun groupsForUser(userId: String): List<GroupInfo>
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

    override fun groupsForUser(userId: String): List<GroupInfo> {
        return groups.values.sortedByDescending { it.updatedAt }
    }
}
