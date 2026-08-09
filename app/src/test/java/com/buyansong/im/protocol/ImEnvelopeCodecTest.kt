package com.buyansong.im.protocol

import com.buyansong.im.protocol.v2.ChatMessagePayload
import com.buyansong.im.protocol.v2.ConversationType
import com.buyansong.im.protocol.v2.Heartbeat
import com.buyansong.im.protocol.v2.ImEnvelope
import com.buyansong.im.protocol.v2.MessageType
import com.buyansong.im.protocol.v2.SendMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImEnvelopeCodecTest {

    @Test
    fun heartbeatRoundTrips() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
            .build()

        val decoded = ImEnvelopeCodec.decode(ImEnvelopeCodec.encode(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun goldenHeartbeatBytesDecode() {
        // Same fixture asserted by the mock-server codec test; it is the
        // wire-compatibility guarantee between the two generated codebases.
        val golden = hexToBytes("0802a20102087b")

        val decoded = ImEnvelopeCodec.decode(golden)

        assertEquals(ImEnvelopeCodec.PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT, decoded.payloadCase)
        assertEquals(123L, decoded.heartbeat.clientTime)

        assertArrayEquals(golden, ImEnvelopeCodec.encode(decoded))
    }

    @Test
    fun goldenHeartbeatEncodeMatchesFixture() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
            .build()

        assertArrayEquals(hexToBytes("0802a20102087b"), ImEnvelopeCodec.encode(envelope))
    }

    @Test
    fun decodeRejectsEmptyBytes() {
        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.decode(ByteArray(0))
        }
    }

    @Test
    fun decodeRejectsMalformedBytes() {
        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.decode(byteArrayOf(0x0A, 0x7F, 0x01))
        }
    }

    @Test
    fun decodeRejectsVersionOne() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(1)
            .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
            .build()

        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.decode(envelope.toByteArray())
        }
    }

    @Test
    fun decodeRejectsPayloadNotSet() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .build()

        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.decode(envelope.toByteArray())
        }
    }

    @Test
    fun decodeRejectsEnvelopeOver64KiB() {
        val oversized = ByteArray(ImEnvelopeCodec.MAX_ENVELOPE_BYTES + 1)

        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.decode(oversized)
        }
    }

    @Test
    fun encodeRejectsEnvelopeOver64KiB() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .setSendMessage(
                SendMessage.newBuilder().setMessage(
                    ChatMessagePayload.newBuilder()
                        .setMessageId("m-1")
                        .setConversationId("single:a:b")
                        .setConversationType(ConversationType.CONVERSATION_TYPE_SINGLE)
                        .setSenderId("a")
                        .setReceiverId("b")
                        .setMessageType(MessageType.MESSAGE_TYPE_TEXT)
                        .setContent("x".repeat(ImEnvelopeCodec.MAX_ENVELOPE_BYTES))
                )
            )
            .build()

        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.encode(envelope)
        }
    }

    @Test
    fun encodeRejectsWrongVersion() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(1)
            .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
            .build()

        assertThrows(ProtocolException::class.java) {
            ImEnvelopeCodec.encode(envelope)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
