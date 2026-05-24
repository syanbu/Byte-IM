package com.codex.im.chat

import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChatDisplayPolicyTest {
    @Test
    fun outgoingMessageLineDoesNotShowStatus() {
        val line = ChatDisplayPolicy.messageLine(
            message = message(
                content = "hello",
                status = MessageStatus.SENT,
                direction = MessageDirection.OUTGOING
            )
        )

        assertEquals("Me: hello", line)
        assertFalse(line.contains("[SENT]"))
    }

    @Test
    fun incomingMessageLineDoesNotShowStatus() {
        val line = ChatDisplayPolicy.messageLine(
            message = message(
                senderId = "13900113900",
                content = "hi",
                status = MessageStatus.RECEIVED,
                direction = MessageDirection.INCOMING
            )
        )

        assertEquals("13900113900: hi", line)
        assertFalse(line.contains("[RECEIVED]"))
    }

    @Test
    fun localHistoryEndDoesNotShowUserVisibleText() {
        val text = ChatDisplayPolicy.historyStatusText(
            ChatUiState(
                messages = listOf(message()),
                isLoadingMore = false,
                hasMoreLocal = false
            )
        )

        assertNull(text)
    }

    @Test
    fun loadingHistoryStillShowsLoadingText() {
        val text = ChatDisplayPolicy.historyStatusText(
            ChatUiState(
                messages = listOf(message()),
                isLoadingMore = true,
                hasMoreLocal = true
            )
        )

        assertEquals("Loading earlier messages...", text)
    }

    @Test
    fun chatTopBackButtonDoesNotShowAUserVisibleLabel() {
        assertNull(ChatDisplayPolicy.backButtonLabel)
    }

    @Test
    fun chatTopBackButtonUsesPlainBackSymbol() {
        assertEquals("<", ChatDisplayPolicy.backButtonSymbol)
    }

    @Test
    fun composerDoesNotShowMessageLabel() {
        assertNull(ChatDisplayPolicy.composerLabel)
    }

    @Test
    fun composerSendButtonOnlyShowsWhenDraftHasContent() {
        assertFalse(ChatDisplayPolicy.shouldShowSendButton(""))
        assertFalse(ChatDisplayPolicy.shouldShowSendButton("   "))
        assertEquals(true, ChatDisplayPolicy.shouldShowSendButton("hello"))
    }

    private fun message(
        senderId: String = "13800113800",
        content: String = "hello",
        status: MessageStatus = MessageStatus.SENT,
        direction: MessageDirection = MessageDirection.OUTGOING
    ): ChatMessage {
        return ChatMessage(
            messageId = "m1",
            conversationId = "single:13800113800:13900113900",
            senderId = senderId,
            receiverId = "13900113900",
            clientSeq = 1L,
            serverSeq = null,
            content = content,
            status = status,
            direction = direction,
            createdAt = 1_000L,
            updatedAt = 1_000L
        )
    }
}
