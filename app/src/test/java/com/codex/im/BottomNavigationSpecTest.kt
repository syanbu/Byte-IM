package com.codex.im

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationSpecTest {
    @Test
    fun messagesTabUsesMessageVectorDrawable() {
        assertEquals(R.drawable.ic_nav_message, BottomNavigationSpec.messages.iconResId)
    }

    @Test
    fun contactsTabUsesContactsVectorDrawable() {
        assertEquals(R.drawable.ic_nav_contacts, BottomNavigationSpec.contacts.iconResId)
    }

    @Test
    fun meTabUsesMeVectorDrawable() {
        assertEquals(R.drawable.ic_nav_me, BottomNavigationSpec.me.iconResId)
    }

    @Test
    fun topLevelTabsAreOrderedMessagesContactsMe() {
        assertEquals(
            listOf("Messages", "Contacts", "Me"),
            BottomNavigationSpec.topLevelItems.map { it.label }
        )
    }
}
