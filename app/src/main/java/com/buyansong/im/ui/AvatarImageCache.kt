package com.buyansong.im.ui

import android.content.Context
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class AvatarImageCache(
    private val cacheDir: File,
    private val downloader: (String) -> ByteArray?
) {
    constructor(
        cacheDir: File,
        downloader: () -> ByteArray?
    ) : this(cacheDir, { _: String -> downloader() })

    private val memoryBytes = ConcurrentHashMap<String, ByteArray>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun bytesFor(avatarUrl: String?): ByteArray? {
        val normalizedUrl = avatarUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        memoryBytes[normalizedUrl]?.let { return it }

        val lock = locks.getOrPut(normalizedUrl) { Any() }
        return synchronized(lock) {
            memoryBytes[normalizedUrl]?.let { return@synchronized it }

            val file = cacheFileFor(normalizedUrl)
            if (file.isFile) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    memoryBytes[normalizedUrl] = bytes
                    return@synchronized bytes
                }
            }

            val downloaded = runCatching { downloader(normalizedUrl) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@synchronized null

            runCatching {
                cacheDir.mkdirs()
                file.writeBytes(downloaded)
            }
            memoryBytes[normalizedUrl] = downloaded
            downloaded
        }
    }

    private fun cacheFileFor(url: String): File {
        return File(cacheDir, sha256Hex(url))
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        @Volatile
        private var defaultCache: AvatarImageCache? = null

        fun default(context: Context): AvatarImageCache {
            val directory = File(context.applicationContext.cacheDir, "avatar-images")
            defaultCache?.takeIf { it.cacheDir == directory }?.let { return it }

            return synchronized(this) {
                defaultCache?.takeIf { it.cacheDir == directory } ?: AvatarImageCache(
                    cacheDir = directory,
                    downloader = { url ->
                        URL(url).openStream().use { stream -> stream.readBytes() }
                    }
                ).also { defaultCache = it }
            }
        }
    }
}
