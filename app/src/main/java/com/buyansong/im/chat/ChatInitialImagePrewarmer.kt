package com.buyansong.im.chat

import android.content.Context
import coil.Coil
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
            .mapNotNull { it.localThumbnailPath }
            .distinct()
            .toList()
    }

    fun thumbnailPathsToPrewarm(messages: List<ChatMessage>, maxImages: Int): List<String> {
        if (maxImages <= 0) {
            return emptyList()
        }
        return thumbnailPathsToPrewarm(messages).take(maxImages)
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

        val appContext = context.applicationContext
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                val imageLoader = Coil.imageLoader(appContext)
                thumbnailPaths
                    .chunked(maxConcurrency.coerceAtLeast(1))
                    .forEach { batch ->
                        coroutineScope {
                            batch.map { path ->
                                async {
                                    val request = ChatLocalThumbnailRequest.build(appContext, path) ?: return@async
                                    imageLoader.execute(request)
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
            withTimeoutOrNull(PREWARM_TIMEOUT_MS) {
                val imageLoader = Coil.imageLoader(appContext)
                thumbnailPaths.forEach { path ->
                    val request = ChatLocalThumbnailRequest.build(appContext, path) ?: return@forEach
                    imageLoader.execute(request)
                }
            }
        }
    }
}
