package com.codex.im

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationSpecTest {
    @Test
    fun messagesTabUsesMessageVectorDrawable() {
        assertEquals(R.drawable.ic_nav_message, BottomNavigationSpec.messages.iconResId)
    }

    @Test
    fun meTabUsesMeVectorDrawable() {
        assertEquals(R.drawable.ic_nav_me, BottomNavigationSpec.me.iconResId)
    }
}
