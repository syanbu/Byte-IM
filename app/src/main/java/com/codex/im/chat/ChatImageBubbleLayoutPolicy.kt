package com.codex.im.chat

import kotlin.math.roundToInt

data class ChatImageBubbleSize(
    val widthDp: Int,
    val heightDp: Int
)

object ChatImageBubbleLayoutPolicy {
    private const val MaxWidthDp = 220
    private const val MaxHeightDp = 270
    private const val MinEdgeDp = 96
    private const val FallbackWidthDp = 180
    private const val FallbackHeightDp = 120

    fun displaySize(imageWidth: Int?, imageHeight: Int?): ChatImageBubbleSize {
        if (imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
            return ChatImageBubbleSize(FallbackWidthDp, FallbackHeightDp)
        }

        val widthScale = MaxWidthDp.toFloat() / imageWidth.toFloat()
        val heightScale = MaxHeightDp.toFloat() / imageHeight.toFloat()
        val scale = minOf(widthScale, heightScale, 1f)
        val width = (imageWidth * scale).roundToInt().coerceIn(MinEdgeDp, MaxWidthDp)
        val height = (imageHeight * scale).roundToInt().coerceIn(MinEdgeDp, MaxHeightDp)
        return ChatImageBubbleSize(width, height)
    }
}
