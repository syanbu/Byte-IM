package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatImageBubbleLoadingPolicyTest {
    @Test
    fun thumbnailLoadingDoesNotShowInlineProgress() {
        assertFalse(ChatImageBubbleLoadingPolicy.showInlineProgress())
    }

    @Test
    fun uploadingOrSendingImageDoesNotShowBubbleStatusProgress() {
        assertFalse(ChatImageBubbleLoadingPolicy.showBubbleStatusProgress())
    }

    @Test
    fun missingThumbnailUsesPlainGrayPlaceholderWithoutImageText() {
        val imageBubble = sourceFile("src/main/java/com/codex/im/chat/ChatImageBubble.kt").readText()

        assertTrue(imageBubble.contains("Color(0xFFE5E2E1)"))
        assertFalse(imageBubble.contains("text = \"[图片]\""))
    }

    private fun sourceFile(path: String): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, path),
            File(userDir, "app/$path")
        )
        return candidates.first { it.exists() }
    }
}
