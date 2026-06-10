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

    /**
     * Returns the messages in oldest-first order. Used by the chat
     * screen, which renders messages top-to-bottom in a normal
     * `LazyColumn` (item 0 at the visual top, last item at the visual
     * bottom), so the oldest message sits at the top of the list and
     * the newest message sits at the bottom. The newest message is
     * therefore `result.last()`, not `result.first()`.
     *
     * "Active local" (uploading/sending) messages are still pinned to
     * the *end* of the list (i.e. the visual bottom) so the user
     * keeps seeing their in-flight message even if other timeline
     * changes happen around it.
     */
    fun sortOldestFirst(messages: Iterable<ChatMessage>): List<ChatMessage> {
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

        val sortedServerSequenced = serverSequenced.sortedWith(serverSequencedOldestFirst)
        val sortedLocalTimeline = localTimeline.sortedWith(localOldestFirst)
        // For oldest-first we want active-local messages at the END of
        // the list (the visual bottom) and timeline messages in
        // chronological order. We build the timeline first, then append
        // active-local at the end.
        val timelineOldestFirst = mergeLocalTimelineByCreateTimeOldestFirst(
            serverMessages = sortedServerSequenced,
            localMessages = sortedLocalTimeline
        )
        return timelineOldestFirst + activeLocal.sortedWith(localOldestFirst)
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

    /**
     * Like [mergeLocalTimelineByCreateTime] but produces an
     * oldest-first merge: local messages with smaller `createdAt` come
     * first, then the corresponding server message, then the next
     * server message, etc. The `serverMessages` and `localMessages`
     * inputs must already be sorted in their respective oldest-first
     * orders.
     */
    private fun mergeLocalTimelineByCreateTimeOldestFirst(
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
                localMessages[localIndex].createdAt < serverMessage.createdAt
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

    private val serverSequencedOldestFirst = Comparator<ChatMessage> { left, right ->
        val serverSeqOrder = compareNullableLongOldestFirst(left.serverSeq, right.serverSeq)
        if (serverSeqOrder != 0) {
            serverSeqOrder
        } else {
            compareLocalOldestFirst(left, right)
        }
    }

    private val localOldestFirst = Comparator<ChatMessage> { left, right ->
        compareLocalOldestFirst(left, right)
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

    private fun compareLocalOldestFirst(left: ChatMessage, right: ChatMessage): Int {
        val createdAtOrder = left.createdAt.compareTo(right.createdAt)
        if (createdAtOrder != 0) {
            return createdAtOrder
        }
        val serverSeqOrder = compareNullableLongOldestFirst(left.serverSeq, right.serverSeq)
        if (serverSeqOrder != 0) {
            return serverSeqOrder
        }
        val clientSeqOrder = left.clientSeq.compareTo(right.clientSeq)
        if (clientSeqOrder != 0) {
            return clientSeqOrder
        }
        return left.messageId.compareTo(right.messageId)
    }

    private fun compareNullableLongOldestFirst(left: Long?, right: Long?): Int {
        return when {
            left != null && right != null -> left.compareTo(right)
            left != null -> -1
            right != null -> 1
            else -> 0
        }
    }
}
