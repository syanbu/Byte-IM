package com.buyansong.im.message

import android.content.Context
import coil.Coil
import coil.request.ImageRequest
import java.io.File

interface ChatThumbnailPreloader {
    fun preload(localThumbnailPaths: List<String>)
}

object NoopChatThumbnailPreloader : ChatThumbnailPreloader {
    override fun preload(localThumbnailPaths: List<String>) = Unit
}

class CoilChatThumbnailPreloader(
    context: Context
) : ChatThumbnailPreloader {
    private val appContext = context.applicationContext
    private val imageLoader = Coil.imageLoader(appContext)

    override fun preload(localThumbnailPaths: List<String>) {
        localThumbnailPaths
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { path ->
                imageLoader.enqueue(
                    ImageRequest.Builder(appContext)
                        .data(File(path))
                        .memoryCacheKey(path)
                        .diskCacheKey(path)
                        .build()
                )
            }
    }
}
