package com.buyansong.imserver.push;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class PushService {
    private final PushTokenStore tokenStore;
    private final PushNotificationStore notificationStore;
    private final LongSupplier clock;

    public PushService(PushTokenStore tokenStore, PushNotificationStore notificationStore, LongSupplier clock) {
        this.tokenStore = tokenStore;
        this.notificationStore = notificationStore;
        this.clock = clock;
    }

    public String registerToken(String userId, String pushToken, String platform, String deviceId) {
        if (pushToken == null || pushToken.isBlank()) {
            return failure(400, "pushToken required");
        }
        long now = clock.getAsLong();
        tokenStore.registerToken(
                userId,
                pushToken,
                platform == null || platform.isBlank() ? "android" : platform,
                deviceId,
                now
        );
        JsonObject data = new JsonObject();
        data.addProperty("registeredAt", now);
        return success(data);
    }

    public String unregisterToken(String userId) {
        tokenStore.unregisterToken(userId);
        return success(new JsonObject());
    }

    public String pending(String userId, long sincePushId, int limit) {
        List<PushNotificationStore.PushNotificationRecord> pending = notificationStore.pending(
                userId,
                Math.max(0L, sincePushId),
                limit <= 0 ? 50 : Math.min(limit, 100)
        );
        JsonArray rows = new JsonArray();
        long latestPushId = Math.max(0L, sincePushId);
        for (PushNotificationStore.PushNotificationRecord record : pending) {
            latestPushId = Math.max(latestPushId, record.pushId());
            JsonObject row = new JsonObject();
            row.addProperty("pushId", record.pushId());
            row.addProperty("senderId", record.senderId());
            row.addProperty("conversationId", record.conversationId());
            row.addProperty("messageId", record.messageId());
            row.addProperty("messageType", record.messageType());
            row.addProperty("preview", record.preview());
            row.addProperty("serverSeq", record.serverSeq());
            row.addProperty("serverTime", record.serverTime());
            rows.add(row);
        }
        JsonObject data = new JsonObject();
        data.add("pending", rows);
        data.addProperty("latestPushId", latestPushId);
        return success(data);
    }

    public String ack(String userId, JsonArray pushIds) {
        List<Long> ids = new ArrayList<>();
        if (pushIds != null) {
            pushIds.forEach(id -> {
                if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
                    ids.add(id.getAsLong());
                }
            });
        }
        int ackedCount = notificationStore.ack(userId, ids, clock.getAsLong());
        JsonObject data = new JsonObject();
        data.addProperty("ackedCount", ackedCount);
        return success(data);
    }

    public String failure(int code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", code);
        root.addProperty("message", message);
        return root.toString();
    }

    private String success(JsonObject data) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }
}
