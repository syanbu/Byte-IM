package com.buyansong.im.message

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.security.MessageDigest

interface ChatThumbnailCache {
    suspend fun cacheThumbnail(messageId: String, thumbnailUrl: String): String?
}

object NoopChatThumbnailCache : ChatThumbnailCache {
    override suspend fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? = null
}

class CoilChatThumbnailCache(
    context: Context,
    private val imageLoader: ImageLoader = Coil.imageLoader(context.applicationContext)
) : ChatThumbnailCache {
    private val appContext = context.applicationContext

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? {
        val normalizedUrl = thumbnailUrl.trim().takeIf { it.isNotEmpty() } ?: return null
        val key = "chat-thumb-${messageId.sanitizeFileName()}-${sha256Hex(normalizedUrl)}"
        return runCatching {
            val request = ImageRequest.Builder(appContext)
                .data(normalizedUrl)
                .diskCacheKey(key)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            if (imageLoader.execute(request) !is SuccessResult) {
                return@runCatching null
            }
            val file = imageLoader.diskCache?.openSnapshot(key)?.use { snapshot ->
                snapshot.data.toFile()
            } ?: return@runCatching null
            if (file.isFile && file.length() > 0L) file.absolutePath else null
        }.getOrNull()
    }

    private fun String.sanitizeFileName(): String {
        return replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
