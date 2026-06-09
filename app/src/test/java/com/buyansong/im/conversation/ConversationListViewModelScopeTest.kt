package com.buyansong.im.conversation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationListViewModelScopeTest {

    @Test
    fun conversationListViewModelIsRememberedAboveConversationsDestination() {
        val source = listOf(
            File("src/main/java/com/buyansong/im/MainActivity.kt"),
            File("app/src/main/java/com/buyansong/im/MainActivity.kt")
        )
            .firstOrNull { it.exists() }
            ?.readText()
            ?: throw java.io.FileNotFoundException("MainActivity.kt not found from test working directory")

        val topLevelViewModelIndex = source.indexOf(
            "val conversationListViewModel = remember(session.userId) {"
        )
        val conversationsDestinationIndex = source.indexOf(
            "composable(SelfHostedImRoute.Conversations.route) {"
        )
        val nestedViewModelIndex = source.indexOf(
            "composable(SelfHostedImRoute.Conversations.route) {\n                val conversationListViewModel = remember(session.userId) {"
        )

        assertTrue(
            "ConversationListViewModel should be defined in MainActivity",
            topLevelViewModelIndex >= 0
        )
        assertTrue(
            "Conversations destination should still exist in MainActivity",
            conversationsDestinationIndex >= 0
        )
        assertFalse(
            "ConversationListViewModel should not be created inside the Conversations destination block",
            nestedViewModelIndex >= 0
        )
    }
}
