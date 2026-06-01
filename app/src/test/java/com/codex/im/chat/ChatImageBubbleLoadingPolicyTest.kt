package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Test

class ChatImageBubbleLoadingPolicyTest {
    @Test
    fun thumbnailLoadingDoesNotShowInlineProgress() {
        assertFalse(ChatImageBubbleLoadingPolicy.showInlineProgress())
    }

    @Test
    fun uploadingOrSendingImageDoesNotShowBubbleStatusProgress() {
        assertFalse(ChatImageBubbleLoadingPolicy.showBubbleStatusProgress())
    }
}
