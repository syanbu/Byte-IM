package com.buyansong.imserver.groupread;

public record GroupReadCursor(
        String groupId,
        String readerId,
        long readUpToServerSeq,
        long readAt
) {
}
