package com.buyansong.imserver.push;

import com.google.gson.JsonObject;

import java.util.List;

public interface PushNotificationStore {
    boolean enqueueIfAbsent(String userId, JsonObject message, long createdAt);

    List<PushNotificationRecord> pending(String userId, long sincePushId, int limit);

    int ack(String userId, List<Long> pushIds, long deliveredAt);

    record PushNotificationRecord(
            long pushId,
            String userId,
            String senderId,
            String conversationId,
            String messageId,
            String messageType,
            String preview,
            long serverSeq,
            long serverTime,
            long createdAt
    ) {
    }
}
