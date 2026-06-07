package com.buyansong.im.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarPlaceholderPolicyTest {
    @Test
    fun groupAvatarAlwaysUsesGroupPlaceholderText() {
        assertEquals("群", AvatarPlaceholderPolicy.text("ByteDance2", isGroup = true))
    }

    @Test
    fun singleAvatarStillUsesDisplayNameInitial() {
        assertEquals("B", AvatarPlaceholderPolicy.text("ByteDance2", isGroup = false))
    }
}
