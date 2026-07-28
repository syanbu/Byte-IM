package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun entryWarmupSelectsAtMostSixDistinctLocalThumbnails() {
        val messages = (1..8).map { index ->
            message(
                id = "image-$index",
                type = MessageType.IMAGE,
                localThumbnailPath = "/cache/$index.jpg"
            )
        }

        assertEquals(
            (1..6).map { "/cache/$it.jpg" },
            ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
                messages = messages,
                maxImages = ChatInitialImagePrewarmer.MAX_PREWARM_BEFORE_NAVIGATION_IMAGES
            )
        )
    }

    @Test
    fun forEachWithConcurrencyStartsNextItemWhenOneSlotBecomesAvailable() = runTest {
        val releaseThird = CompletableDeferred<Unit>()
        val fourthStarted = CompletableDeferred<Unit>()

        val job = launch {
            ChatInitialImagePrewarmer.forEachWithConcurrency(
                items = listOf(1, 2, 3, 4),
                maxConcurrency = 3
            ) { item ->
                when (item) {
                    3 -> releaseThird.await()
                    4 -> fourthStarted.complete(Unit)
                }
            }
        }

        testScheduler.runCurrent()

        assertTrue(
            "Item 4 must start while item 3 is still running",
            fourthStarted.isCompleted
        )

        releaseThird.complete(Unit)
        job.join()
    }

    @Test
    fun forEachWithConcurrencyNeverExceedsConfiguredLimit() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()

        val job = launch {
            ChatInitialImagePrewarmer.forEachWithConcurrency(
                items = listOf(1, 2, 3, 4),
                maxConcurrency = 3
            ) { item ->
                started += item
                release.await()
            }
        }

        testScheduler.runCurrent()

        assertEquals(listOf(1, 2, 3), started)

        release.complete(Unit)
        job.join()
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
