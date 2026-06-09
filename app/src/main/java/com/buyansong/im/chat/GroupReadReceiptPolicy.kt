package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus

object GroupReadReceiptPolicy {
    fun latestEligibleOwnSentMessageId(
        messages: List<ChatMessage>,
        currentUserId: String
    ): String? = messages.firstOrNull { message ->
        message.senderId == currentUserId &&
            message.direction == MessageDirection.OUTGOING &&
            message.status == MessageStatus.SENT &&
            message.serverSeq != null &&
            !message.isRecalled
    }?.messageId

    fun readersOf(
        messageSenderId: String,
        messageServerSeq: Long?,
        cursors: List<GroupReadCursor>,
        members: List<GroupMember>
    ): List<GroupMember> {
        if (messageServerSeq == null) return emptyList()
        val cursorByUser = cursors.associateBy { it.readerId }
        return members
            .filter { it.userId != messageSenderId }
            .mapNotNull { member -> cursorByUser[member.userId]?.let { cursor -> member to cursor } }
            .filter { (_, cursor) -> cursor.readUpToServerSeq >= messageServerSeq }
            .sortedByDescending { (_, cursor) -> cursor.readAt }
            .map { (member, _) -> member }
    }
}
