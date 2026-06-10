package com.buyansong.im.chat

object ChatAutoScrollPolicy {
    private const val LOAD_EARLIER_THRESHOLD_ITEMS = 6
    private const val MAX_RETAINED_MESSAGES = 2_000

    fun shouldScrollToLatest(previousLatestMessageId: String?, latestMessageId: String?): Boolean {
        return latestMessageId != null && latestMessageId != previousLatestMessageId
    }

    /**
     * Returns the index the message `LazyColumn` should animate to when
     * a new message arrives and the caller has already determined (via
     * [shouldScrollToLatest]) that an auto-scroll is needed.
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

    fun shouldLoadEarlierHistory(
        visibleMaxIndex: Int,
        messageCount: Int,
        hasMoreLocal: Boolean,
        isLoadingMore: Boolean
    ): Boolean {
        if (messageCount == 0 || messageCount >= MAX_RETAINED_MESSAGES || !hasMoreLocal || isLoadingMore) {
            return false
        }
        val triggerIndex = maxOf(0, messageCount - LOAD_EARLIER_THRESHOLD_ITEMS)
        return visibleMaxIndex >= triggerIndex
    }
}
