package com.buyansong.im.alert

import com.buyansong.im.storage.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MessageAlertPolicy {
    private const val MAX_PREVIEW_CHARS = 20
    private const val ELLIPSIS = "..."
    private const val IMAGE_LABEL = "[图片]"

    fun previewForText(content: String): String = truncate(content)

    fun previewForImage(): String = IMAGE_LABEL

    fun groupPreview(
        senderDisplayName: String?,
        senderId: String,
        content: String,
        type: MessageType
    ): String {
        val senderLabel = senderDisplayName?.takeIf { it.isNotBlank() } ?: senderId
        val messagePreview = when (type) {
            MessageType.IMAGE -> previewForImage()
            MessageType.TEXT -> content
        }
        return truncate("$senderLabel: $messagePreview")
    }

    fun formatTime(
        timestampMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(timestampMillis))
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_PREVIEW_CHARS) {
            return text
        }
        return text.take(MAX_PREVIEW_CHARS) + ELLIPSIS
    }
}
