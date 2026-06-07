package com.buyansong.im.chat

object ChatAutoScrollPolicy {
    private const val LOAD_EARLIER_THRESHOLD_ITEMS = 6
    private const val MAX_RETAINED_MESSAGES = 2_000

    fun shouldScrollToLatest(previousLatestMessageId: String?, latestMessageId: String?): Boolean {
        return latestMessageId != null && latestMessageId != previousLatestMessageId
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
