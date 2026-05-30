package com.codex.im.chat

import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageDirection

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
}

enum class ChatComposerAction {
    PICK_IMAGE,
    SEND_TEXT
}
