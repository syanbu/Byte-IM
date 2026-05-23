package com.codex.im.connection

import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStateReducerTest {
    @Test
    fun authAckMovesConnectionToAuthenticated() {
        val packet = ImPacket(
            cmd = ImCommand.AUTH_ACK.value,
            body = """{"userId":"13800113800","serverTime":1000}""".toByteArray()
        )

        assertEquals(ConnectionState.Authenticated, ConnectionStateReducer.stateAfterIncomingPacket(packet))
    }

    @Test
    fun nonConnectionPacketsDoNotChangeConnectionState() {
        val packet = ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """{"messageId":"m1"}""".toByteArray()
        )

        assertNull(ConnectionStateReducer.stateAfterIncomingPacket(packet))
    }
}
