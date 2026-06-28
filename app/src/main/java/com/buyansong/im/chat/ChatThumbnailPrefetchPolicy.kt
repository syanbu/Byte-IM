package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage

object ChatThumbnailPrefetchPolicy {
    fun pathsForIdleViewport(
        messages: List<ChatMessage>,
        visibleMinIndex: Int,
        visibleMaxIndex: Int,
        isScrollInProgress: Boolean,
        alreadyPrefetchedPaths: Set<String>,
        margin: Int,
        maxImages: Int
    ): List<String> {
        if (isScrollInProgress) {
            return emptyList()
        }
        return ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
            messages = messages,
            visibleMinIndex = visibleMinIndex,
            visibleMaxIndex = visibleMaxIndex,
            margin = margin,
            maxImages = maxImages
        ).filterNot { it in alreadyPrefetchedPaths }
    }
}
