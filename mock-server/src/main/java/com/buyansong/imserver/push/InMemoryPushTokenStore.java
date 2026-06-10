package com.buyansong.imserver.push;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryPushTokenStore implements PushTokenStore {
    private final ConcurrentMap<String, PushTokenRecord> tokensByUserId = new ConcurrentHashMap<>();

    @Override
    public void registerToken(String userId, String pushToken, String platform, String deviceId, long updatedAt) {
        tokensByUserId.put(userId, new PushTokenRecord(userId, pushToken, platform, deviceId, updatedAt));
    }

    @Override
    public void unregisterToken(String userId) {
        tokensByUserId.remove(userId);
    }

    @Override
    public Optional<PushTokenRecord> findByUserId(String userId) {
        return Optional.ofNullable(tokensByUserId.get(userId));
    }
}
