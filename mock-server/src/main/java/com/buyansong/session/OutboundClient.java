package com.buyansong.imserver.session;

import com.buyansong.imserver.ImServerLogger;
import com.buyansong.imserver.protocol.ImPacket;

public interface OutboundClient {
    void send(ImPacket packet);

    default void recordStatus(String status) {
        ImServerLogger.log("[IM] STATUS %s", status);
    }
}
