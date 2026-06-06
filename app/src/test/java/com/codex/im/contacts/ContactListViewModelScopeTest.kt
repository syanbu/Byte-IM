package com.codex.im.contacts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContactListViewModelScopeTest {
    @Test
    fun contactListViewModelIsRememberedAboveContactsDestination() {
        val source = File("src/main/java/com/codex/im/MainActivity.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/com/codex/im/MainActivity.kt")
        val text = source.readText()
        val hoistedIndex = text.indexOf("val contactListViewModel = remember(session.userId)")
        val contactsRouteIndex = text.indexOf("composable(SelfHostedImRoute.Contacts.route)")

        assertTrue(
            "ContactListViewModel must be remembered before the Contacts destination so scroll state survives tab navigation.",
            hoistedIndex >= 0 && contactsRouteIndex >= 0 && hoistedIndex < contactsRouteIndex
        )
    }
}
