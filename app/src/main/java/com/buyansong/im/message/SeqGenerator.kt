package com.buyansong.im.message

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class SeqGenerator {
    private val nextSeqByConversation = ConcurrentHashMap<String, AtomicLong>()

    fun next(conversationId: String): Long {
        val existing = nextSeqByConversation[conversationId]
        val counter = if (existing != null) {
            existing
        } else {
            val newCounter = AtomicLong(0L)
            val raced = nextSeqByConversation.putIfAbsent(conversationId, newCounter)
            raced ?: newCounter
        }
        return counter.incrementAndGet()
    }
}
