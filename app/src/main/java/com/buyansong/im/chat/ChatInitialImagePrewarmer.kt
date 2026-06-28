package com.buyansong.im.chat

import android.content.Context
import coil.Coil
import coil.request.SuccessResult
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ChatInitialImagePrewarmer {
    private const val PREWARM_TIMEOUT_MS = 300L
    private const val PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 700L
    private const val MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 12
    private const val MAX_PREWARM_CONCURRENCY = 3

    fun thumbnailPathsToPrewarm(messages: List<ChatMessage>): List<String> {
        return messages
            .asSequence()
            .filter { it.type == MessageType.IMAGE }
            .mapNotNull { it.localThumbnailPath?.let(ChatLocalThumbnailRequest::cacheKey) }
            .distinct()
            .toList()
    }

    fun thumbnailPathsToPrewarm(messages: List<ChatMessage>, maxImages: Int): List<String> {
        if (maxImages <= 0) {
            return emptyList()
        }
        return thumbnailPathsToPrewarm(messages).take(maxImages)
    }

    fun thumbnailPathsToPrewarm(
        messages: List<ChatMessage>,
        visibleMinIndex: Int,
        visibleMaxIndex: Int,
        margin: Int,
        maxImages: Int
    ): List<String> {
        if (
            messages.isEmpty() ||
            visibleMinIndex < 0 ||
            visibleMaxIndex < 0 ||
            maxImages <= 0
        ) {
            return emptyList()
        }

        val visibleStart = minOf(visibleMinIndex, visibleMaxIndex)
        val visibleEnd = maxOf(visibleMinIndex, visibleMaxIndex)
        val boundedMargin = margin.coerceAtLeast(0)
        val clampedStart = (visibleStart - boundedMargin).coerceAtLeast(0)
        val clampedEnd = (visibleEnd + boundedMargin).coerceAtMost(messages.lastIndex)

        return messages
            .asSequence()
            .drop(clampedStart)
            .take(clampedEnd - clampedStart + 1)
            .filter { it.type == MessageType.IMAGE }
            .mapNotNull { it.localThumbnailPath?.let(ChatLocalThumbnailRequest::cacheKey) }
            .distinct()
            .take(maxImages)
            .toList()
    }

    fun shouldPrewarmBeforeNavigation(messages: List<ChatMessage>): Boolean {
        return thumbnailPathsToPrewarm(messages, maxImages = 1).isNotEmpty()
    }

    suspend fun prewarmBeforeNavigation(
        context: Context,
        messages: List<ChatMessage>
    ) {
        prewarmBeforeNavigation(
            context = context,
            messages = messages,
            timeoutMs = PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS,
            maxImages = MAX_PREWARM_BEFORE_NAVIGATION_IMAGES,
            maxConcurrency = MAX_PREWARM_CONCURRENCY
        )
    }

    internal suspend fun prewarmBeforeNavigation(
        context: Context,
        messages: List<ChatMessage>,
        timeoutMs: Long,
        maxImages: Int,
        maxConcurrency: Int
    ) {
        val thumbnailPaths = thumbnailPathsToPrewarm(messages, maxImages)
        if (thumbnailPaths.isEmpty()) {
            return
        }

        prewarmLocalThumbnails(
            context = context,
            localThumbnailPaths = thumbnailPaths,
            timeoutMs = timeoutMs,
            maxConcurrency = maxConcurrency
        )
    }

    suspend fun prewarmLocalThumbnail(context: Context, localThumbnailPath: String): Boolean {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            val request = ChatLocalThumbnailRequest.build(appContext, localThumbnailPath) ?: return@withContext false
            Coil.imageLoader(appContext).execute(request) is SuccessResult
        }
    }

    suspend fun prewarmLocalThumbnails(
        context: Context,
        localThumbnailPaths: List<String>,
        timeoutMs: Long,
        maxConcurrency: Int
    ) {
        val thumbnailPaths = localThumbnailPaths
            .mapNotNull(ChatLocalThumbnailRequest::cacheKey)
            .distinct()
        if (thumbnailPaths.isEmpty()) {
            return
        }

        val appContext = context.applicationContext
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                thumbnailPaths
                    .chunked(maxConcurrency.coerceAtLeast(1))
                    .forEach { batch ->
                        coroutineScope {
                            batch.map { path ->
                                async {
                                    prewarmLocalThumbnail(appContext, path)
                                }
                            }.awaitAll()
                        }
                    }
            }
        }
    }

    fun prewarmAsync(scope: CoroutineScope, context: Context, messages: List<ChatMessage>) {
        val thumbnailPaths = thumbnailPathsToPrewarm(messages)
        if (thumbnailPaths.isEmpty()) {
            return
        }

        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            prewarmLocalThumbnails(
                context = appContext,
                localThumbnailPaths = thumbnailPaths,
                timeoutMs = PREWARM_TIMEOUT_MS,
                maxConcurrency = 1
            )
        }
    }
}
