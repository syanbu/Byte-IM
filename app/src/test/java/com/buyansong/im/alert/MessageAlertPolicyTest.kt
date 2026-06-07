package com.buyansong.im.alert

import com.buyansong.im.storage.MessageType
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAlertPolicyTest {
    @Test
    fun textPreviewKeepsEmptyText() {
        assertEquals("", MessageAlertPolicy.previewForText(""))
    }

    @Test
    fun textPreviewKeepsTwentyCharacters() {
        val text = "12345678901234567890"

        assertEquals(text, MessageAlertPolicy.previewForText(text))
    }

    @Test
    fun textPreviewTruncatesAfterTwentyCharacters() {
        assertEquals("12345678901234567890...", MessageAlertPolicy.previewForText("123456789012345678901"))
    }

    @Test
    fun imagePreviewIsStable() {
        assertEquals("[图片]", MessageAlertPolicy.previewForImage())
    }

    @Test
    fun groupTextPreviewPrefixesSenderName() {
        assertEquals(
            "Alice: hello",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = "Alice",
                senderId = "u2",
                content = "hello",
                type = MessageType.TEXT
            )
        )
    }

    @Test
    fun groupTextPreviewFallsBackToSenderId() {
        assertEquals(
            "u2: hello",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = null,
                senderId = "u2",
                content = "hello",
                type = MessageType.TEXT
            )
        )
    }

    @Test
    fun groupImagePreviewPrefixesImageLabel() {
        assertEquals(
            "Alice: [图片]",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = "Alice",
                senderId = "u2",
                content = "ignored",
                type = MessageType.IMAGE
            )
        )
    }

    @Test
    fun groupPreviewTruncatesAfterPrefixAsOneString() {
        assertEquals(
            "Alice: 1234567890123...",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = "Alice",
                senderId = "u2",
                content = "1234567890123456789012345",
                type = MessageType.TEXT
            )
        )
    }

    @Test
    fun formatTimeUsesHourAndMinute() {
        assertEquals(
            "12:31",
            MessageAlertPolicy.formatTime(
                timestampMillis = 45_060_000L,
                timeZone = TimeZone.getTimeZone("UTC")
            )
        )
    }
}
