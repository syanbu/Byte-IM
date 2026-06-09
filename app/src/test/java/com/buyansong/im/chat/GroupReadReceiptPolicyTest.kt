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
import org.junit.Assert.assertNull
import org.junit.Test

class GroupReadReceiptPolicyTest {

    private fun msg(
        id: String,
        senderId: String = "u_a",
        status: MessageStatus = MessageStatus.SENT,
        serverSeq: Long? = 1L,
        isRecalled: Boolean = false,
        direction: MessageDirection = MessageDirection.OUTGOING
    ) = ChatMessage(
        messageId = id,
        conversationId = "group:g_1",
        senderId = senderId,
        receiverId = "g_1",
        clientSeq = 0L,
        serverSeq = serverSeq,
        content = "x",
        status = status,
        direction = direction,
        createdAt = 0L,
        updatedAt = 0L,
        type = MessageType.TEXT,
        conversationType = ConversationType.GROUP
    ).let { if (isRecalled) it.copy(isRecalled = true) else it }

    private fun member(userId: String, name: String = userId) = GroupMember(
        groupId = "g_1",
        userId = userId,
        displayName = name,
        avatarUrl = null,
        role = GroupMemberRole.MEMBER,
        joinedAt = 0L,
        updatedAt = 0L
    )

    private fun cursor(readerId: String, readUpTo: Long, readAt: Long) =
        GroupReadCursor("g_1", readerId, readUpTo, readAt)

    @Test
    fun latestEligible_picksFirstSentOutgoingOwnMessageNewestFirst() {
        val messages = listOf(
            msg("m1", status = MessageStatus.SENDING, serverSeq = null),
            msg("m2", status = MessageStatus.SENT, serverSeq = 1L),
            msg("m3", status = MessageStatus.SENT, serverSeq = 2L, isRecalled = true),
            msg("m4", senderId = "u_b")
        )
        assertEquals("m2", GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a"))
    }

    @Test
    fun latestEligible_skipsRecalledAndNullServerSeq() {
        val messages = listOf(
            msg("m1", status = MessageStatus.SENT, serverSeq = 1L, isRecalled = true),
            msg("m2", status = MessageStatus.SENT, serverSeq = null),
            msg("m3", status = MessageStatus.SENDING),
            msg("m4", status = MessageStatus.FAILED)
        )
        assertNull(GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a"))
    }

    @Test
    fun latestEligible_returnsNullForEmptyList() {
        assertNull(GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(emptyList(), "u_a"))
    }

    @Test
    fun latestEligible_onlyCountsOutgoingOwnSender() {
        val messages = listOf(
            msg("m1", senderId = "u_b", direction = MessageDirection.INCOMING)
        )
        assertNull(GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a"))
    }

    @Test
    fun readersOf_excludesSenderEvenIfCursorExists() {
        val members = listOf(member("u_a"), member("u_b"), member("u_c"))
        val cursors = listOf(
            cursor("u_a", 100L, 5_000L),
            cursor("u_b", 100L, 1_000L),
            cursor("u_c", 50L, 2_000L)
        )
        val readers = GroupReadReceiptPolicy.readersOf("u_a", 100L, cursors, members)
        assertEquals(listOf("u_b"), readers.map { it.userId })
    }

    @Test
    fun readersOf_sortsByReadAtDescending() {
        val members = listOf(member("u_b"), member("u_c"), member("u_d"))
        val cursors = listOf(
            cursor("u_b", 100L, 1_000L),
            cursor("u_c", 100L, 3_000L),
            cursor("u_d", 100L, 2_000L)
        )
        val readers = GroupReadReceiptPolicy.readersOf("u_a", 100L, cursors, members)
        assertEquals(listOf("u_c", "u_d", "u_b"), readers.map { it.userId })
    }

    @Test
    fun readersOf_returnsEmptyWhenServerSeqNull() {
        val members = listOf(member("u_b"))
        val cursors = listOf(cursor("u_b", 100L, 1L))
        assertEquals(emptyList<GroupMember>(), GroupReadReceiptPolicy.readersOf("u_a", null, cursors, members))
    }

    @Test
    fun readersOf_memberWithoutCursorIsOmitted() {
        val members = listOf(member("u_b"), member("u_c"))
        val cursors = listOf(cursor("u_b", 100L, 1L))
        val readers = GroupReadReceiptPolicy.readersOf("u_a", 100L, cursors, members)
        assertEquals(listOf("u_b"), readers.map { it.userId })
    }
}
