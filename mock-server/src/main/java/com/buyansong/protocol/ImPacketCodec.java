package com.buyansong.imserver.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public final class ImPacketCodec {
    public static final short MAGIC = (short) 0xCAFE;
    public static final byte VERSION = 1;

    private static final int HEADER_SIZE = 11;
    private static final int CRC_SIZE = 4;

    private ImPacketCodec() {
    }

    public static byte[] encode(ImPacket packet) {
        byte[] body = packet.body();
        ByteBuffer buffer = ByteBuffer
                .allocate(HEADER_SIZE + body.length + CRC_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putShort(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(body.length);
        buffer.putInt(packet.cmd());
        buffer.put(body);
        int crcOffset = buffer.position();
        buffer.putInt(crc32(buffer.array(), 0, crcOffset));
        return buffer.array();
    }

    public static ImPacket decode(byte[] bytes) {
        if (bytes.length < HEADER_SIZE + CRC_SIZE) {
            throw new ProtocolException("Packet too short");
        }
        ByteBuffer crcBuffer = ByteBuffer.wrap(bytes, bytes.length - CRC_SIZE, CRC_SIZE).order(ByteOrder.BIG_ENDIAN);
        int expectedCrc = crcBuffer.getInt();
        int actualCrc = crc32(bytes, 0, bytes.length - CRC_SIZE);
        if (expectedCrc != actualCrc) {
            throw new ProtocolException("Invalid CRC");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        short magic = buffer.getShort();
        if (magic != MAGIC) {
            throw new ProtocolException("Invalid magic");
        }
        byte version = buffer.get();
        if (version != VERSION) {
            throw new ProtocolException("Unsupported version");
        }
        int length = buffer.getInt();
        if (length < 0 || bytes.length != HEADER_SIZE + length + CRC_SIZE) {
            throw new ProtocolException("Invalid body length");
        }
        int cmd = buffer.getInt();
        byte[] body = new byte[length];
        buffer.get(body);
        return new ImPacket(cmd, body);
    }

    private static int crc32(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }
}
