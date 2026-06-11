package com.buyansong.im.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImPacketCodec {
    // B6 自定义二进制协议帧头：
    // magic(2) + version(1) + length(4) + cmd(4) + body(N) + crc(4)
    // 这里的 magic/version 也是 Wireshark 里最容易先定位的标记。
    const val MAGIC: Short = 0xCAFE.toShort()
    const val VERSION: Byte = 1

    private const val HEADER_SIZE = 11
    private const val CRC_SIZE = 4

    fun encode(packet: ImPacket): ByteArray {
        val body = packet.body
        // 协议全部字段都用大端序，和网络抓包里看到的字节顺序一致。
        val buffer = ByteBuffer
            .allocate(HEADER_SIZE + body.size + CRC_SIZE)
            .order(ByteOrder.BIG_ENDIAN)

        // Header:
        // 0..1  : magic = CA FE
        // 2     : version = 01
        // 3..6  : body length
        // 7..10 : command id
        buffer.putShort(MAGIC)
        buffer.put(packet.version)
        buffer.putInt(body.size)
        buffer.putInt(packet.cmd)
        buffer.put(body)

        // CRC 覆盖的是“CRC 之前的所有字节”，不包含尾部 4 字节 CRC 自身。
        val withoutCrc = buffer.position()
        buffer.putInt(Crc32.of(buffer.array(), 0, withoutCrc))
        return buffer.array()
    }

    fun decode(bytes: ByteArray): ImPacket {
        if (bytes.size < HEADER_SIZE + CRC_SIZE) {
            throw ProtocolException("Packet too short")
        }

        // 先校验尾部 CRC，再做字段解析，避免解析到损坏包。
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
