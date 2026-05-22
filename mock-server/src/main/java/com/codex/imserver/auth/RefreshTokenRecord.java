package com.codex.imserver.auth;

public record RefreshTokenRecord(String phone, String tokenHash, long expiresAt, Long revokedAt) {
}
