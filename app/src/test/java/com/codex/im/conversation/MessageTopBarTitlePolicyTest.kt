package com.codex.im.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTopBarTitlePolicyTest {
    @Test
    fun hidesUnreadCountWhenThereAreNoUnreadMessages() {
        assertEquals("Message", MessageTopBarTitlePolicy.titleForUnreadCount(0))
    }

    @Test
    fun showsUnreadCountInTitle() {
        assertEquals("Message(18)", MessageTopBarTitlePolicy.titleForUnreadCount(18))
    }

    @Test
    fun capsUnreadCountLikeBottomNavigationBadge() {
        assertEquals("Message(99+)", MessageTopBarTitlePolicy.titleForUnreadCount(100))
    }
}
