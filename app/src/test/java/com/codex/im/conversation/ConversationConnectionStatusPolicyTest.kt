package com.codex.im.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationConnectionStatusPolicyTest {
    @Test
    fun showsReconnectingStateForMessagesHeader() {
        assertEquals(
            "Connection: Reconnecting in 4s",
            ConversationConnectionStatusPolicy.visibleLabel("Reconnecting in 4s")
        )
    }

    @Test
    fun showsDisconnectedForBlankState() {
        assertEquals(
            "Connection: Disconnected",
            ConversationConnectionStatusPolicy.visibleLabel(" ")
        )
    }

    @Test
    fun hidesAuthenticatedStateBecauseCommunicationIsHealthy() {
        assertNull(ConversationConnectionStatusPolicy.visibleLabel("Authenticated"))
    }

    @Test
    fun hidesConnectedStateBecauseSocketIsNotDisconnected() {
        assertNull(ConversationConnectionStatusPolicy.visibleLabel("Connected"))
    }
}
