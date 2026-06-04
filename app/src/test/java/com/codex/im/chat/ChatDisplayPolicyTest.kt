package com.codex.im.chat

import com.codex.im.R
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertEquals("我: hello", line)
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

        assertEquals("正在加载更早的消息...", text)
    }

    @Test
    fun chatTopBackButtonDoesNotShowAUserVisibleLabel() {
        assertNull(ChatDisplayPolicy.backButtonLabel)
    }

    @Test
    fun chatTopBackButtonUsesChevronBackIcon() {
        assertEquals(R.drawable.ic_chevron_left, ChatDisplayPolicy.backButtonIconRes)
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

    @Test
    fun composerPrimaryActionSwitchesBetweenImageAndSend() {
        assertEquals(ChatComposerAction.PICK_IMAGE, ChatDisplayPolicy.composerAction(""))
        assertEquals(ChatComposerAction.PICK_IMAGE, ChatDisplayPolicy.composerAction("   "))
        assertEquals(ChatComposerAction.SEND_TEXT, ChatDisplayPolicy.composerAction("hello"))
    }

    @Test
    fun textMessagesCanBeCopiedUntilRecalled() {
        assertTrue(ChatDisplayPolicy.canCopy(message(content = "copy me")))
        assertFalse(ChatDisplayPolicy.canCopy(message(content = "old").copy(isRecalled = true)))
        assertFalse(ChatDisplayPolicy.canCopy(message(content = "[图片]").copy(type = MessageType.IMAGE)))
    }

    @Test
    fun onlyCurrentUserSentMessagesWithServerSeqCanBeRecalledWithinTwoMinutes() {
        val sent = message(
            senderId = "13800113800",
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING
        ).copy(serverSeq = 9L, createdAt = 1_000L)

        assertTrue(ChatDisplayPolicy.canRecall(sent, currentUserId = "13800113800", now = 121_000L))
        assertFalse(ChatDisplayPolicy.canRecall(sent, currentUserId = "13800113800", now = 121_001L))
        assertFalse(ChatDisplayPolicy.canRecall(sent.copy(senderId = "13900113900"), currentUserId = "13800113800", now = 2_000L))
        assertFalse(ChatDisplayPolicy.canRecall(sent.copy(serverSeq = null), currentUserId = "13800113800", now = 2_000L))
        assertFalse(ChatDisplayPolicy.canRecall(sent.copy(isRecalled = true), currentUserId = "13800113800", now = 2_000L))
    }

    @Test
    fun messageActionsAreOrderedLeftToRightAndHideExpiredRecall() {
        val sent = message(
            senderId = "13800113800",
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING
        ).copy(serverSeq = 9L, createdAt = 1_000L)

        assertEquals(
            listOf(ChatMessageAction.COPY, ChatMessageAction.RECALL),
            ChatDisplayPolicy.messageActions(sent, currentUserId = "13800113800", now = 121_000L)
        )
        assertEquals(
            listOf(ChatMessageAction.COPY),
            ChatDisplayPolicy.messageActions(sent, currentUserId = "13800113800", now = 121_001L)
        )
    }

    @Test
    fun imageMessageActionsOnlyShowRecallWhenEligible() {
        val image = message(
            senderId = "13800113800",
            content = "[图片]",
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING
        ).copy(type = MessageType.IMAGE, serverSeq = 9L, createdAt = 1_000L)

        assertEquals(
            listOf(ChatMessageAction.RECALL),
            ChatDisplayPolicy.messageActions(image, currentUserId = "13800113800", now = 121_000L)
        )
        assertEquals(
            emptyList<ChatMessageAction>(),
            ChatDisplayPolicy.messageActions(image, currentUserId = "13800113800", now = 121_001L)
        )
    }

    @Test
    fun recalledMessagePromptDependsOnSender() {
        val own = message(senderId = "13800113800").copy(isRecalled = true)
        val peer = message(senderId = "13900113900", direction = MessageDirection.INCOMING).copy(isRecalled = true)

        assertEquals("你撤回了一条消息", ChatDisplayPolicy.recalledMessageText(own, currentUserId = "13800113800"))
        assertEquals("对方撤回了一条消息", ChatDisplayPolicy.recalledMessageText(peer, currentUserId = "13800113800"))
    }

    @Test
    fun groupRecalledMessagePromptUsesSenderNickname() {
        val groupPeer = message(
            senderId = "13900113900",
            direction = MessageDirection.INCOMING
        ).copy(
            isRecalled = true,
            conversationId = "group:g_1001",
            conversationType = com.codex.im.storage.ConversationType.GROUP
        )

        assertEquals(
            "ByteDance2撤回了一条消息",
            ChatDisplayPolicy.recalledMessageText(
                message = groupPeer,
                currentUserId = "13800113800",
                senderDisplayName = "ByteDance2"
            )
        )
    }

    @Test
    fun recalledMessagesUseCenteredNoticeInsteadOfBubbleRow() {
        assertEquals(ChatMessageRowKind.CENTERED_NOTICE, ChatDisplayPolicy.rowKind(message().copy(isRecalled = true)))
        assertEquals(ChatMessageRowKind.BUBBLE, ChatDisplayPolicy.rowKind(message().copy(isRecalled = false)))
    }

    @Test
    fun incomingGroupBubbleAvatarUsesMessageSenderProfileInsteadOfGroupTitle() {
        val senderProfile = UserProfile(
            userId = "13800113800",
            phone = "13800113800",
            nickname = "Alice",
            avatarUrl = "https://example.com/a.jpg",
            avatarUpdatedAt = 1_000L,
            updatedAt = 1_000L
        )

        val avatar = ChatDisplayPolicy.bubbleAvatar(
            message = message(
                senderId = "13800113800",
                direction = MessageDirection.INCOMING
            ).copy(conversationId = "group:g_1001", conversationType = com.codex.im.storage.ConversationType.GROUP),
            groupTitle = "群聊(2)",
            peerName = "Bob",
            peerAvatarUrl = "https://example.com/group.jpg",
            currentUserAvatarUrl = null,
            currentUserId = "13900113900",
            senderProfile = senderProfile
        )

        assertEquals("Alice", avatar.displayName)
        assertEquals("https://example.com/a.jpg", avatar.avatarUrl)
    }

    @Test
    fun outgoingBubbleAvatarUserIdUsesCurrentUser() {
        val userId = ChatDisplayPolicy.bubbleAvatarUserId(
            message = message(
                senderId = "13800113800",
                direction = MessageDirection.OUTGOING
            ),
            currentUserId = "13800113800"
        )

        assertEquals("13800113800", userId)
    }

    @Test
    fun incomingBubbleAvatarUserIdUsesSenderForSingleAndGroupMessages() {
        val singleUserId = ChatDisplayPolicy.bubbleAvatarUserId(
            message = message(
                senderId = "13900113900",
                direction = MessageDirection.INCOMING
            ),
            currentUserId = "13800113800"
        )
        val groupUserId = ChatDisplayPolicy.bubbleAvatarUserId(
            message = message(
                senderId = "17724734511",
                direction = MessageDirection.INCOMING
            ).copy(
                conversationId = "group:g_1001",
                conversationType = com.codex.im.storage.ConversationType.GROUP
            ),
            currentUserId = "13800113800"
        )

        assertEquals("13900113900", singleUserId)
        assertEquals("17724734511", groupUserId)
    }

    @Test
    fun bubbleAvatarUserIdReturnsNullForBlankResolvedUserId() {
        val userId = ChatDisplayPolicy.bubbleAvatarUserId(
            message = message(
                senderId = "   ",
                direction = MessageDirection.INCOMING
            ),
            currentUserId = "13800113800"
        )

        assertNull(userId)
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
