package com.buyansong.im.protocol

import com.buyansong.im.protocol.v2.ImEnvelope
import com.google.protobuf.InvalidProtocolBufferException

/**
 * Validation wrapper around the generated Protobuf [ImEnvelope] codec.
 *
 * Enforces the wire-format invariants of protocol version 2 (one binary
 * WebSocket message = one envelope): decoded/encoded size limit,
 * `protocol_version == 2`, and a set `oneof payload`. All failures surface
 * as [ProtocolException] so callers keep a single failure path.
 */
object ImEnvelopeCodec {
    const val PROTOCOL_VERSION = 2
    const val MAX_ENVELOPE_BYTES = 64 * 1024

    fun encode(envelope: ImEnvelope): ByteArray {
        validate(envelope)
        val bytes = envelope.toByteArray()
        if (bytes.size > MAX_ENVELOPE_BYTES) {
            throw ProtocolException("Envelope too large: ${bytes.size} bytes > $MAX_ENVELOPE_BYTES")
        }
        return bytes
    }

    fun decode(bytes: ByteArray): ImEnvelope {
        if (bytes.isEmpty()) {
            throw ProtocolException("Empty envelope")
        }
        if (bytes.size > MAX_ENVELOPE_BYTES) {
            throw ProtocolException("Envelope too large: ${bytes.size} bytes > $MAX_ENVELOPE_BYTES")
        }
        val envelope = try {
            ImEnvelope.parseFrom(bytes)
        } catch (error: InvalidProtocolBufferException) {
            throw ProtocolException("Malformed envelope: ${error.message}")
        }
        validate(envelope)
        return envelope
    }

    private fun validate(envelope: ImEnvelope) {
        if (envelope.protocolVersion != PROTOCOL_VERSION) {
            throw ProtocolException("Unsupported protocol version: ${envelope.protocolVersion}")
        }
        if (envelope.payloadCase == ImEnvelope.PayloadCase.PAYLOAD_NOT_SET) {
            throw ProtocolException("Envelope payload is not set")
        }
    }
}
