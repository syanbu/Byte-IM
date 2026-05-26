package com.codex.imserver.session;

import com.codex.imserver.ImServerLogger;
import com.codex.imserver.protocol.ImPacket;

public interface OutboundClient {
    void send(ImPacket packet);

    default void recordStatus(String status) {
        ImServerLogger.log("[IM] STATUS %s", status);
    }
}
