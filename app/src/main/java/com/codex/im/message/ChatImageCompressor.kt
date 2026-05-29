package com.codex.im.message

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ChatImageCompressor {
    private const val THUMBNAIL_MAX_EDGE = 480
    private const val ORIGINAL_QUALITY = 90
    private const val THUMBNAIL_QUALITY = 78
    const val JPEG_CONTENT_TYPE = "image/jpeg"

    suspend fun prepareSelectedImage(
        context: Context,
        contentResolver: ContentResolver,
        uri: Uri
    ): SelectedChatImage? {
        return withContext(Dispatchers.IO) {
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            try {
                val originalBytes = compress(source, ORIGINAL_QUALITY) ?: return@withContext null
                val thumbnailBitmap = scaleToThumbnail(source)
                try {
                    val thumbnailBytes = compress(thumbnailBitmap, THUMBNAIL_QUALITY) ?: return@withContext null
                    val originalFile = writeCacheFile(context, originalBytes, "chat-origin", ".jpg")
                    val thumbnailFile = writeCacheFile(context, thumbnailBytes, "chat-thumb", ".jpg")
                    SelectedChatImage(
                        originalBytes = originalBytes,
                        thumbnailBytes = thumbnailBytes,
                        localOriginalPath = originalFile.absolutePath,
                        localThumbnailPath = thumbnailFile.absolutePath,
                        width = source.width,
                        height = source.height,
                        mimeType = JPEG_CONTENT_TYPE
                    )
                } finally {
                    if (thumbnailBitmap !== source) {
                        thumbnailBitmap.recycle()
                    }
                }
            } finally {
                source.recycle()
            }
        }
    }

    private fun scaleToThumbnail(source: Bitmap): Bitmap {
        val maxDimension = maxOf(source.width, source.height)
        if (maxDimension <= THUMBNAIL_MAX_EDGE) {
            return source
        }
        val scale = THUMBNAIL_MAX_EDGE.toFloat() / maxDimension.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        return if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            output.toByteArray()
        } else {
            null
        }
    }

    private fun writeCacheFile(context: Context, bytes: ByteArray, prefix: String, suffix: String): File {
        val directory = File(context.cacheDir, "chat-images").apply { mkdirs() }
        return File.createTempFile(prefix, suffix, directory).also { file ->
            FileOutputStream(file).use { it.write(bytes) }
        }
    }
}
