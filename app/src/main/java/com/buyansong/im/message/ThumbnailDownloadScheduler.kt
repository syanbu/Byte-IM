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
    private val prewarmLocalThumbnail: suspend (
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        localThumbnailPath: String,
        alreadyPrewarmedInDrain: Int,
        maxPrewarmPerDrain: Int
    ) -> Boolean = { _, _, _, _, _ -> false }
) : ThumbnailDownloadScheduler {
    override fun enqueue(
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        onCached: (messageId: String, localThumbnailPath: String) -> Unit
    ): Boolean {
        val thumbnailUrl = message.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
        val localPath = thumbnailCache.cacheThumbnail(message.messageId, thumbnailUrl) ?: return false
        kotlinx.coroutines.runBlocking {
            prewarmLocalThumbnail(
                message,
                priority,
                localPath,
                0,
                1
            )
        }
        onCached(message.messageId, localPath)
        return true
    }
}

class CoroutineThumbnailDownloadScheduler(
    private val thumbnailCache: ChatThumbnailCache,
    private val scope: CoroutineScope,
    private val maxPrewarmPerDrain: Int = 5,
    private val prewarmLocalThumbnail: suspend (
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        localThumbnailPath: String,
        alreadyPrewarmedInDrain: Int,
        maxPrewarmPerDrain: Int
    ) -> Boolean = { _, _, _, _, _ -> false }
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
                message = message,
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
        var prewarmedInDrain = 0
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
            val localPath = thumbnailCache.cacheThumbnail(
                request.message.messageId,
                request.thumbnailUrl
            ) ?: continue
            val didPrewarm = if (prewarmedInDrain < maxPrewarmPerDrain.coerceAtLeast(0)) {
                prewarmLocalThumbnail(
                    request.message,
                    request.priority,
                    localPath,
                    prewarmedInDrain,
                    maxPrewarmPerDrain
                )
            } else {
                false
            }
            if (didPrewarm) {
                prewarmedInDrain += 1
            }
            request.onCached(request.message.messageId, localPath)
        }
    }

    private data class PendingThumbnailDownload(
        val message: ChatMessage,
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
