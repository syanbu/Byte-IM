package com.buyansong.im.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationListLoadMorePolicyTest {
    @Test
    fun loadsMoreWhenVisibleItemsReachOlderEnd() {
        assertTrue(
            ConversationListLoadMorePolicy.shouldLoadMore(
                visibleLastIndex = 40,
                itemCount = 50,
                hasMore = true,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun doesNotLoadMoreBeforeUserScrollsNearOlderEnd() {
        assertFalse(
            ConversationListLoadMorePolicy.shouldLoadMore(
                visibleLastIndex = 39,
                itemCount = 50,
                hasMore = true,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun doesNotLoadMoreWhileLoadingOrAfterEnd() {
        assertFalse(
            ConversationListLoadMorePolicy.shouldLoadMore(
                visibleLastIndex = 49,
                itemCount = 50,
                hasMore = true,
                isLoadingMore = true
            )
        )
        assertFalse(
            ConversationListLoadMorePolicy.shouldLoadMore(
                visibleLastIndex = 49,
                itemCount = 50,
                hasMore = false,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun doesNotLoadMoreOnEmptyList() {
        assertFalse(
            ConversationListLoadMorePolicy.shouldLoadMore(
                visibleLastIndex = -1,
                itemCount = 0,
                hasMore = true,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun triggersWhenListIsShorterThanThreshold() {
        assertTrue(
            ConversationListLoadMorePolicy.shouldLoadMore(
                visibleLastIndex = 0,
                itemCount = 5,
                hasMore = true,
                isLoadingMore = false
            )
        )
    }
}
