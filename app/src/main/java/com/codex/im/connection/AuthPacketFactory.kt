package com.codex.im.connection

import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket

object AuthPacketFactory {
    fun create(token: String): ImPacket {
        val body = """{"token":"${token.escapeJson()}"}""".toByteArray()
        return ImPacket(cmd = ImCommand.AUTH.value, body = body)
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
