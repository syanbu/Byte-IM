package com.codex.imserver.auth;

public record UserRecord(
        String phone,
        String salt,
        String passwordHash,
        long createdAt
) {
}
