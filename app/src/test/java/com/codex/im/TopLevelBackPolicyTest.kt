package com.codex.im

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelBackPolicyTest {
    @Test
    fun messagesRouteExitsAppOnBack() {
        assertTrue(TopLevelBackPolicy.shouldExitApp(SelfHostedImRoute.Conversations.route))
    }

    @Test
    fun meRouteExitsAppOnBack() {
        assertTrue(TopLevelBackPolicy.shouldExitApp(SelfHostedImRoute.Me.route))
    }

    @Test
    fun contactsRouteExitsAppOnBack() {
        assertTrue(TopLevelBackPolicy.shouldExitApp(SelfHostedImRoute.Contacts.route))
    }

    @Test
    fun chatRouteDoesNotExitAppOnBack() {
        assertFalse(TopLevelBackPolicy.shouldExitApp(SelfHostedImRoute.Chat.pattern))
    }
}
