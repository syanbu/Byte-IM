package com.buyansong.im.connection

import com.buyansong.im.protocol.ImEnvelopeCodec
import com.buyansong.im.protocol.v2.ImEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatPacketFactoryTest {
    @Test
    fun create_withoutUnackedIds_emitsHeartbeatEnvelope() {
        val envelope = HeartbeatPacketFactory.create(nowMillis = 123L)

        assertEquals(ImEnvelopeCodec.PROTOCOL_VERSION, envelope.protocolVersion)
        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT, envelope.payloadCase)
        assertEquals(123L, envelope.heartbeat.clientTime)
        assertEquals(emptyList<String>(), envelope.heartbeat.unackedMessageIdsList)
    }

    @Test
    fun create_withUnackedIds_carriesIdList() {
        val envelope = HeartbeatPacketFactory.create(
            nowMillis = 123L,
            unackedMessageIds = listOf("m_1", "m_2")
        )

        assertEquals(listOf("m_1", "m_2"), envelope.heartbeat.unackedMessageIdsList)
    }

    @Test
    fun create_preservesSpecialCharactersInIds() {
        val envelope = HeartbeatPacketFactory.create(
            nowMillis = 123L,
            unackedMessageIds = listOf("m_\"x\"\\1")
        )

        assertEquals(listOf("m_\"x\"\\1"), envelope.heartbeat.unackedMessageIdsList)
    }
}
