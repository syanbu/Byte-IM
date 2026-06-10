package com.buyansong.imserver.push;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryPushNotificationStore implements PushNotificationStore {
    private final AtomicLong nextPushId = new AtomicLong(1L);
    private final Map<String, PushNotificationRecord> recordsByUserAndMessage = new ConcurrentHashMap<>();
    private final Map<Long, PushNotificationRecord> recordsByPushId = new ConcurrentHashMap<>();
    private final Map<Long, Long> deliveredAtByPushId = new ConcurrentHashMap<>();

    @Override
    public boolean enqueueIfAbsent(String userId, JsonObject message, long createdAt) {
        String messageId = readString(message, "messageId", "");
        String key = userId + ":" + messageId;
        PushNotificationRecord record = fromMessage(nextPushId.getAndIncrement(), userId, message, createdAt);
        PushNotificationRecord existing = recordsByUserAndMessage.putIfAbsent(key, record);
        if (existing != null) {
            return false;
        }
        recordsByPushId.put(record.pushId(), record);
        return true;
    }

    @Override
    public List<PushNotificationRecord> pending(String userId, long sincePushId, int limit) {
        int safeLimit = Math.max(1, limit);
        return recordsByPushId.values().stream()
                .filter(record -> record.userId().equals(userId))
                .filter(record -> record.pushId() > sincePushId)
                .filter(record -> !deliveredAtByPushId.containsKey(record.pushId()))
                .sorted(Comparator.comparingLong(PushNotificationRecord::pushId))
                .limit(safeLimit)
                .toList();
    }

    @Override
    public int ack(String userId, List<Long> pushIds, long deliveredAt) {
        int count = 0;
        for (Long pushId : pushIds) {
            PushNotificationRecord record = recordsByPushId.get(pushId);
            if (record != null && record.userId().equals(userId) && !deliveredAtByPushId.containsKey(pushId)) {
                deliveredAtByPushId.put(pushId, deliveredAt);
                count++;
            }
        }
        return count;
    }

    static PushNotificationRecord fromMessage(long pushId, String userId, JsonObject message, long createdAt) {
        String messageType = readString(message, "messageType", "TEXT");
        return new PushNotificationRecord(
                pushId,
                userId,
                readString(message, "senderId", ""),
                readString(message, "conversationId", ""),
                readString(message, "messageId", ""),
                messageType,
                "IMAGE".equals(messageType) ? "[图片]" : readString(message, "content", ""),
                readLong(message, "serverSeq", 0L),
                readLong(message, "serverTime", createdAt),
                createdAt
        );
    }

    private static String readString(JsonObject json, String name, String fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : fallback;
    }

    private static long readLong(JsonObject json, String name, long fallback) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsLong() : fallback;
    }
}
