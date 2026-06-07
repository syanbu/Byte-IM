package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket

object AuthPacketFactory {
    fun create(token: String): ImPacket {
        val body = """{"token":"${token.escapeJson()}"}""".toByteArray()
        return ImPacket(cmd = ImCommand.AUTH.value, body = body)
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
