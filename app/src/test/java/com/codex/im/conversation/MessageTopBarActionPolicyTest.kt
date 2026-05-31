package com.codex.im.conversation

import com.codex.im.R
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTopBarActionPolicyTest {
    @Test
    fun addActionUsesVectorDrawableResource() {
        assertEquals(R.drawable.ic_add_circle, MessageTopBarActionPolicy.addIconResId)
    }
}
