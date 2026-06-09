package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelGroupReadReceiptTest {

    private fun member(userId: String) = GroupMember(
        groupId = "g_1",
        userId = userId,
        displayName = userId,
        avatarUrl = null,
        role = GroupMemberRole.MEMBER,
        joinedAt = 0L,
        updatedAt = 0L
    )

    private fun msg(id: String, senderId: String, serverSeq: Long?) = ChatMessage(
        messageId = id,
        conversationId = "group:g_1",
        senderId = senderId,
        receiverId = "g_1",
        clientSeq = 0L,
        serverSeq = serverSeq,
        content = "x",
        status = MessageStatus.SENT,
        direction = MessageDirection.OUTGOING,
        createdAt = 0L,
        updatedAt = 0L,
        type = MessageType.TEXT,
        conversationType = ConversationType.GROUP
    )

    @Test
    fun policy_drivesIndicatorStateForCurrentUser() {
        val messages = listOf(
            msg("m2", "u_a", 2L),
            msg("m1", "u_a", 1L)
        )
        val cursors = listOf(
            GroupReadCursor("g_1", "u_b", 2L, 100L),
            GroupReadCursor("g_1", "u_c", 2L, 200L)
        )
        val members = listOf(member("u_a"), member("u_b"), member("u_c"))

        val latestOwnId = GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a")
        val latestOwn = messages.firstOrNull { it.messageId == latestOwnId }
        val readers = GroupReadReceiptPolicy.readersOf(
            messageSenderId = latestOwn!!.senderId,
            messageServerSeq = latestOwn.serverSeq,
            cursors = cursors,
            members = members
        )
        assertEquals("m2", latestOwnId)
        assertEquals(2, readers.size)
        assertEquals(listOf("u_c", "u_b"), readers.map { it.userId })
    }
}
