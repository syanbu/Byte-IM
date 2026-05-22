package com.codex.imserver.protocol;

public record ImPacket(int cmd, byte[] body) {
}
