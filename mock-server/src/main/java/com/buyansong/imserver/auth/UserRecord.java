package com.buyansong.imserver.auth;

public record UserRecord(
        String phone,
        String salt,
        String passwordHash,
        String nickname,
        String avatarUrl,
        String avatarObjectKey,
        long avatarUpdatedAt,
        long updatedAt,
        long createdAt,
        String gender,
        String signature,
        long profileVersion
) {
    public UserRecord(String phone, String salt, String passwordHash, long createdAt) {
        this(phone, salt, passwordHash, phone, null, null, 0L, createdAt, createdAt, null, null, 0L);
    }
}
