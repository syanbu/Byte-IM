package com.buyansong.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTabUnreadBadgePolicyTest {
    @Test
    fun hidesBadgeWhenUnreadCountIsZero() {
        assertNull(MessagesTabUnreadBadgePolicy.badgeTextForCount(0))
    }

    @Test
    fun showsActualCountWhenUnreadCountIsBelowHundred() {
        assertEquals("1", MessagesTabUnreadBadgePolicy.badgeTextForCount(1))
        assertEquals("99", MessagesTabUnreadBadgePolicy.badgeTextForCount(99))
    }

    @Test
    fun capsDisplayedBadgeTextAtNinetyNinePlus() {
        assertEquals("99+", MessagesTabUnreadBadgePolicy.badgeTextForCount(100))
        assertEquals("99+", MessagesTabUnreadBadgePolicy.badgeTextForCount(180))
    }
}
