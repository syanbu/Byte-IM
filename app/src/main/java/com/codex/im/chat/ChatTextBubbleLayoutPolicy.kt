package com.codex.im.chat

import kotlin.math.roundToInt

object ChatTextBubbleLayoutPolicy {
    private const val MaxBubbleWidthFraction = 0.72f

    fun maxBubbleWidth(availableRowWidthDp: Int): Int {
        require(availableRowWidthDp > 0) { "availableRowWidthDp must be positive" }
        return (availableRowWidthDp * MaxBubbleWidthFraction).roundToInt()
    }
}
