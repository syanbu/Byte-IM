package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket

object HeartbeatPacketFactory {
    fun create(nowMillis: Long = System.currentTimeMillis(), unackedMessageIds: List<String> = emptyList()): ImPacket {
        val unackedJson = unackedMessageIds.joinToString(separator = ",", prefix = "[", postfix = "]") { messageId ->
            "\"${messageId.escapeJson()}\""
        }
        return ImPacket(
            cmd = ImCommand.HEARTBEAT.value,
            body = """{"clientTime":$nowMillis,"unackedMessageIds":$unackedJson}""".toByteArray()
        )
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
