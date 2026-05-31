package com.codex.im.chat

import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType

object ChatDisplayPolicy {
    val backButtonLabel: String? = null
    const val backButtonSymbol = "<"
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
        val prefix = if (message.direction == MessageDirection.OUTGOING) "Me" else message.senderId
        return "$prefix: ${message.content}"
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

    fun recalledMessageText(message: ChatMessage, currentUserId: String): String {
        return if (message.senderId == currentUserId) {
            "你撤回了一条消息"
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
            state.isLoadingMore -> "Loading earlier messages..."
            state.isHistoryMemoryLimitReached -> "Loaded 2000 messages in this chat"
            else -> null
        }
    }

    private const val RECALL_WINDOW_MS = 2 * 60 * 1000L
}

enum class ChatComposerAction {
    PICK_IMAGE,
    SEND_TEXT
}

enum class ChatMessageAction {
    COPY,
    RECALL
}

enum class ChatMessageRowKind {
    BUBBLE,
    CENTERED_NOTICE
}
