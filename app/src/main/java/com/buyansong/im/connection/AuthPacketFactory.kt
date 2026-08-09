package com.buyansong.im.connection

import com.buyansong.im.protocol.ImEnvelopeCodec
import com.buyansong.im.protocol.v2.Auth
import com.buyansong.im.protocol.v2.ImEnvelope

object AuthPacketFactory {
    fun create(token: String): ImEnvelope {
        return ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .setAuth(Auth.newBuilder().setAccessToken(token))
            .build()
    }
}
