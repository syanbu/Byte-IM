package com.codex.im.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageBubbleLayoutPolicyTest {
    @Test
    fun landscapeImageFitsWithinBoundsWithoutChangingAspectRatio() {
        val size = ChatImageBubbleLayoutPolicy.displaySize(
            imageWidth = 1600,
            imageHeight = 900
        )

        assertEquals(220, size.widthDp)
        assertEquals(124, size.heightDp)
    }

    @Test
    fun portraitImageFitsWithinBoundsWithoutChangingAspectRatio() {
        val size = ChatImageBubbleLayoutPolicy.displaySize(
            imageWidth = 900,
            imageHeight = 1600
        )

        assertEquals(152, size.widthDp)
        assertEquals(270, size.heightDp)
    }

    @Test
    fun missingImageMetadataUsesStableFallbackSize() {
        val size = ChatImageBubbleLayoutPolicy.displaySize(
            imageWidth = null,
            imageHeight = null
        )

        assertEquals(180, size.widthDp)
        assertEquals(120, size.heightDp)
    }
}
