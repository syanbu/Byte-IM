package com.buyansong.im

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBackPolicyTest {
    @Test
    fun backNavigatesWithoutClosingConversationInTheClickFrame() {
        val events = mutableListOf<String>()

        ChatBackPolicy.run(
            navigateBack = { events += "navigate" }
        )

        assertEquals(listOf("navigate"), events)
    }
}
