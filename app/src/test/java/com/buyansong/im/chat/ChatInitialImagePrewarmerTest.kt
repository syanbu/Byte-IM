package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInitialImagePrewarmerTest {

    @Test
    fun thumbnailPathsToPrewarmReturnsDistinctLocalPathsForImageMessagesOnly() {
        val messages = listOf(
            message("text", type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg"),
            message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
            message("image-2", type = MessageType.IMAGE, localThumbnailPath = null),
            message("image-3", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
            message("image-4", type = MessageType.IMAGE, localThumbnailPath = "/cache/b.jpg")
        )

        assertEquals(
            listOf("/cache/a.jpg", "/cache/b.jpg"),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(messages)
        )
    }

    @Test
    fun thumbnailPathsToPrewarmCanBeLimitedAfterDistinctFiltering() {
        val messages = listOf(
            message("text", type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg"),
            message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
            message("image-2", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
            message("image-3", type = MessageType.IMAGE, localThumbnailPath = "/cache/b.jpg"),
            message("image-4", type = MessageType.IMAGE, localThumbnailPath = "/cache/c.jpg")
        )

        assertEquals(
            listOf("/cache/a.jpg", "/cache/b.jpg"),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(messages, maxImages = 2)
        )
    }

    @Test
    fun shouldPrewarmBeforeNavigationReturnsFalseWhenThereAreNoLocalImageThumbnails() {
        val messages = listOf(
            message("text", type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg"),
            message("image-empty", type = MessageType.IMAGE, localThumbnailPath = null)
        )

        assertEquals(false, ChatInitialImagePrewarmer.shouldPrewarmBeforeNavigation(messages))
    }

    @Test
    fun shouldPrewarmBeforeNavigationReturnsTrueWhenLocalImageThumbnailExists() {
        val messages = listOf(
            message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg")
        )

        assertEquals(true, ChatInitialImagePrewarmer.shouldPrewarmBeforeNavigation(messages))
    }

    private fun message(
        id: String,
        type: MessageType,
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
