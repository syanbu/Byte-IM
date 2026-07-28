package com.buyansong.im.chat

import kotlin.math.roundToInt

data class ChatImageBubbleSize(
    val widthDp: Int,
    val heightDp: Int
)

data class ChatImageDecodeSize(
    val widthPx: Int,
    val heightPx: Int
)

object ChatImageBubbleLayoutPolicy {
    internal const val MAX_WIDTH_DP = 220
    internal const val MAX_HEIGHT_DP = 270
    private const val MinEdgeDp = 96
    private const val FallbackWidthDp = 180
    private const val FallbackHeightDp = 120

    fun displaySize(imageWidth: Int?, imageHeight: Int?): ChatImageBubbleSize {
        if (imageWidth == null || imageHeight == null || imageWidth <= 0 || imageHeight <= 0) {
            return ChatImageBubbleSize(FallbackWidthDp, FallbackHeightDp)
        }

        val widthScale = MAX_WIDTH_DP.toFloat() / imageWidth.toFloat()
        val heightScale = MAX_HEIGHT_DP.toFloat() / imageHeight.toFloat()
        val scale = minOf(widthScale, heightScale, 1f)
        val width = (imageWidth * scale).roundToInt().coerceIn(MinEdgeDp, MAX_WIDTH_DP)
        val height = (imageHeight * scale).roundToInt().coerceIn(MinEdgeDp, MAX_HEIGHT_DP)
        return ChatImageBubbleSize(width, height)
    }

    fun decodeSizePx(density: Float): ChatImageDecodeSize {
        val safeDensity = density.takeIf { it > 0f } ?: 1f
        return ChatImageDecodeSize(
            widthPx = (MAX_WIDTH_DP * safeDensity).roundToInt().coerceAtLeast(1),
            heightPx = (MAX_HEIGHT_DP * safeDensity).roundToInt().coerceAtLeast(1)
        )
    }
}
