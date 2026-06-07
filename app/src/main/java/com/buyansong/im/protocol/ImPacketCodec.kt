package com.buyansong.im.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImPacketCodec {
    const val MAGIC: Short = 0xCAFE.toShort()
    const val VERSION: Byte = 1

    private const val HEADER_SIZE = 11
    private const val CRC_SIZE = 4

    fun encode(packet: ImPacket): ByteArray {
        val body = packet.body
        val buffer = ByteBuffer
            .allocate(HEADER_SIZE + body.size + CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putShort(MAGIC)
        buffer.put(packet.version)
        buffer.putInt(body.size)
        buffer.putInt(packet.cmd)
        buffer.put(body)

        val withoutCrc = buffer.position()
        buffer.putInt(Crc32.of(buffer.array(), 0, withoutCrc))
        return buffer.array()
    }

    fun decode(bytes: ByteArray): ImPacket {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) {
            throw ProtocolException("Packet too short")
        }

        val expectedCrc = ByteBuffer.wrap(bytes, bytes.size - CRC_SIZE, CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        val actualCrc = Crc32.of(bytes, 0, bytes.size - CRC_SIZE)
        if (expectedCrc != actualCrc) {
            throw ProtocolException("Invalid CRC")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.short
        if (magic != MAGIC) {
            throw ProtocolException("Invalid magic")
        }

        val version = buffer.get()
        if (version != VERSION) {
            throw ProtocolException("Unsupported version")
        }

        val length = buffer.int
        if (length < 0 || bytes.size != HEADER_SIZE + length + CRC_SIZE) {
            throw ProtocolException("Invalid body length")
        }

        val cmd = buffer.int
        val body = ByteArray(length)
        buffer.get(body)
        return ImPacket(cmd = cmd, body = body, version = version)
    }
}
