package com.buyansong.im.chat

object ChatAutoScrollPolicy {
    private const val LOAD_EARLIER_THRESHOLD_ITEMS = 10
    private const val MAX_RETAINED_MESSAGES = 2_000

    enum class ScrollAction {
        NONE,
        PRE_MEASURE_ANCHOR_TO_LATEST,
        ANIMATE_TO_LATEST
    }

    sealed interface ImeExpansionAction {
        data object None : ImeExpansionAction
        data class ScrollByImeDelta(val deltaPx: Int) : ImeExpansionAction
        data object ScrollToLatest : ImeExpansionAction
    }

    fun scrollAction(previousLatestMessageId: String?, latestMessageId: String?): ScrollAction {
        return when {
            latestMessageId == null -> ScrollAction.NONE
            previousLatestMessageId == null -> ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST
            previousLatestMessageId == latestMessageId -> ScrollAction.NONE
            else -> ScrollAction.ANIMATE_TO_LATEST
        }
    }

    fun shouldScrollToLatest(previousLatestMessageId: String?, latestMessageId: String?): Boolean {
        return scrollAction(previousLatestMessageId, latestMessageId) != ScrollAction.NONE
    }

    /**
     * Returns the index the message `LazyColumn` should move to when
     * the caller has already determined (via [scrollAction]) that the
     * latest message should be brought into view.
     *
     * The chat list uses a *normal* top-to-bottom layout (item 0 = oldest
     * = visual top; last item = newest = visual bottom). The "scroll to
     * latest" target is therefore the last index of the list. The caller
     * is responsible for not invoking this when the list is empty — the
     * `LaunchedEffect` in `ChatScreen` checks [shouldScrollToLatest]
     * first, which returns `false` whenever `latestMessageId` is null.
     */
    fun scrollToLatestIndex(messageCount: Int): Int {
        // Defensive: if the list is empty, return 0; the caller's
        // `shouldScrollToLatest` guard should make this unreachable, but
        // returning 0 keeps `animateScrollToItem` safe even if it slips
        // through.
        if (messageCount <= 0) return 0
        return messageCount - 1
    }

    fun shouldAnchorLatestAfterImeExpansion(
        previousImeBottomPx: Int,
        currentImeBottomPx: Int,
        messageCount: Int,
        lastVisibleIndexBeforeImeChange: Int
    ): Boolean {
        if (messageCount <= 0) return false
        if (currentImeBottomPx <= previousImeBottomPx) return false
        val latestIndex = scrollToLatestIndex(messageCount)
        return lastVisibleIndexBeforeImeChange >= latestIndex
    }

    fun imeExpansionAction(
        previousImeBottomPx: Int,
        currentImeBottomPx: Int,
        messageCount: Int,
        lastVisibleIndexBeforeImeChange: Int,
        didScrollToLatestDuringImeExpansion: Boolean = false
    ): ImeExpansionAction {
        if (messageCount <= 0) return ImeExpansionAction.None
        if (currentImeBottomPx <= previousImeBottomPx) return ImeExpansionAction.None

        val latestIndex = scrollToLatestIndex(messageCount)
        return if (lastVisibleIndexBeforeImeChange >= latestIndex || didScrollToLatestDuringImeExpansion) {
            ImeExpansionAction.ScrollByImeDelta(currentImeBottomPx - previousImeBottomPx)
        } else if (previousImeBottomPx == 0) {
            ImeExpansionAction.ScrollToLatest
        } else {
            ImeExpansionAction.None
        }
    }

    fun imeExpansionScrollDeltaPx(
        previousImeBottomPx: Int,
        currentImeBottomPx: Int,
        messageCount: Int,
        lastVisibleIndexBeforeImeChange: Int
    ): Int {
        return when (
            val action = imeExpansionAction(
                previousImeBottomPx = previousImeBottomPx,
                currentImeBottomPx = currentImeBottomPx,
                messageCount = messageCount,
                lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeChange
            )
        ) {
            ImeExpansionAction.None -> 0
            is ImeExpansionAction.ScrollByImeDelta -> action.deltaPx
            ImeExpansionAction.ScrollToLatest -> 0
        }
    }

    fun bottomAlignmentScrollDeltaPx(
        itemOffsetPx: Int,
        itemSizePx: Int,
        viewportEndOffsetPx: Int
    ): Int {
        return (itemOffsetPx + itemSizePx - viewportEndOffsetPx).coerceAtLeast(0)
    }

    fun moreActionsExpansionScrollDeltaPx(
        panelHeightPx: Int,
        messageCount: Int,
        lastVisibleIndexBeforeExpansion: Int
    ): Int {
        if (panelHeightPx <= 0 || messageCount <= 0) return 0
        val latestIndex = scrollToLatestIndex(messageCount)
        if (lastVisibleIndexBeforeExpansion < latestIndex) return 0
        return panelHeightPx
    }

    fun shouldLoadEarlierHistory(
        firstVisibleItemIndex: Int,
        messageCount: Int,
        hasMoreLocal: Boolean,
        isLoadingMore: Boolean
    ): Boolean {
        if (messageCount == 0 || messageCount >= MAX_RETAINED_MESSAGES || !hasMoreLocal || isLoadingMore) {
            return false
        }
        return firstVisibleItemIndex <= LOAD_EARLIER_THRESHOLD_ITEMS
    }
}
