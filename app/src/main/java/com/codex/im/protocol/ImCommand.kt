package com.codex.im.protocol

enum class ImCommand(val value: Int) {
    AUTH(1),
    AUTH_ACK(2),
    HEARTBEAT(3),
    HEARTBEAT_ACK(4),
    SEND_MESSAGE(10),
    MESSAGE_ACK(11),
    RECEIVE_MESSAGE(12),
    READ_ACK(13),
    DELIVERY_ACK(14),
    HISTORY_QUERY(20),
    HISTORY_RESULT(21);

    companion object {
        fun fromValue(value: Int): ImCommand? = entries.firstOrNull { it.value == value }
    }
}
