package com.codex.im.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationConnectionStatusPolicyTest {
    @Test
    fun showsReconnectingStateForMessagesHeader() {
        assertEquals(
            "连接状态：4 秒后重连",
            ConversationConnectionStatusPolicy.visibleLabel("4 秒后重连")
        )
    }

    @Test
    fun showsDisconnectedForBlankState() {
        assertEquals(
            "连接状态：未连接",
            ConversationConnectionStatusPolicy.visibleLabel(" ")
        )
    }

    @Test
    fun hidesAuthenticatedStateBecauseCommunicationIsHealthy() {
        assertNull(ConversationConnectionStatusPolicy.visibleLabel("已认证"))
    }

    @Test
    fun hidesConnectedStateBecauseSocketIsNotDisconnected() {
        assertNull(ConversationConnectionStatusPolicy.visibleLabel("已连接"))
    }
}
