package com.buyansong.im.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class AvatarImageCacheTest {
    @Test
    fun bytesForCachesDownloadedAvatarInMemory() {
        var downloadCount = 0
        val cache = AvatarImageCache(
            cacheDir = Files.createTempDirectory("avatar-cache").toFile(),
            downloader = { _: String ->
                downloadCount += 1
                byteArrayOf(1, 2, 3)
            }
        )

        val first = cache.bytesFor("https://example.com/avatar.jpg")
        val second = cache.bytesFor("https://example.com/avatar.jpg")

        assertArrayEquals(byteArrayOf(1, 2, 3), first)
        assertArrayEquals(byteArrayOf(1, 2, 3), second)
        assertEquals(1, downloadCount)
    }

    @Test
    fun bytesForCachesDownloadedAvatarOnDisk() {
        val directory = Files.createTempDirectory("avatar-cache").toFile()
        val firstCache = AvatarImageCache(
            cacheDir = directory,
            downloader = { _: String -> byteArrayOf(4, 5, 6) }
        )
        firstCache.bytesFor("https://example.com/avatar.jpg")

        var downloadCount = 0
        val secondCache = AvatarImageCache(
            cacheDir = directory,
            downloader = { _: String ->
                downloadCount += 1
                byteArrayOf(7, 8, 9)
            }
        )

        val restored = secondCache.bytesFor("https://example.com/avatar.jpg")

        assertArrayEquals(byteArrayOf(4, 5, 6), restored)
        assertEquals(0, downloadCount)
    }

    @Test
    fun bytesForReturnsNullForBlankUrl() {
        val cache = AvatarImageCache(
            cacheDir = Files.createTempDirectory("avatar-cache").toFile(),
            downloader = { _: String -> byteArrayOf(1) }
        )

        assertNull(cache.bytesFor(""))
    }
}
