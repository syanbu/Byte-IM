package com.codex.im.message

class MessageIdGenerator(startCounter: Long = 0) {
    private var counter = startCounter

    fun next(userId: String, now: Long): String {
        counter += 1
        return "$userId-$now-${counter.toString().padStart(6, '0')}"
    }
}
