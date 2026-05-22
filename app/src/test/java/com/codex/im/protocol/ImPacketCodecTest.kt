package com.codex.im.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImPacketCodecTest {
    @Test
    fun encodeThenDecodeKeepsFields() {
        val packet = ImPacket(cmd = ImCommand.SEND_MESSAGE.value, body = """{"content":"hello"}""".toByteArray())

        val decoded = ImPacketCodec.decode(ImPacketCodec.encode(packet))

        assertEquals(ImCommand.SEND_MESSAGE.value, decoded.cmd)
        assertEquals(ImPacketCodec.VERSION, decoded.version)
        assertArrayEquals(packet.body, decoded.body)
    }

    @Test
    fun rejectsInvalidMagic() {
        val bytes = ImPacketCodec.encode(ImPacket(cmd = ImCommand.HEARTBEAT.value, body = ByteArray(0)))
        bytes[0] = 0x00

        assertThrows(ProtocolException::class.java) {
            ImPacketCodec.decode(bytes)
        }
    }

    @Test
    fun rejectsInvalidLength() {
        val bytes = ImPacketCodec.encode(ImPacket(cmd = ImCommand.HEARTBEAT.value, body = "abc".toByteArray()))
        bytes[6] = 0x10

        assertThrows(ProtocolException::class.java) {
            ImPacketCodec.decode(bytes)
        }
    }

    @Test
    fun rejectsInvalidCrc() {
        val bytes = ImPacketCodec.encode(ImPacket(cmd = ImCommand.HEARTBEAT.value, body = "abc".toByteArray()))
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()

        assertThrows(ProtocolException::class.java) {
            ImPacketCodec.decode(bytes)
        }
    }
}
