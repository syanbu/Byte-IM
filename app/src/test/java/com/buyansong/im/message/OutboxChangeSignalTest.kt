package com.buyansong.im.message

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxChangeSignalTest {
    @Test
    fun notifyChanged_incrementsRevisionMonotonically() {
        val signal = OutboxChangeSignal()
        assertEquals(0L, signal.revisions.value)

        signal.notifyChanged()
        signal.notifyChanged()
        signal.notifyChanged()

        assertEquals(3L, signal.revisions.value)
    }
}
