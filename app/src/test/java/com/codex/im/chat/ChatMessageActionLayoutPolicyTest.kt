package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatMessageActionLayoutPolicyTest {
    @Test
    fun outgoingReadMarkerAlignsWithBubbleLineNotActionBarColumn() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        assertTrue(chatScreen.contains("private fun ChatMessageContent"))
        assertTrue(chatScreen.contains("private fun ChatBubbleLine"))
        assertTrue(chatScreen.contains("OutgoingMessageStatus("))
        assertTrue(chatScreen.contains("ChatTextBubble("))
        assertFalse(chatScreen.contains("private fun ChatTextBubble(\n    message: ChatMessage,\n    currentUserId: String,\n    onCopy: (String) -> Unit,\n    onRecall: (ChatMessage) -> Unit,"))
    }

    @Test
    fun imageBubblesCanOpenRecallActionBarOnLongPress() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()
        val imageBubble = sourceFile("src/main/java/com/codex/im/chat/ChatImageBubble.kt").readText()

        assertTrue(chatScreen.contains("onLongPressImage = onOpenActions"))
        assertTrue(imageBubble.contains("onLongPress: () -> Unit = {}"))
        assertTrue(imageBubble.contains("combinedClickable("))
        assertFalse(imageBubble.contains(".clickable("))
    }

    @Test
    fun tappingOutsideMessageActionBarDismissesIt() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        assertTrue(chatScreen.contains("var activeActionMessageId by remember"))
        assertTrue(chatScreen.contains("activeActionMessageId = null"))
        assertTrue(chatScreen.contains("showActions = activeActionMessageId == message.messageId"))
        assertTrue(chatScreen.contains("onOpenActions = { activeActionMessageId = message.messageId }"))
        assertTrue(chatScreen.contains("onDismissActions = { activeActionMessageId = null }"))
        assertFalse(chatScreen.contains("var showMenu by remember { mutableStateOf(false) }"))
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
