package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.imserver.ImServerLogger;

public interface OutboundClient {
    void send(ImEnvelope envelope);

    default void recordStatus(String status) {
        ImServerLogger.log("[IM] STATUS %s", status);
    }
}
