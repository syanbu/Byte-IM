package com.codex.imserver.session;

import com.codex.imserver.protocol.ImPacket;

public interface OutboundClient {
    void send(ImPacket packet);
}
