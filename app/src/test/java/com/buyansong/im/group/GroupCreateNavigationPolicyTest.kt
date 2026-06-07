package com.buyansong.im.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupCreateNavigationPolicyTest {
    @Test
    fun createdGroupNavigatesDirectlyToGroupChatRoute() {
        assertEquals(
            "chat/group:g_1001",
            GroupCreateNavigationPolicy.destinationAfterCreated("group:g_1001")
        )
    }

    @Test
    fun blankCreatedConversationDoesNotNavigate() {
        assertNull(GroupCreateNavigationPolicy.destinationAfterCreated(" "))
    }
}
