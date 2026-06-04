package com.codex.im.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ByteImListRowPolicyTest {

    @Test
    fun dividerStartPaddingMatchesAvatarAndGutterLayout() {
        val expected = ByteImDimensions.EdgePadding +
            ByteImDimensions.ListAvatarSize

        assertEquals(expected, ByteImListRowPolicy.dividerStartPadding())
    }

    @Test
    fun previewEndPaddingOnlyReservesSpaceWhenUnreadBadgeIsVisible() {
        assertEquals(0, ByteImListRowPolicy.previewEndPaddingForUnreadCount(0).value.toInt())
        assertEquals(40, ByteImListRowPolicy.previewEndPaddingForUnreadCount(1).value.toInt())
        assertEquals(40, ByteImListRowPolicy.previewEndPaddingForUnreadCount(100).value.toInt())
    }

    @Test
    fun messagesAndContactsScreensUseSharedDividerStartPolicy() {
        val conversationScreen = sourceFile("src/main/java/com/codex/im/conversation/ConversationListScreen.kt").readText()
        val contactsScreen = sourceFile("src/main/java/com/codex/im/contacts/ContactListScreen.kt").readText()

        assertTrue(
            "ConversationListScreen should use ByteImListRowPolicy.dividerStartPadding() so its dividers " +
                "stay aligned with the row body and don't drift back into the avatar area.",
            conversationScreen.contains("ByteImListRowPolicy.dividerStartPadding()")
        )
        assertTrue(
            "ContactListScreen should use ByteImListRowPolicy.dividerStartPadding() so contacts and messages " +
                "share the same divider alignment source.",
            contactsScreen.contains("ByteImListRowPolicy.dividerStartPadding()")
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
}
