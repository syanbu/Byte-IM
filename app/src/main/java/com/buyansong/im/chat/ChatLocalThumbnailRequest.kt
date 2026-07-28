package com.buyansong.im.chat

import android.content.Context
import coil.request.ImageRequest
import java.io.File

object ChatLocalThumbnailRequest {
    fun cacheKey(localThumbnailPath: String): String? {
        return localThumbnailPath.trim().takeIf { it.isNotEmpty() }
    }

    fun build(context: Context, localThumbnailPath: String): ImageRequest? {
        val key = cacheKey(localThumbnailPath) ?: return null
        val decodeSize = ChatImageBubbleLayoutPolicy.decodeSizePx(
            context.resources.displayMetrics.density
        )
        return ImageRequest.Builder(context)
            .data(File(key))
            .memoryCacheKey(key)
            .size(decodeSize.widthPx, decodeSize.heightPx)
            .build()
    }
}
