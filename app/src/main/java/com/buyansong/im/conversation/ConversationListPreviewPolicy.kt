package com.buyansong.im.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.buyansong.im.chat.ChatMention
import com.buyansong.im.chat.ChatMentionPolicy

object ConversationListPreviewPolicy {
    private const val MENTION_REMINDER_LABEL = "[有人@我]"

    fun previewText(item: ConversationListItem): String {
        val preview = displayPreview(item)
        return if (item.mentionUnreadCount > 0) {
            "$MENTION_REMINDER_LABEL $preview"
        } else {
            preview
        }
    }

    fun previewAnnotatedText(item: ConversationListItem, mentionColor: Color): AnnotatedString {
        val preview = displayPreview(item)
        if (item.mentionUnreadCount <= 0) {
            return AnnotatedString(preview)
        }
        return buildAnnotatedString {
            withStyle(SpanStyle(color = mentionColor)) {
                append(MENTION_REMINDER_LABEL)
            }
            append(" ")
            append(preview)
        }
    }

    private fun displayPreview(item: ConversationListItem): String {
        val preview = item.lastMessagePreview.ifBlank { "开始聊天吧" }
        val mentions = item.mentionDisplayNamesById.map { (userId, displayName) ->
            ChatMention(userId = userId, displayName = displayName)
        }
        return ChatMentionPolicy.displayText(preview, mentions).text
    }
}
