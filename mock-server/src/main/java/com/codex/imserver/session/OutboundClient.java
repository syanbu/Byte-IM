package com.codex.imserver.session;

import com.codex.imserver.protocol.ImPacket;

public interface OutboundClient {
    void send(ImPacket packet);

    default void recordStatus(String status) {
        System.out.printf("[IM] STATUS %s%n", status);
    }
}
