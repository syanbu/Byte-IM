package com.buyansong.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfHostedImRouteTest {
    @Test
    fun conversationsRouteIsStartDestination() {
        assertEquals("conversations", SelfHostedImRoute.Conversations.route)
    }

    @Test
    fun contactsRouteIsTopLevelDestination() {
        assertEquals("contacts", SelfHostedImRoute.Contacts.route)
    }

    @Test
    fun chatPatternCarriesConversationIdArgument() {
        assertEquals("chat/{conversationId}", SelfHostedImRoute.Chat.pattern)
    }

    @Test
    fun chatRouteUsesTrimmedSingleConversationId() {
        assertEquals("chat/single:13800113800:13900113900", SelfHostedImRoute.Chat.createRoute(" single:13800113800:13900113900 "))
    }

    @Test
    fun chatRouteAcceptsGroupConversationId() {
        assertEquals("chat/group:g_1001", SelfHostedImRoute.Chat.createRoute(" group:g_1001 "))
    }

    @Test
    fun chatRouteIgnoresBlankConversationId() {
        assertNull(SelfHostedImRoute.Chat.createRoute(" "))
    }

    @Test
    fun singleChatRouteBuildsCanonicalSingleConversationId() {
        assertEquals(
            "chat/single:13800113800:13900113900",
            SelfHostedImRoute.Chat.createSingleRoute(currentUserId = "13900113900", peerUserId = "13800113800")
        )
    }

    @Test
    fun contactProfileRouteIsUnderContactProfilePath() {
        assertEquals("contact-profile/{userId}", SelfHostedImRoute.ContactProfile.pattern)
    }

    @Test
    fun contactProfileRouteEncodesUserIdFromCreateRoute() {
        assertEquals("contact-profile/13900113900", SelfHostedImRoute.ContactProfile.createRoute("13900113900"))
    }

    @Test
    fun contactProfileRouteTrimsUserIdFromCreateRoute() {
        assertEquals("contact-profile/13900113900", SelfHostedImRoute.ContactProfile.createRoute(" 13900113900 "))
    }

    @Test
    fun contactProfileRouteIgnoresBlankUserId() {
        assertNull(SelfHostedImRoute.ContactProfile.createRoute(""))
        assertNull(SelfHostedImRoute.ContactProfile.createRoute("   "))
    }
}
