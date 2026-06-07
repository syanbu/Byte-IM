package com.codex.im.message

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SeqGenerator {
    private val nextSeqByConversation = ConcurrentHashMap<String, AtomicLong>()

    fun next(conversationId: String): Long {
        val counter = nextSeqByConversation.computeIfAbsent(conversationId) { AtomicLong(0L) }
        return counter.incrementAndGet()
    }
}
