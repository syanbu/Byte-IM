package com.codex.im.message

class MessageRetryPolicy(
    private val delaysMillis: List<Long> = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L),
    private val maxRetryCount: Int = 5
) {
    fun nextDelayMillis(retryAttempt: Int): Long {
        val index = (retryAttempt - 1).coerceAtLeast(0).coerceAtMost(delaysMillis.lastIndex)
        return delaysMillis[index]
    }

    fun isExhausted(retryCount: Int): Boolean {
        return retryCount >= maxRetryCount
    }
}
