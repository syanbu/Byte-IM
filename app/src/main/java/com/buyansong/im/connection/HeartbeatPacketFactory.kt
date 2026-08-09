package com.buyansong.im.connection

import com.buyansong.im.protocol.ImEnvelopeCodec
import com.buyansong.im.protocol.v2.Heartbeat
import com.buyansong.im.protocol.v2.ImEnvelope

object HeartbeatPacketFactory {
    fun create(nowMillis: Long = System.currentTimeMillis(), unackedMessageIds: List<String> = emptyList()): ImEnvelope {
        return ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .setHeartbeat(
                Heartbeat.newBuilder()
                    .setClientTime(nowMillis)
                    .addAllUnackedMessageIds(unackedMessageIds)
            )
            .build()
    }
}
