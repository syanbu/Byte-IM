package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatPacketFactoryTest {
    @Test
    fun create_withoutUnackedIds_emitsEmptyArray() {
        val packet = HeartbeatPacketFactory.create(nowMillis = 123L)

        assertEquals(ImCommand.HEARTBEAT.value, packet.cmd)
        assertEquals("""{"clientTime":123,"unackedMessageIds":[]}""", packet.body.decodeToString())
    }

    @Test
    fun create_withUnackedIds_emitsIdArray() {
        val packet = HeartbeatPacketFactory.create(
            nowMillis = 123L,
            unackedMessageIds = listOf("m_1", "m_2")
        )

        assertEquals(
            """{"clientTime":123,"unackedMessageIds":["m_1","m_2"]}""",
            packet.body.decodeToString()
        )
    }

    @Test
    fun create_escapesQuotesAndBackslashesInIds() {
        val packet = HeartbeatPacketFactory.create(
            nowMillis = 123L,
            unackedMessageIds = listOf("m_\"x\"\\1")
        )

        assertEquals(
            """{"clientTime":123,"unackedMessageIds":["m_\"x\"\\1"]}""",
            packet.body.decodeToString()
        )
    }
}
