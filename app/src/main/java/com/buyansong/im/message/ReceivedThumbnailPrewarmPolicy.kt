package com.buyansong.im.message

object ReceivedThumbnailPrewarmPolicy {
    fun shouldPrewarm(
        messageConversationId: String,
        activeConversationId: String?,
        alreadyPrewarmedInDrain: Int,
        maxPrewarmPerDrain: Int
    ): Boolean {
        if (activeConversationId == null) {
            return false
        }
        if (messageConversationId != activeConversationId) {
            return false
        }
        return alreadyPrewarmedInDrain < maxPrewarmPerDrain.coerceAtLeast(0)
    }
}
