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
    }

    @Test
    fun scrollAction_noLatest_returnsNone() {
        assertEquals(
            ChatAutoScrollPolicy.ScrollAction.NONE,
            ChatAutoScrollPolicy.scrollAction(
                previousLatestMessageId = null,
                latestMessageId = null
            )
        )
    }

    @Test
    fun scrollAction_firstLoadedLatest_returnsPreMeasureAnchor() {
        assertEquals(
            ChatAutoScrollPolicy.ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST,
            ChatAutoScrollPolicy.scrollAction(
                previousLatestMessageId = null,
                latestMessageId = "msg-9"
            )
        )
    }

    @Test
    fun scrollAction_sameLatest_returnsNone() {
        assertEquals(
            ChatAutoScrollPolicy.ScrollAction.NONE,
            ChatAutoScrollPolicy.scrollAction(
                previousLatestMessageId = "msg-9",
                latestMessageId = "msg-9"
            )
        )
    }

    @Test
    fun scrollAction_latestChangedAfterInitialLoad_returnsAnimate() {
        assertEquals(
            ChatAutoScrollPolicy.ScrollAction.ANIMATE_TO_LATEST,
            ChatAutoScrollPolicy.scrollAction(
                previousLatestMessageId = "msg-8",
                latestMessageId = "msg-9"
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

    @Test
    fun shouldAnchorLatestAfterImeExpansion_whenKeyboardExpandsAndUserWasAtBottom_returnsTrue() {
        assertTrue(
            ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 11
            )
        )
    }

    @Test
    fun shouldAnchorLatestAfterImeExpansion_whenKeyboardCollapses_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
                previousImeBottomPx = 720,
                currentImeBottomPx = 0,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 11
            )
        )
    }

    @Test
    fun shouldAnchorLatestAfterImeExpansion_whenUserReadsHistory_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 6
            )
        )
    }

    @Test
    fun shouldAnchorLatestAfterImeExpansion_whenNoMessages_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 0,
                lastVisibleIndexBeforeImeChange = -1
            )
        )
    }

    @Test
    fun imeExpansionScrollDelta_whenKeyboardExpandsAndUserWasAtBottom_returnsKeyboardDelta() {
        assertEquals(
            720,
            ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 11
            )
        )
    }

    @Test
    fun imeExpansionScrollDelta_whenKeyboardContinuesExpanding_returnsIncrementalDelta() {
        assertEquals(
            160,
            ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
                previousImeBottomPx = 560,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 11
            )
        )
    }

    @Test
    fun imeExpansionScrollDelta_whenUserReadsHistory_returnsZero() {
        assertEquals(
            0,
            ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 6
            )
        )
    }

    @Test
    fun moreActionsExpansionScrollDelta_whenPanelExpandsAndUserWasAtBottom_returnsPanelHeight() {
        assertEquals(
            240,
            ChatAutoScrollPolicy.moreActionsExpansionScrollDeltaPx(
                panelHeightPx = 240,
                messageCount = 12,
                lastVisibleIndexBeforeExpansion = 11
            )
        )
    }

    @Test
    fun moreActionsExpansionScrollDelta_whenUserReadsHistory_returnsZero() {
        assertEquals(
            0,
            ChatAutoScrollPolicy.moreActionsExpansionScrollDeltaPx(
                panelHeightPx = 240,
                messageCount = 12,
                lastVisibleIndexBeforeExpansion = 6
            )
        )
    }

    @Test
    fun moreActionsExpansionScrollDelta_whenPanelHasNoMeasuredHeight_returnsZero() {
        assertEquals(
            0,
            ChatAutoScrollPolicy.moreActionsExpansionScrollDeltaPx(
                panelHeightPx = 0,
                messageCount = 12,
                lastVisibleIndexBeforeExpansion = 11
            )
        )
    }

}
