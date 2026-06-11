package com.buyansong.im.chat

import android.content.Context
import coil.Coil
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object ChatInitialImagePrewarmer {
    private const val PREWARM_TIMEOUT_MS = 300L

    fun thumbnailPathsToPrewarm(messages: List<ChatMessage>): List<String> {
        return messages
            .asSequence()
            .filter { it.type == MessageType.IMAGE }
            .mapNotNull { it.localThumbnailPath }
            .distinct()
            .toList()
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
