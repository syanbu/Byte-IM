package com.buyansong.im.connection

import com.buyansong.im.protocol.v2.AuthFailureReason
import com.buyansong.im.protocol.v2.ImEnvelope

object ConnectionStateReducer {
    fun stateAfterIncomingPacket(envelope: ImEnvelope): ConnectionState? {
        return when (envelope.payloadCase) {
            ImEnvelope.PayloadCase.AUTH_ACK -> ConnectionState.Authenticated
            ImEnvelope.PayloadCase.AUTH_NACK -> ConnectionState.Failed(authNackReason(envelope))
            else -> null
        }
    }

    private fun authNackReason(envelope: ImEnvelope): String {
        return when (val reason = envelope.authNack.reason) {
            AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_EXPIRED,
            AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_INVALID,
            AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_MISSING ->
                reason.name.removePrefix("AUTH_FAILURE_REASON_")
            AuthFailureReason.AUTH_FAILURE_REASON_UNSPECIFIED,
            AuthFailureReason.UNRECOGNIZED -> "auth rejected"
        }
    }
}
