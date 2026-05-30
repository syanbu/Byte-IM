package com.codex.im.message

import android.content.Context
import java.io.File
import java.net.URL
import java.security.MessageDigest

interface ChatThumbnailCache {
    fun cacheThumbnail(messageId: String, thumbnailUrl: String): String?
}

object NoopChatThumbnailCache : ChatThumbnailCache {
    override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? = null
}

class AndroidChatThumbnailCache(
    private val cacheDir: File,
    private val downloader: (String) -> ByteArray?
) : ChatThumbnailCache {
    constructor(context: Context) : this(
        cacheDir = File(context.applicationContext.cacheDir, "chat-image-thumbnails"),
        downloader = { url -> URL(url).openStream().use { stream -> stream.readBytes() } }
    )

    override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? {
        val normalizedUrl = thumbnailUrl.trim().takeIf { it.isNotEmpty() } ?: return null
        val file = File(cacheDir, "${messageId.sanitizeFileName()}-${sha256Hex(normalizedUrl)}.jpg")
        if (file.isFile && file.length() > 0L) {
            return file.absolutePath
        }

        val bytes = runCatching { downloader(normalizedUrl) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return runCatching {
            cacheDir.mkdirs()
            file.writeBytes(bytes)
            file.absolutePath
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
