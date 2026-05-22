package com.codex.im.connection

import com.codex.im.protocol.ImPacket
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ImConnection {
    val states: StateFlow<ConnectionState>

    val incomingPackets: SharedFlow<ImPacket>

    fun connect(token: String)

    fun disconnect()

    fun send(packet: ImPacket): Boolean
}
