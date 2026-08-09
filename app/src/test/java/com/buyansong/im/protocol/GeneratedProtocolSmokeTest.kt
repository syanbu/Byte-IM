package com.buyansong.im.protocol

import com.buyansong.im.protocol.v2.Heartbeat
import com.buyansong.im.protocol.v2.ImEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedProtocolSmokeTest {
    @Test
    fun generatedEnvelopeExposesOneofPayloadCase() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(2)
            .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
            .build()

        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT, envelope.payloadCase)
    }
}
