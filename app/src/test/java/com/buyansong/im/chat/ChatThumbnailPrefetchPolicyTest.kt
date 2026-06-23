package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatThumbnailPrefetchPolicyTest {

    @Test
    fun pathsForIdleViewportReturnsEmptyWhileScrolling() {
        val messages = listOf(
            message("image-0", localThumbnailPath = "/cache/0.jpg"),
            message("image-1", localThumbnailPath = "/cache/1.jpg")
        )

        assertEquals(
            emptyList<String>(),
            ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = messages,
                visibleMinIndex = 0,
                visibleMaxIndex = 1,
                isScrollInProgress = true,
                alreadyPrefetchedPaths = emptySet(),
                margin = 4,
                maxImages = 10
            )
        )
    }

    @Test
    fun pathsForIdleViewportSelectsUnprefetchedLocalImagePathsWhenIdle() {
        val messages = listOf(
            message("image-0", localThumbnailPath = "/cache/0.jpg"),
            textMessage("text-1"),
            message("image-2", localThumbnailPath = "/cache/2.jpg"),
            message("image-3", localThumbnailPath = "/cache/3.jpg")
        )

        assertEquals(
            listOf("/cache/2.jpg", "/cache/3.jpg"),
            ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = messages,
                visibleMinIndex = 1,
                visibleMaxIndex = 2,
                isScrollInProgress = false,
                alreadyPrefetchedPaths = setOf("/cache/0.jpg"),
                margin = 1,
                maxImages = 10
            )
        )
    }

    @Test
    fun pathsForIdleViewportReturnsEmptyForInvalidVisibleWindow() {
        val messages = listOf(
            message("image-0", localThumbnailPath = "/cache/0.jpg")
        )

        assertEquals(
            emptyList<String>(),
            ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = messages,
                visibleMinIndex = -1,
                visibleMaxIndex = 0,
                isScrollInProgress = false,
                alreadyPrefetchedPaths = emptySet(),
                margin = 4,
                maxImages = 10
            )
        )
    }

    private fun textMessage(id: String): ChatMessage {
        return message(id, type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg")
    }

    private fun message(
        id: String,
        type: MessageType = MessageType.IMAGE,
        localThumbnailPath: String?
    ): ChatMessage {
        return ChatMessage(
            messageId = id,
            conversationId = "single:u_a:u_b",
            senderId = "u_a",
            receiverId = "u_b",
            clientSeq = 1L,
            serverSeq = 1L,
            content = id,
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = 1L,
            updatedAt = 1L,
            type = type,
            localThumbnailPath = localThumbnailPath
        )
    }
}
