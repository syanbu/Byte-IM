package com.codex.im.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object AvatarImageCompressor {
    const val MAX_AVATAR_BYTES: Int = 1_000_000
    const val JPEG_CONTENT_TYPE: String = "image/jpeg"

    suspend fun compressJpegUnderLimit(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Int = MAX_AVATAR_BYTES
    ): ByteArray? {
        return withContext(Dispatchers.IO) {
            val source = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext null
            try {
                compressBitmap(source, maxBytes)
            } finally {
                source.recycle()
            }
        }
    }

    private fun compressBitmap(source: Bitmap, maxBytes: Int): ByteArray? {
        var bitmap = source
        var quality = 90
        repeat(24) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= maxBytes) {
                if (bitmap !== source) {
                    bitmap.recycle()
                }
                return bytes
            }
            if (quality > 45) {
                quality -= 10
            } else {
                val nextWidth = (bitmap.width * 0.85f).toInt().coerceAtLeast(1)
                val nextHeight = (bitmap.height * 0.85f).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
                if (bitmap !== source) {
                    bitmap.recycle()
                }
                bitmap = scaled
                quality = 90
            }
        }
        if (bitmap !== source) {
            bitmap.recycle()
        }
        return null
    }
}
