package com.buyansong.imserver.protocol;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ImPacketCodecTest {
    @Test
    public void encodeThenDecodeKeepsFields() {
        ImPacket packet = new ImPacket(ImCommand.SEND_MESSAGE.value(), "{\"content\":\"hello\"}".getBytes(StandardCharsets.UTF_8));

        ImPacket decoded = ImPacketCodec.decode(ImPacketCodec.encode(packet));

        assertEquals(ImCommand.SEND_MESSAGE.value(), decoded.cmd());
        assertArrayEquals(packet.body(), decoded.body());
    }

    @Test(expected = ProtocolException.class)
    public void rejectsInvalidCrc() {
        byte[] bytes = ImPacketCodec.encode(new ImPacket(ImCommand.HEARTBEAT.value(), "ping".getBytes(StandardCharsets.UTF_8)));
        bytes[bytes.length - 1] = (byte) (bytes[bytes.length - 1] + 1);

        ImPacketCodec.decode(bytes);
    }
}
