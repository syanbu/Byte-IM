package com.buyansong.im.conversation

import com.buyansong.im.R
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTopBarActionPolicyTest {
    @Test
    fun addActionUsesVectorDrawableResource() {
        assertEquals(R.drawable.ic_add_circle, MessageTopBarActionPolicy.addIconResId)
    }
}
