package com.buyansong.imserver.protocol;

public record ImPacket(int cmd, byte[] body) {
}
