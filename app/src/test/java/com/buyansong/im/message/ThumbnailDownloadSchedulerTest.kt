package com.buyansong.im.message

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailDownloadSchedulerTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun coroutineSchedulerProcessesHighPriorityBeforeNormalWhenBothAreQueued() = runTest {
        val cache = RecordingThumbnailCache()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = CoroutineThumbnailDownloadScheduler(
            thumbnailCache = cache,
            scope = TestScope(dispatcher)
        )

        scheduler.enqueue(imageMessage("normal"), ThumbnailDownloadPriority.NORMAL) { _, _ -> }
        scheduler.enqueue(imageMessage("high"), ThumbnailDownloadPriority.HIGH) { _, _ -> }
        runCurrent()
        dispatcher.scheduler.runCurrent()

        assertEquals(listOf("high", "normal"), cache.requests)
    }

    private class RecordingThumbnailCache : ChatThumbnailCache {
        val requests = mutableListOf<String>()

        override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? {
            requests += messageId
            return "cache/$messageId.jpg"
        }
    }

    private fun imageMessage(messageId: String): ChatMessage {
        return ChatMessage(
            messageId = messageId,
            conversationId = "single:u1:u2",
            senderId = "u2",
            receiverId = "u1",
            clientSeq = 1L,
            serverSeq = 1L,
            content = "[图片]",
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            type = MessageType.IMAGE,
            thumbnailUrl = "https://oss.example.com/$messageId.jpg"
        )
    }
}
