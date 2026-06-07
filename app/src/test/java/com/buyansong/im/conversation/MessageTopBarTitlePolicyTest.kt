package com.buyansong.im.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTopBarTitlePolicyTest {
    @Test
    fun hidesUnreadCountWhenThereAreNoUnreadMessages() {
        assertEquals("消息", MessageTopBarTitlePolicy.titleForUnreadCount(0))
    }

    @Test
    fun showsUnreadCountInTitle() {
        assertEquals("消息(18)", MessageTopBarTitlePolicy.titleForUnreadCount(18))
    }

    @Test
    fun capsUnreadCountLikeBottomNavigationBadge() {
        assertEquals("消息(99+)", MessageTopBarTitlePolicy.titleForUnreadCount(100))
    }
}
