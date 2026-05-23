package com.codex.im.connection

import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket

object ConnectionStateReducer {
    fun stateAfterIncomingPacket(packet: ImPacket): ConnectionState? {
        return when (packet.cmd) {
            ImCommand.AUTH_ACK.value -> ConnectionState.Authenticated
            else -> null
        }
    }
}
