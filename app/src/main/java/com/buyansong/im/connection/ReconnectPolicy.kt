package com.buyansong.im.connection

class ReconnectPolicy(
    private val delaysMillis: List<Long> = DEFAULT_DELAYS_MILLIS
) {
    private var attempt = 0

    fun nextDelayMillis(): Long {
        val delay = delaysMillis.getOrElse(attempt) { delaysMillis.last() }
        attempt += 1
        return delay
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        val DEFAULT_DELAYS_MILLIS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
