package com.buyansong.imserver.auth;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecureSaltGenerator implements SaltGenerator {
    private final SecureRandom random = new SecureRandom();

    @Override
    public String nextSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
}
