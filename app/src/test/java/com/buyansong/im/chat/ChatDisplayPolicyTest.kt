package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDisplayPolicyTest {

    @Test
    fun shouldShowTimeSeparator_firstMessage_returnsFalse() {
        assertFalse(
            ChatDisplayPolicy.shouldShowTimeSeparator(
                prevMessage = null,
                currentMessage = chatMessage(createdAt = timestamp(2026, 6, 10, 14, 30))
            )
        )
    }

    @Test
    fun shouldShowTimeSeparator_singleChatBelowThreshold_returnsFalse() {
        val previous = chatMessage(createdAt = timestamp(2026, 6, 10, 14, 0))
        val current = chatMessage(createdAt = previous.createdAt + 4 * 60 * 1000L)

        assertFalse(ChatDisplayPolicy.shouldShowTimeSeparator(previous, current))
    }

    @Test
    fun shouldShowTimeSeparator_groupChatAboveThreshold_returnsTrue() {
        val previous = chatMessage(
            createdAt = timestamp(2026, 6, 10, 14, 0),
            conversationType = ConversationType.GROUP
        )
        val current = chatMessage(
            createdAt = previous.createdAt + 5 * 60 * 1000L + 1,
            conversationType = ConversationType.GROUP
        )

        assertTrue(ChatDisplayPolicy.shouldShowTimeSeparator(previous, current))
    }

    @Test
    fun shouldShowTimeSeparator_exactThreshold_returnsFalse() {
        val previous = chatMessage(createdAt = timestamp(2026, 6, 10, 14, 0))
        val current = chatMessage(createdAt = previous.createdAt + 5 * 60 * 1000L)

        assertFalse(ChatDisplayPolicy.shouldShowTimeSeparator(previous, current))
    }

    @Test
    fun timeSeparatorText_today_formatsHourAndMinute() {
        assertEquals(
            "14:30",
            ChatDisplayPolicy.timeSeparatorText(
                createdAt = timestamp(2026, 6, 10, 14, 30),
                now = timestamp(2026, 6, 10, 12, 0)
            )
        )
    }

    @Test
    fun timeSeparatorText_yesterday_formatsYesterdayAndTime() {
        assertEquals(
            "昨天 14:30",
            ChatDisplayPolicy.timeSeparatorText(
                createdAt = timestamp(2026, 6, 9, 14, 30),
                now = timestamp(2026, 6, 10, 12, 0)
            )
        )
    }

    @Test
    fun timeSeparatorText_sameYear_formatsMonthDayAndTime() {
        assertEquals(
            "3月15日 14:30",
            ChatDisplayPolicy.timeSeparatorText(
                createdAt = timestamp(2026, 3, 15, 14, 30),
                now = timestamp(2026, 6, 10, 12, 0)
            )
        )
    }

    @Test
    fun timeSeparatorText_previousYear_formatsYearMonthDayAndTime() {
        assertEquals(
            "2025年12月31日 23:59",
            ChatDisplayPolicy.timeSeparatorText(
                createdAt = timestamp(2025, 12, 31, 23, 59),
                now = timestamp(2026, 6, 10, 12, 0)
            )
        )
    }

    @Test
    fun topTimelineTimeText_usesTimeSeparatorFormatting() {
        assertEquals(
            "昨天 14:30",
            ChatDisplayPolicy.topTimelineTimeText(
                createdAt = timestamp(2026, 6, 9, 14, 30),
                now = timestamp(2026, 6, 10, 12, 0)
            )
        )
    }

    private fun chatMessage(
        createdAt: Long,
        conversationType: ConversationType = ConversationType.SINGLE
    ): ChatMessage {
        return ChatMessage(
            messageId = "msg-$createdAt",
            conversationId = "conversation-1",
            senderId = "sender-1",
            receiverId = "receiver-1",
            clientSeq = createdAt,
            serverSeq = createdAt,
            content = "hello",
            status = MessageStatus.SENT,
            direction = MessageDirection.INCOMING,
            createdAt = createdAt,
            updatedAt = createdAt,
            conversationType = conversationType
        )
    }

    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long {
        return Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }.timeInMillis
    }
}
