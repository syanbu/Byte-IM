package com.codex.im.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationRowLayoutTest {

    @Test
    fun unreadBadgeOverlaysPreviewLineWithoutReducingTimeColumnWidth() {
        val conversationScreen =
            sourceFile("src/main/java/com/codex/im/conversation/ConversationListScreen.kt").readText()
        val rowBody = extractFunctionBody(
            source = conversationScreen,
            signature = "private fun ConversationRow("
        ) ?: error("ConversationRow declaration not found")

        assertTrue(
            "ConversationRow should keep the title/time Column as the Row's weighted content area.",
            rowBody.contains("Column(modifier = Modifier.weight(1f))")
        )
        assertTrue(
            "The unread badge should be rendered inside the weighted Column's preview-line Box, " +
                "so it no longer consumes sibling width from the last-message time.",
            rowBody.contains("Box(modifier = Modifier.fillMaxWidth())") &&
                rowBody.indexOf("Box(modifier = Modifier.fillMaxWidth())") <
                rowBody.indexOf("ByteImUnreadBadge(")
        )
        assertTrue(
            "The unread badge should align to the trailing edge of the preview line.",
            rowBody.contains("Modifier.align(Alignment.CenterEnd)")
        )
        assertFalse(
            "ConversationRow must not keep ByteImUnreadBadge as a top-level Row child after the " +
                "weighted Column; that layout shifts the time left when unreadCount is positive.",
            Regex("""\}\s*if\s*\(\s*item\.unreadCount\s*>\s*0\s*\)\s*\{\s*ByteImUnreadBadge\(""")
                .containsMatchIn(rowBody)
        )
    }

    @Test
    fun longPressDeleteMenuUsesPopupWithBlackText() {
        val conversationScreen =
            sourceFile("src/main/java/com/codex/im/conversation/ConversationListScreen.kt").readText()
        val rowBody = extractFunctionBody(
            source = conversationScreen,
            signature = "private fun ConversationRow("
        ) ?: error("ConversationRow declaration not found")

        assertTrue(
            "ConversationRow long-press actions should render with Popup instead of Material DropdownMenu.",
            rowBody.contains("Popup(")
        )
        assertFalse(
            "ConversationRow delete action should not use DropdownMenu; it needs a custom floating menu.",
            rowBody.contains("DropdownMenu(")
        )
        assertTrue(
            "The delete action label should match the requested copy.",
            rowBody.contains("删除该聊天")
        )
        assertTrue(
            "The delete action text should use the primary black text color.",
            rowBody.contains("color = ByteImColors.TextPrimary")
        )
    }

    private fun sourceFile(path: String): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, path),
            File(userDir, "app/$path")
        )
        return candidates.first { it.exists() }
    }

    private fun extractFunctionBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
        if (start < 0) return null
        val openBrace = source.indexOf('{', start)
        if (openBrace < 0) return null

        var depth = 0
        var i = openBrace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }
}
