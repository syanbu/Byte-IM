package com.codex.im.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRetryPolicyTest {
    @Test
    fun retryDelaysBackOffAndCapAtSixtySeconds() {
        val policy = MessageRetryPolicy()

        assertEquals(5_000L, policy.nextDelayMillis(retryAttempt = 1))
        assertEquals(10_000L, policy.nextDelayMillis(retryAttempt = 2))
        assertEquals(20_000L, policy.nextDelayMillis(retryAttempt = 3))
        assertEquals(40_000L, policy.nextDelayMillis(retryAttempt = 4))
        assertEquals(60_000L, policy.nextDelayMillis(retryAttempt = 5))
        assertEquals(60_000L, policy.nextDelayMillis(retryAttempt = 6))
    }

    @Test
    fun retryCountFiveIsExhausted() {
        val policy = MessageRetryPolicy()

        assertFalse(policy.isExhausted(retryCount = 4))
        assertTrue(policy.isExhausted(retryCount = 5))
    }
}
