package com.codex.im.connection

import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket

object HeartbeatPacketFactory {
    fun create(nowMillis: Long = System.currentTimeMillis()): ImPacket {
        return ImPacket(
            cmd = ImCommand.HEARTBEAT.value,
            body = """{"clientTime":$nowMillis}""".toByteArray()
        )
    }
}
