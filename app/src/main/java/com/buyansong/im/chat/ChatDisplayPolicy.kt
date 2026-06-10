package com.buyansong.im.chat

import com.buyansong.im.R
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import com.buyansong.im.storage.UserProfile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ChatDisplayPolicy {
    val backButtonLabel: String? = null
    val backButtonIconRes: Int = R.drawable.ic_chevron_left
    val composerLabel: String? = null

    fun shouldShowSendButton(draft: String): Boolean {
        return draft.trim().isNotEmpty()
    }

    fun composerAction(draft: String): ChatComposerAction {
        return if (shouldShowSendButton(draft)) {
            ChatComposerAction.SEND_TEXT
        } else {
            ChatComposerAction.PICK_IMAGE
        }
    }

    fun messageLine(message: ChatMessage): String {
        val prefix = if (message.direction == MessageDirection.OUTGOING) "我" else message.senderId
        return "$prefix: ${message.content}"
    }

    fun bubbleAvatar(
        message: ChatMessage,
        groupTitle: String,
        peerName: String,
        peerAvatarUrl: String?,
        currentUserAvatarUrl: String?,
        currentUserId: String,
        senderProfile: UserProfile?
    ): BubbleAvatar {
        if (message.direction == MessageDirection.OUTGOING) {
            return BubbleAvatar(displayName = "我", avatarUrl = currentUserAvatarUrl)
        }
        if (message.conversationType == ConversationType.GROUP) {
            return BubbleAvatar(
                displayName = senderProfile?.nickname ?: message.senderId,
                avatarUrl = senderProfile?.avatarUrl
            )
        }
        return BubbleAvatar(displayName = peerName, avatarUrl = peerAvatarUrl)
    }

    fun bubbleAvatarUserId(message: ChatMessage, currentUserId: String): String? {
        val userId = if (message.direction == MessageDirection.OUTGOING) {
            currentUserId
        } else {
            message.senderId
        }
        return userId.trim().takeIf { it.isNotEmpty() }
    }

    fun canCopy(message: ChatMessage): Boolean {
        return message.type == MessageType.TEXT && !message.isRecalled
    }

    fun canRecall(message: ChatMessage, currentUserId: String, now: Long): Boolean {
        return message.senderId == currentUserId &&
            message.status == MessageStatus.SENT &&
            message.serverSeq != null &&
            !message.isRecalled &&
            now - message.createdAt <= RECALL_WINDOW_MS
    }

    fun messageActions(message: ChatMessage, currentUserId: String, now: Long): List<ChatMessageAction> {
        return buildList {
            if (canCopy(message)) {
                add(ChatMessageAction.COPY)
            }
            if (canRecall(message, currentUserId, now)) {
                add(ChatMessageAction.RECALL)
            }
        }
    }

    fun recalledMessageText(
        message: ChatMessage,
        currentUserId: String,
        senderDisplayName: String? = null
    ): String {
        return if (message.senderId == currentUserId) {
            "你撤回了一条消息"
        } else if (message.conversationType == ConversationType.GROUP) {
            "${senderDisplayName?.takeIf { it.isNotBlank() } ?: message.senderId}撤回了一条消息"
        } else {
            "对方撤回了一条消息"
        }
    }

    fun rowKind(message: ChatMessage): ChatMessageRowKind {
        return if (message.isRecalled) {
            ChatMessageRowKind.CENTERED_NOTICE
        } else {
            ChatMessageRowKind.BUBBLE
        }
    }

    fun historyStatusText(state: ChatUiState): String? {
        if (state.messages.isEmpty()) {
            return null
        }
        return when {
            state.isLoadingMore -> "正在加载更早的消息..."
            state.isHistoryMemoryLimitReached -> "本次聊天已加载 2000 条消息"
            else -> null
        }
    }

    fun shouldShowTimeSeparator(prevMessage: ChatMessage?, currentMessage: ChatMessage): Boolean {
        if (prevMessage == null) {
            return false
        }
        return currentMessage.createdAt - prevMessage.createdAt > TIME_SEPARATOR_THRESHOLD_MS
    }

    fun topTimelineTimeText(createdAt: Long, now: Long = System.currentTimeMillis()): String {
        return timeSeparatorText(createdAt = createdAt, now = now)
    }

    fun timeSeparatorText(createdAt: Long, now: Long = System.currentTimeMillis()): String {
        val messageDate = Date(createdAt)
        val messageCalendar = Calendar.getInstance().apply { time = messageDate }
        val nowCalendar = Calendar.getInstance().apply { timeInMillis = now }

        return when {
            isSameDay(messageCalendar, nowCalendar) -> hourMinuteFormat().format(messageDate)
            isYesterday(messageCalendar, nowCalendar) -> "昨天 ${hourMinuteFormat().format(messageDate)}"
            messageCalendar.get(Calendar.YEAR) == nowCalendar.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(messageDate)
            else -> SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(messageDate)
        }
    }

    private fun hourMinuteFormat(): SimpleDateFormat {
        return SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(messageCalendar: Calendar, nowCalendar: Calendar): Boolean {
        val yesterday = nowCalendar.clone() as Calendar
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(messageCalendar, yesterday)
    }

    private const val RECALL_WINDOW_MS = 2 * 60 * 1000L
    private const val TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L
}

data class BubbleAvatar(
    val displayName: String,
    val avatarUrl: String?
)

enum class ChatMessageAction {
    COPY,
    RECALL
}

enum class ChatComposerAction {
    PICK_IMAGE,
    SEND_TEXT
}

enum class ChatMessageRowKind {
    BUBBLE,
    CENTERED_NOTICE
}
