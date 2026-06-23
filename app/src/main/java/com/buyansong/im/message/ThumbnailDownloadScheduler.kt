package com.buyansong.im.message

import com.buyansong.im.storage.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class ThumbnailDownloadPriority {
    HIGH,
    NORMAL,
    LOW
}

interface ThumbnailDownloadScheduler {
    fun enqueue(
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        onCached: (messageId: String, localThumbnailPath: String) -> Unit
    ): Boolean
}

class ImmediateThumbnailDownloadScheduler(
    private val thumbnailCache: ChatThumbnailCache,
    private val prewarmLocalThumbnail: suspend (String) -> Unit = {}
) : ThumbnailDownloadScheduler {
    override fun enqueue(
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        onCached: (messageId: String, localThumbnailPath: String) -> Unit
    ): Boolean {
        val thumbnailUrl = message.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
        val localPath = thumbnailCache.cacheThumbnail(message.messageId, thumbnailUrl) ?: return false
        kotlinx.coroutines.runBlocking {
            prewarmLocalThumbnail(localPath)
        }
        onCached(message.messageId, localPath)
        return true
    }
}

class CoroutineThumbnailDownloadScheduler(
    private val thumbnailCache: ChatThumbnailCache,
    private val scope: CoroutineScope,
    private val prewarmLocalThumbnail: suspend (String) -> Unit = {}
) : ThumbnailDownloadScheduler {
    private val lock = Any()
    private val pendingRequests = mutableListOf<PendingThumbnailDownload>()
    private var nextSequence = 0L
    private var workerRunning = false

    override fun enqueue(
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        onCached: (messageId: String, localThumbnailPath: String) -> Unit
    ): Boolean {
        val thumbnailUrl = message.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
        synchronized(lock) {
            pendingRequests += PendingThumbnailDownload(
                messageId = message.messageId,
                thumbnailUrl = thumbnailUrl,
                priority = priority,
                sequence = nextSequence++,
                onCached = onCached
            )
            if (!workerRunning) {
                workerRunning = true
                scope.launch {
                    drainQueue()
                }
            }
        }
        return true
    }

    private suspend fun drainQueue() {
        while (true) {
            val request = synchronized(lock) {
                val next = pendingRequests
                    .minWithOrNull(
                        compareBy<PendingThumbnailDownload> { it.priority.rank }
                            .thenBy { it.sequence }
                    )
                if (next == null) {
                    workerRunning = false
                    return
                }
                pendingRequests.remove(next)
                next
            }
            val localPath = thumbnailCache.cacheThumbnail(request.messageId, request.thumbnailUrl) ?: continue
            prewarmLocalThumbnail(localPath)
            request.onCached(request.messageId, localPath)
        }
    }

    private data class PendingThumbnailDownload(
        val messageId: String,
        val thumbnailUrl: String,
        val priority: ThumbnailDownloadPriority,
        val sequence: Long,
        val onCached: (messageId: String, localThumbnailPath: String) -> Unit
    )

    private val ThumbnailDownloadPriority.rank: Int
        get() = when (this) {
            ThumbnailDownloadPriority.HIGH -> 0
            ThumbnailDownloadPriority.NORMAL -> 1
            ThumbnailDownloadPriority.LOW -> 2
        }
}
