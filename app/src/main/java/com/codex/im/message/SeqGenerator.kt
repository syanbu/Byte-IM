package com.codex.im.message

class SeqGenerator {
    private val nextSeqByConversation = mutableMapOf<String, Long>()

    fun next(conversationId: String): Long {
        val next = (nextSeqByConversation[conversationId] ?: 0L) + 1L
        nextSeqByConversation[conversationId] = next
        return next
    }
}
