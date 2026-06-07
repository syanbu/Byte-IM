package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket

object HeartbeatPacketFactory {
    fun create(nowMillis: Long = System.currentTimeMillis()): ImPacket {
        return ImPacket(
            cmd = ImCommand.HEARTBEAT.value,
            body = """{"clientTime":$nowMillis}""".toByteArray()
        )
    }
}
