package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatTextBubbleLayoutPolicyTest {
    @Test
    fun textBubbleUsesSeventyTwoPercentOfAvailableRowWidth() {
        assertEquals(230, ChatTextBubbleLayoutPolicy.maxBubbleWidth(320))
        assertEquals(259, ChatTextBubbleLayoutPolicy.maxBubbleWidth(360))
    }

    @Test
    fun chatScreenConstrainsTextBubbleWidthSoLongMessagesWrap() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()

        assertTrue(chatScreen.contains("ChatTextBubbleLayoutPolicy.maxBubbleWidth"))
        assertTrue(chatScreen.contains("widthIn(max = maxBubbleWidth)"))
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
