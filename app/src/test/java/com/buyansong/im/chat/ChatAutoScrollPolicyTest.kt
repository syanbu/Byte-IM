package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutoScrollPolicyTest {

    @Test
    fun shouldScrollToLatest_noLatest_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = null,
                latestMessageId = null
            )
        )
    }

    @Test
    fun shouldScrollToLatest_sameLatest_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = "msg-1",
                latestMessageId = "msg-1"
            )
        )
    }

    @Test
    fun shouldScrollToLatest_newLatest_returnsTrue() {
        assertTrue(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = "msg-1",
                latestMessageId = "msg-2"
            )
        )
        assertTrue(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = null,
                latestMessageId = "msg-1"
            )
        )
    }

    @Test
    fun scrollToLatestIndex_emptyList_returnsZero() {
        assertEquals(0, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 0))
    }

    @Test
    fun scrollToLatestIndex_singleMessage_returnsZero() {
        assertEquals(0, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 1))
    }

    @Test
    fun scrollToLatestIndex_multipleMessages_returnsLastIndex() {
        assertEquals(1, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 2))
        assertEquals(9, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 10))
    }
}
