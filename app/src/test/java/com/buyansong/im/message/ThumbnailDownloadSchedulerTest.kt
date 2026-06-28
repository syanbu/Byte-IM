package com.buyansong.im.message

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailDownloadSchedulerTest {

    private class FakeThumbnailCache : ChatThumbnailCache {
        override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String {
            return "/cache/$messageId.jpg"
        }
    }

    @Test
    fun immediateSchedulerPrewarmsBeforeOnCachedWhenCallbackChoosesToPrewarm() {
        val events = mutableListOf<String>()
        val scheduler = ImmediateThumbnailDownloadScheduler(
            thumbnailCache = FakeThumbnailCache(),
            prewarmLocalThumbnail = { message, _, localPath, _, _ ->
                events += "prewarm:${message.messageId}:$localPath"
                true
            }
        )

        scheduler.enqueue(message("m1"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }

        assertEquals(
            listOf("prewarm:m1:/cache/m1.jpg", "cached:m1:/cache/m1.jpg"),
            events
        )
    }

    @Test
    fun immediateSchedulerSkipsPrewarmWhenCallbackReturnsFalse() {
        val events = mutableListOf<String>()
        val scheduler = ImmediateThumbnailDownloadScheduler(
            thumbnailCache = FakeThumbnailCache(),
            prewarmLocalThumbnail = { _, _, _, _, _ -> false }
        )

        scheduler.enqueue(message("m1"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }

        assertEquals(listOf("cached:m1:/cache/m1.jpg"), events)
    }

    @Test
    fun coroutineSchedulerCapsPrewarmPerDrain() = runTest {
        val events = mutableListOf<String>()
        val scopeJob = Job()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scheduler = CoroutineThumbnailDownloadScheduler(
            thumbnailCache = FakeThumbnailCache(),
            scope = CoroutineScope(scopeJob + dispatcher),
            maxPrewarmPerDrain = 1,
            prewarmLocalThumbnail = { message, _, localPath, _, _ ->
                events += "prewarm:${message.messageId}:$localPath"
                true
            }
        )

        scheduler.enqueue(message("m1"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }
        scheduler.enqueue(message("m2"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }

        testScheduler.runCurrent()

        assertEquals(
            listOf(
                "prewarm:m1:/cache/m1.jpg",
                "cached:m1:/cache/m1.jpg",
                "cached:m2:/cache/m2.jpg"
            ),
            events
        )
        scopeJob.cancel()
    }

    private fun message(id: String): ChatMessage {
        return ChatMessage(
            messageId = id,
            conversationId = "single:u_a:u_b",
            senderId = "u_a",
            receiverId = "u_b",
            clientSeq = 1L,
            serverSeq = 1L,
            content = "[图片]",
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = 1L,
            updatedAt = 1L,
            type = MessageType.IMAGE,
            thumbnailUrl = "https://example.test/$id-thumb.jpg"
        )
    }
}
