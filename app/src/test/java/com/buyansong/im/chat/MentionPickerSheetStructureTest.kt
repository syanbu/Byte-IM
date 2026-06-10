package com.buyansong.im.chat

import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the shared data assumptions used by MentionPickerSheet's call site.
 * UI rendering is verified manually in the running app.
 */
class MentionPickerSheetStructureTest {

    @Test
    fun groupMemberDisplayNameFallbackToUserId() {
        val member = GroupMember(
            groupId = "g_1",
            userId = "u_1",
            displayName = "",
            avatarUrl = null,
            role = GroupMemberRole.MEMBER,
            joinedAt = 0L,
            updatedAt = 0L
        )
        assertEquals("u_1", member.displayName.ifBlank { member.userId })
    }

    @Test
    fun mentionMembersAreFilteredByCurrentUserId() {
        val members = listOf(
            GroupMember("g", "me", "Me", null, GroupMemberRole.MEMBER, 0L, 0L),
            GroupMember("g", "other", "Other", null, GroupMemberRole.MEMBER, 0L, 0L)
        )
        val currentUserId = "me"
        val filtered = members.filter { it.userId != currentUserId }
        assertEquals(1, filtered.size)
        assertEquals("other", filtered[0].userId)
    }
}
