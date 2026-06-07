package com.buyansong.im.storage

object MessageOrderingPolicy {
    val newestFirst: Comparator<ChatMessage> = Comparator { left, right ->
        compareNewestFirst(left, right)
    }

    fun sortNewestFirst(messages: Iterable<ChatMessage>): List<ChatMessage> {
        val activeLocal = mutableListOf<ChatMessage>()
        val serverSequenced = mutableListOf<ChatMessage>()
        val localTimeline = mutableListOf<ChatMessage>()

        messages.forEach { message ->
            when {
                isActiveLocal(message) -> activeLocal += message
                message.serverSeq != null -> serverSequenced += message
                else -> localTimeline += message
            }
        }

        val sortedServerSequenced = serverSequenced.sortedWith(serverSequencedNewestFirst)
        val sortedLocalTimeline = localTimeline.sortedWith(localNewestFirst)
        return activeLocal.sortedWith(localNewestFirst) + mergeLocalTimelineByCreateTime(
            serverMessages = sortedServerSequenced,
            localMessages = sortedLocalTimeline
        )
    }

    private fun compareNewestFirst(left: ChatMessage, right: ChatMessage): Int {
        val activeLocalOrder = activeLocalRank(left).compareTo(activeLocalRank(right))
        if (activeLocalOrder != 0) {
            return activeLocalOrder
        }

        return compareLocalNewestFirst(left, right)
    }

    private fun activeLocalRank(message: ChatMessage): Int {
        return if (isActiveLocal(message)) 0 else 1
    }

    private fun isActiveLocal(message: ChatMessage): Boolean {
        return message.serverSeq == null && message.status in setOf(
            MessageStatus.UPLOADING,
            MessageStatus.SENDING
        )
    }

    private fun mergeLocalTimelineByCreateTime(
        serverMessages: List<ChatMessage>,
        localMessages: List<ChatMessage>
    ): List<ChatMessage> {
        if (serverMessages.isEmpty()) {
            return localMessages
        }
        val merged = mutableListOf<ChatMessage>()
        var localIndex = 0
        serverMessages.forEach { serverMessage ->
            while (
                localIndex < localMessages.size &&
                localMessages[localIndex].createdAt > serverMessage.createdAt
            ) {
                merged += localMessages[localIndex]
                localIndex += 1
            }
            merged += serverMessage
        }
        while (localIndex < localMessages.size) {
            merged += localMessages[localIndex]
            localIndex += 1
        }
        return merged
    }

    private val serverSequencedNewestFirst = Comparator<ChatMessage> { left, right ->
        val serverSeqOrder = compareNullableLongNewestFirst(left.serverSeq, right.serverSeq)
        if (serverSeqOrder != 0) {
            serverSeqOrder
        } else {
            compareLocalNewestFirst(left, right)
        }
    }

    private val localNewestFirst = Comparator<ChatMessage> { left, right ->
        compareLocalNewestFirst(left, right)
    }

    private fun compareLocalNewestFirst(left: ChatMessage, right: ChatMessage): Int {
        val createdAtOrder = right.createdAt.compareTo(left.createdAt)
        if (createdAtOrder != 0) {
            return createdAtOrder
        }
        val serverSeqOrder = compareNullableLongNewestFirst(left.serverSeq, right.serverSeq)
        if (serverSeqOrder != 0) {
            return serverSeqOrder
        }
        val clientSeqOrder = right.clientSeq.compareTo(left.clientSeq)
        if (clientSeqOrder != 0) {
            return clientSeqOrder
        }
        return right.messageId.compareTo(left.messageId)
    }

    private fun compareNullableLongNewestFirst(left: Long?, right: Long?): Int {
        return when {
            left != null && right != null -> right.compareTo(left)
            left != null -> -1
            right != null -> 1
            else -> 0
        }
    }
}
