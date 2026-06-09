package com.buyansong.im.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationListViewModelTest {

    @Test
    fun normalizeConversationListScrollPosition_clampsNegativeValues() {
        val normalized = normalizeConversationListScrollPosition(
            firstVisibleItemIndex = -3,
            firstVisibleItemScrollOffset = -40
        )

        assertEquals(0, normalized.firstVisibleItemIndex)
        assertEquals(0, normalized.firstVisibleItemScrollOffset)
    }

    @Test
    fun normalizeConversationListScrollPosition_keepsPositiveValues() {
        val normalized = normalizeConversationListScrollPosition(
            firstVisibleItemIndex = 18,
            firstVisibleItemScrollOffset = 96
        )

        assertEquals(18, normalized.firstVisibleItemIndex)
        assertEquals(96, normalized.firstVisibleItemScrollOffset)
    }
}
