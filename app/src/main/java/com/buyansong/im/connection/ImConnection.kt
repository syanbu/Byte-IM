package com.buyansong.im.connection

import com.buyansong.im.protocol.v2.ImEnvelope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface ImConnection {
    val states: StateFlow<ConnectionState>

    val incomingPackets: SharedFlow<ImEnvelope>

    fun connect(token: String)

    fun disconnect()

    fun send(envelope: ImEnvelope): Boolean
}
