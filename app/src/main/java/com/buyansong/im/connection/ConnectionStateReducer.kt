package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket

object ConnectionStateReducer {
    fun stateAfterIncomingPacket(packet: ImPacket): ConnectionState? {
        return when (packet.cmd) {
            ImCommand.AUTH_ACK.value -> ConnectionState.Authenticated
            ImCommand.AUTH_NACK.value -> ConnectionState.Failed(authNackReason(packet))
            else -> null
        }
    }

    private fun authNackReason(packet: ImPacket): String {
        val body = packet.body.decodeToString()
        val match = """"reason"\s*:\s*"([^"]+)"""".toRegex().find(body)
        return match?.groupValues?.getOrNull(1) ?: "auth rejected"
    }
}
