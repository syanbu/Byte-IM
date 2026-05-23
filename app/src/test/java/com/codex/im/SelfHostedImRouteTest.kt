package com.codex.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfHostedImRouteTest {
    @Test
    fun conversationsRouteIsStartDestination() {
        assertEquals("conversations", SelfHostedImRoute.Conversations.route)
    }

    @Test
    fun chatPatternCarriesPeerUserIdArgument() {
        assertEquals("chat/{peerUserId}", SelfHostedImRoute.Chat.pattern)
    }

    @Test
    fun chatRouteUsesTrimmedPeerUserId() {
        assertEquals("chat/13900113900", SelfHostedImRoute.Chat.createRoute(" 13900113900 "))
    }

    @Test
    fun chatRouteIgnoresBlankPeerUserId() {
        assertNull(SelfHostedImRoute.Chat.createRoute(" "))
    }
}
