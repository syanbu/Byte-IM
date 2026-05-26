package com.codex.im

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopLevelBackPolicyTest {
    @Test
    fun messagesRouteMovesTaskToBackOnBack() {
        assertTrue(TopLevelBackPolicy.shouldMoveTaskToBack(SelfHostedImRoute.Conversations.route))
    }

    @Test
    fun meRouteMovesTaskToBackOnBack() {
        assertTrue(TopLevelBackPolicy.shouldMoveTaskToBack(SelfHostedImRoute.Me.route))
    }

    @Test
    fun contactsRouteMovesTaskToBackOnBack() {
        assertTrue(TopLevelBackPolicy.shouldMoveTaskToBack(SelfHostedImRoute.Contacts.route))
    }

    @Test
    fun chatRouteDoesNotMoveTaskToBackOnBack() {
        assertFalse(TopLevelBackPolicy.shouldMoveTaskToBack(SelfHostedImRoute.Chat.pattern))
    }
}
