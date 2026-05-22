package com.codex.im.connection

import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacketCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthPacketFactoryTest {
    @Test
    fun createsAuthPacketWithTokenBody() {
        val packet = AuthPacketFactory.create("jwt-token")

        assertEquals(ImCommand.AUTH.value, packet.cmd)
        val body = packet.body.decodeToString()
        assertTrue(body.contains("jwt-token"))
        assertEquals(packet, ImPacketCodec.decode(ImPacketCodec.encode(packet)))
    }
}
