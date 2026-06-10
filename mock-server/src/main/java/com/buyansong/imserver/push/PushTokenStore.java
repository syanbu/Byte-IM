package com.buyansong.imserver.push;

import java.util.Optional;

public interface PushTokenStore {
    void registerToken(String userId, String pushToken, String platform, String deviceId, long updatedAt);

    void unregisterToken(String userId);

    Optional<PushTokenRecord> findByUserId(String userId);

    record PushTokenRecord(
            String userId,
            String pushToken,
            String platform,
            String deviceId,
            long updatedAt
    ) {
    }
}
