package com.buyansong.im.conversation

object ConversationListLoadMorePolicy {
    private const val LOAD_MORE_THRESHOLD_ITEMS = 10

    fun shouldLoadMore(
        visibleLastIndex: Int,
        itemCount: Int,
        hasMore: Boolean,
        isLoadingMore: Boolean
    ): Boolean {
        if (itemCount == 0 || !hasMore || isLoadingMore) {
            return false
        }
        val triggerIndex = maxOf(0, itemCount - LOAD_MORE_THRESHOLD_ITEMS)
        return visibleLastIndex >= triggerIndex
    }
}
