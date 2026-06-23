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
            message("image-blank", type = MessageType.IMAGE, localThumbnailPath = "   "),
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

    @Test
    fun viewportThumbnailPathsIncludeVisibleWindowPlusMargin() {
        val messages = listOf(
            message("image-0", type = MessageType.IMAGE, localThumbnailPath = "/cache/0.jpg"),
            message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/1.jpg"),
            message("text-2", type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg"),
            message("image-3", type = MessageType.IMAGE, localThumbnailPath = "/cache/3.jpg"),
            message("image-4", type = MessageType.IMAGE, localThumbnailPath = "/cache/4.jpg"),
            message("image-5", type = MessageType.IMAGE, localThumbnailPath = "/cache/5.jpg")
        )

        assertEquals(
            listOf("/cache/1.jpg", "/cache/3.jpg", "/cache/4.jpg"),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
                messages = messages,
                visibleMinIndex = 2,
                visibleMaxIndex = 3,
                margin = 1,
                maxImages = 10
            )
        )
    }

    @Test
    fun viewportThumbnailPathsClampToMessageBounds() {
        val messages = listOf(
            message("image-0", type = MessageType.IMAGE, localThumbnailPath = "/cache/0.jpg"),
            message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/1.jpg"),
            message("image-2", type = MessageType.IMAGE, localThumbnailPath = "/cache/2.jpg")
        )

        assertEquals(
            listOf("/cache/0.jpg", "/cache/1.jpg", "/cache/2.jpg"),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
                messages = messages,
                visibleMinIndex = 0,
                visibleMaxIndex = 5,
                margin = 4,
                maxImages = 10
            )
        )
    }

    @Test
    fun viewportThumbnailPathsRemoveDuplicatesBeforeMaxImagesLimit() {
        val messages = listOf(
            message("image-0", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
            message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
            message("image-2", type = MessageType.IMAGE, localThumbnailPath = "/cache/b.jpg"),
            message("image-3", type = MessageType.IMAGE, localThumbnailPath = "/cache/c.jpg")
        )

        assertEquals(
            listOf("/cache/a.jpg", "/cache/b.jpg"),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
                messages = messages,
                visibleMinIndex = 0,
                visibleMaxIndex = 3,
                margin = 0,
                maxImages = 2
            )
        )
    }

    @Test
    fun viewportThumbnailPathsReturnEmptyForInvalidInputs() {
        val messages = listOf(
            message("image-0", type = MessageType.IMAGE, localThumbnailPath = "/cache/0.jpg")
        )

        assertEquals(
            emptyList<String>(),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
                messages = messages,
                visibleMinIndex = -1,
                visibleMaxIndex = 0,
                margin = 1,
                maxImages = 10
            )
        )
        assertEquals(
            emptyList<String>(),
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
                messages = messages,
                visibleMinIndex = 0,
                visibleMaxIndex = 0,
                margin = 1,
                maxImages = 0
            )
        )
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
