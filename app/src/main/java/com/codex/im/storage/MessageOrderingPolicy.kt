package com.codex.im.storage

object MessageOrderingPolicy {
    val newestFirst: Comparator<ChatMessage> = Comparator { left, right ->
        compareNewestFirst(left, right)
    }

    private fun compareNewestFirst(left: ChatMessage, right: ChatMessage): Int {
        val categoryOrder = category(left).compareTo(category(right))
        if (categoryOrder != 0) {
            return categoryOrder
        }

        val leftServerSeq = left.serverSeq
        val rightServerSeq = right.serverSeq

        if (leftServerSeq != null && rightServerSeq != null) {
            val serverSeqOrder = rightServerSeq.compareTo(leftServerSeq)
            if (serverSeqOrder != 0) {
                return serverSeqOrder
            }
        }
        return compareLocalNewestFirst(left, right)
    }

    private fun category(message: ChatMessage): Int {
        return when {
            message.serverSeq == null && message.status == MessageStatus.SENDING -> 0
            message.serverSeq != null -> 1
            else -> 2
        }
    }

    private fun compareLocalNewestFirst(left: ChatMessage, right: ChatMessage): Int {
        val createdAtOrder = right.createdAt.compareTo(left.createdAt)
        if (createdAtOrder != 0) {
            return createdAtOrder
        }
        val clientSeqOrder = right.clientSeq.compareTo(left.clientSeq)
        if (clientSeqOrder != 0) {
            return clientSeqOrder
        }
        return right.messageId.compareTo(left.messageId)
    }
}
