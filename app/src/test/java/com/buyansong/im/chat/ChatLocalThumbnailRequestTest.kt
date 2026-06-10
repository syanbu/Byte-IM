package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatLocalThumbnailRequestTest {

    @Test
    fun cacheKeyReturnsTrimmedLocalThumbnailPath() {
        assertEquals(
            "/data/user/0/com.buyansong.im/cache/chat-thumb-1.jpg",
            ChatLocalThumbnailRequest.cacheKey("  /data/user/0/com.buyansong.im/cache/chat-thumb-1.jpg  ")
        )
    }

    @Test
    fun cacheKeyReturnsNullForBlankPath() {
        assertNull(ChatLocalThumbnailRequest.cacheKey(""))
        assertNull(ChatLocalThumbnailRequest.cacheKey("   "))
    }
}
