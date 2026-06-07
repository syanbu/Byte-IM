package com.codex.im.message

import java.util.concurrent.atomic.AtomicLong

class MessageIdGenerator(startCounter: Long = 0) {
    private val counter = AtomicLong(startCounter)

    fun next(userId: String, now: Long): String {
        val n = counter.incrementAndGet()
        return "$userId-$now-${n.toString().padStart(6, '0')}"
    }
}
