package com.buyansong.imserver.protocol;

import com.buyansong.im.protocol.v2.ChatMessagePayload;
import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.Heartbeat;
import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.im.protocol.v2.MessageType;
import com.buyansong.im.protocol.v2.SendMessage;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ImEnvelopeCodecTest {

    @Test
    public void heartbeatRoundTrips() {
        ImEnvelope envelope = ImEnvelope.newBuilder()
                .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
                .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
                .build();

        ImEnvelope decoded = ImEnvelopeCodec.decode(ImEnvelopeCodec.encode(envelope));

        assertEquals(envelope, decoded);
    }

    @Test
    public void goldenHeartbeatBytesDecode() {
        // Same fixture asserted by the Android codec test; it is the
        // wire-compatibility guarantee between the two generated codebases.
        byte[] golden = hexToBytes("0802a20102087b");

        ImEnvelope decoded = ImEnvelopeCodec.decode(golden);

        assertEquals(ImEnvelopeCodec.PROTOCOL_VERSION, decoded.getProtocolVersion());
        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT, decoded.getPayloadCase());
        assertEquals(123L, decoded.getHeartbeat().getClientTime());
        assertArrayEquals(golden, ImEnvelopeCodec.encode(decoded));
    }

    @Test
    public void goldenHeartbeatEncodeMatchesFixture() {
        ImEnvelope envelope = ImEnvelope.newBuilder()
                .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
                .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
                .build();

        assertArrayEquals(hexToBytes("0802a20102087b"), ImEnvelopeCodec.encode(envelope));
    }

    @Test
    public void decodeRejectsEmptyBytes() {
        assertThrows(ProtocolException.class, () -> ImEnvelopeCodec.decode(new byte[0]));
    }

    @Test
    public void decodeRejectsMalformedBytes() {
        assertThrows(ProtocolException.class, () -> ImEnvelopeCodec.decode(new byte[]{0x0A, 0x7F, 0x01}));
    }

    @Test
    public void decodeRejectsVersionOne() {
        ImEnvelope envelope = ImEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
                .build();

        assertThrows(ProtocolException.class, () -> ImEnvelopeCodec.decode(envelope.toByteArray()));
    }

    @Test
    public void decodeRejectsPayloadNotSet() {
        ImEnvelope envelope = ImEnvelope.newBuilder()
                .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
                .build();

        assertThrows(ProtocolException.class, () -> ImEnvelopeCodec.decode(envelope.toByteArray()));
    }

    @Test
    public void decodeRejectsEnvelopeOver64KiB() {
        assertThrows(ProtocolException.class,
                () -> ImEnvelopeCodec.decode(new byte[ImEnvelopeCodec.MAX_ENVELOPE_BYTES + 1]));
    }

    @Test
    public void encodeRejectsEnvelopeOver64KiB() {
        ImEnvelope envelope = ImEnvelope.newBuilder()
                .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
                .setSendMessage(SendMessage.newBuilder().setMessage(
                        ChatMessagePayload.newBuilder()
                                .setMessageId("m-1")
                                .setConversationId("single:a:b")
                                .setConversationType(ConversationType.CONVERSATION_TYPE_SINGLE)
                                .setSenderId("a")
                                .setReceiverId("b")
                                .setMessageType(MessageType.MESSAGE_TYPE_TEXT)
                                .setContent("x".repeat(ImEnvelopeCodec.MAX_ENVELOPE_BYTES))))
                .build();

        assertThrows(ProtocolException.class, () -> ImEnvelopeCodec.encode(envelope));
    }

    @Test
    public void encodeRejectsWrongVersion() {
        ImEnvelope envelope = ImEnvelope.newBuilder()
                .setProtocolVersion(1)
                .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
                .build();

        assertThrows(ProtocolException.class, () -> ImEnvelopeCodec.encode(envelope));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
