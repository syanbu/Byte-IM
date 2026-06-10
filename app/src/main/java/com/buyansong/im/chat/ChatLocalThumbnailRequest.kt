package com.buyansong.im.chat

import android.content.Context
import coil.request.ImageRequest
import coil.size.Size

object ChatLocalThumbnailRequest {
    fun cacheKey(localThumbnailPath: String): String? {
        return localThumbnailPath.trim().takeIf { it.isNotEmpty() }
    }

    fun build(context: Context, localThumbnailPath: String): ImageRequest? {
        val key = cacheKey(localThumbnailPath) ?: return null
        return ImageRequest.Builder(context)
            .data(key)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .size(Size.ORIGINAL)
            .build()
    }
}
