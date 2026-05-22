package com.codex.imserver.protocol;

public enum ImCommand {
    AUTH(1),
    AUTH_ACK(2),
    HEARTBEAT(3),
    HEARTBEAT_ACK(4),
    SEND_MESSAGE(10),
    MESSAGE_ACK(11),
    RECEIVE_MESSAGE(12),
    READ_ACK(13),
    HISTORY_QUERY(20),
    HISTORY_RESULT(21);

    private final int value;

    ImCommand(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
