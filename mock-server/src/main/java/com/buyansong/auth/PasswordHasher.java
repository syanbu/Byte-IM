package com.buyansong.imserver.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

public final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;

    private final SaltGenerator saltGenerator;

    public PasswordHasher(SaltGenerator saltGenerator) {
        this.saltGenerator = saltGenerator;
    }

    public HashedPassword hash(String password) {
        String salt = saltGenerator.nextSalt();
        return new HashedPassword(salt, hash(password, salt));
    }

    public boolean verify(String password, String salt, String expectedHash) {
        return hash(password, salt).equals(expectedHash);
    }

    private String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(),
                    salt.getBytes(StandardCharsets.UTF_8),
                    ITERATIONS,
                    KEY_LENGTH_BITS
            );
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return Base64.getEncoder().encodeToString(encoded);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to hash password", error);
        }
    }

    public record HashedPassword(String salt, String hash) {
    }
}
