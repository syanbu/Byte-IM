package com.buyansong.imserver.protocol;

import com.buyansong.im.protocol.v2.ImEnvelope;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Validation wrapper around the generated Protobuf {@link ImEnvelope} codec.
 * Mirrors the Android {@code ImEnvelopeCodec}: same size limit, same version
 * check, same payload-presence check, same {@link ProtocolException} surface.
 */
public final class ImEnvelopeCodec {
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_ENVELOPE_BYTES = 64 * 1024;

    private ImEnvelopeCodec() {
    }

    public static byte[] encode(ImEnvelope envelope) {
        validate(envelope);
        byte[] bytes = envelope.toByteArray();
        if (bytes.length > MAX_ENVELOPE_BYTES) {
            throw new ProtocolException("Envelope too large: " + bytes.length + " bytes > " + MAX_ENVELOPE_BYTES);
        }
        return bytes;
    }

    public static ImEnvelope decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ProtocolException("Empty envelope");
        }
        if (bytes.length > MAX_ENVELOPE_BYTES) {
            throw new ProtocolException("Envelope too large: " + bytes.length + " bytes > " + MAX_ENVELOPE_BYTES);
        }
        ImEnvelope envelope;
        try {
            envelope = ImEnvelope.parseFrom(bytes);
        } catch (InvalidProtocolBufferException error) {
            throw new ProtocolException("Malformed envelope: " + error.getMessage());
        }
        validate(envelope);
        return envelope;
    }

    private static void validate(ImEnvelope envelope) {
        if (envelope.getProtocolVersion() != PROTOCOL_VERSION) {
            throw new ProtocolException("Unsupported protocol version: " + envelope.getProtocolVersion());
        }
        if (envelope.getPayloadCase() == ImEnvelope.PayloadCase.PAYLOAD_NOT_SET) {
            throw new ProtocolException("Envelope payload is not set");
        }
    }
}
