package com.codex.im.chat

import com.codex.im.R
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.ConversationType
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.UserProfile

object ChatDisplayPolicy {
    val backButtonLabel: String? = null
    val backButtonIconRes: Int = R.drawable.ic_chevron_left
    val composerLabel: String? = null

    fun composerAction(draft: String): ChatComposerAction {
        return if (draft.trim().isEmpty()) {
            ChatComposerAction.PICK_IMAGE
        } else {
            ChatComposerAction.SEND_TEXT
        }
    }

    fun shouldShowSendButton(draft: String): Boolean {
        return composerAction(draft) == ChatComposerAction.SEND_TEXT
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

    private const val RECALL_WINDOW_MS = 2 * 60 * 1000L
}

enum class ChatComposerAction {
    PICK_IMAGE,
    SEND_TEXT
}

data class BubbleAvatar(
    val displayName: String,
    val avatarUrl: String?
)

enum class ChatMessageAction {
    COPY,
    RECALL
}

enum class ChatMessageRowKind {
    BUBBLE,
    CENTERED_NOTICE
}
