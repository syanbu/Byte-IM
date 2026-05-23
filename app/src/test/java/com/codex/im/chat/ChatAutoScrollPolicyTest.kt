package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutoScrollPolicyTest {
    @Test
    fun scrollsWhenLatestMessageChanges() {
        assertTrue(ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId = "m1", latestMessageId = "m2"))
    }

    @Test
    fun doesNotScrollWhenOnlyEarlierHistoryIsAdded() {
        assertFalse(ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId = "m2", latestMessageId = "m2"))
    }

    @Test
    fun doesNotScrollWhenThereAreNoMessages() {
        assertFalse(ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId = null, latestMessageId = null))
    }

    @Test
    fun loadsEarlierHistoryWhenVisibleItemsReachOlderMessageEnd() {
        assertTrue(
            ChatAutoScrollPolicy.shouldLoadEarlierHistory(
                visibleMaxIndex = 14,
                messageCount = 20,
                hasMoreLocal = true,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun doesNotLoadEarlierHistoryBeforeUserScrollsNearOlderEnd() {
        assertFalse(
            ChatAutoScrollPolicy.shouldLoadEarlierHistory(
                visibleMaxIndex = 13,
                messageCount = 20,
                hasMoreLocal = true,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun doesNotLoadEarlierHistoryWhileLoadingOrAfterLocalEnd() {
        assertFalse(
            ChatAutoScrollPolicy.shouldLoadEarlierHistory(
                visibleMaxIndex = 19,
                messageCount = 20,
                hasMoreLocal = true,
                isLoadingMore = true
            )
        )
        assertFalse(
            ChatAutoScrollPolicy.shouldLoadEarlierHistory(
                visibleMaxIndex = 19,
                messageCount = 20,
                hasMoreLocal = false,
                isLoadingMore = false
            )
        )
    }

    @Test
    fun doesNotLoadEarlierHistoryAtMemoryLimit() {
        assertFalse(
            ChatAutoScrollPolicy.shouldLoadEarlierHistory(
                visibleMaxIndex = 1_999,
                messageCount = 2_000,
                hasMoreLocal = true,
                isLoadingMore = false
            )
        )
    }
}
