package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageBubbleLayoutPolicyTest {

    @Test
    fun decodeSizePxUsesMaximumBubbleBoundsAtDeviceDensity() {
        assertEquals(
            ChatImageDecodeSize(widthPx = 440, heightPx = 540),
            ChatImageBubbleLayoutPolicy.decodeSizePx(density = 2f)
        )
    }

    @Test
    fun decodeSizePxFallsBackToOneXForInvalidDensity() {
        assertEquals(
            ChatImageDecodeSize(widthPx = 220, heightPx = 270),
            ChatImageBubbleLayoutPolicy.decodeSizePx(density = 0f)
        )
    }
}
