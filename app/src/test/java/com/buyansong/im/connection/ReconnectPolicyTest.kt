package com.buyansong.im.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun delaysGrowExponentiallyAndCapAtThirtySeconds() {
        val policy = ReconnectPolicy()

        val delays = List(8) { policy.nextDelayMillis() }

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
            delays
        )
    }

    @Test
    fun resetReturnsNextDelayToOneSecond() {
        val policy = ReconnectPolicy()
        repeat(6) { policy.nextDelayMillis() }

        policy.reset()

        assertEquals(1_000L, policy.nextDelayMillis())
    }
}
