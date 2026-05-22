package com.codex.imserver.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class TokenService {
    private static final Pattern MAINLAND_CHINA_PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final String DEFAULT_SECRET = "mock-im-local-development-secret";
    private static final long DEFAULT_ACCESS_TTL_MILLIS = 15 * 60 * 1000L;
    private static final long DEFAULT_REFRESH_TTL_MILLIS = 7 * 24 * 60 * 60 * 1000L;

    private final String secret;
    private final LongSupplier nowMillis;
    private final long accessTtlMillis;
    private final long refreshTtlMillis;
    private final Supplier<String> refreshTokenSupplier;

    public TokenService(String secret, LongSupplier nowMillis, long accessTtlMillis) {
        this(secret, nowMillis, accessTtlMillis, DEFAULT_REFRESH_TTL_MILLIS, TokenService::secureRefreshToken);
    }

    public TokenService(String secret, LongSupplier nowMillis, long accessTtlMillis, long refreshTtlMillis, Supplier<String> refreshTokenSupplier) {
        this.secret = secret;
        this.nowMillis = nowMillis;
        this.accessTtlMillis = accessTtlMillis;
        this.refreshTtlMillis = refreshTtlMillis;
        this.refreshTokenSupplier = refreshTokenSupplier;
    }

    public static TokenService defaultService() {
        return new TokenService(DEFAULT_SECRET, System::currentTimeMillis, DEFAULT_ACCESS_TTL_MILLIS);
    }

    public IssuedToken issue(String phone) {
        long issuedAt = nowMillis.getAsLong();
        long expiresAt = issuedAt + accessTtlMillis;

        JsonObject header = new JsonObject();
        header.addProperty("alg", "HS256");
        header.addProperty("typ", "JWT");

        JsonObject payload = new JsonObject();
        payload.addProperty("sub", phone);
        payload.addProperty("iat", issuedAt);
        payload.addProperty("exp", expiresAt);

        String headerPart = encode(header.toString().getBytes(StandardCharsets.UTF_8));
        String payloadPart = encode(payload.toString().getBytes(StandardCharsets.UTF_8));
        String signedPart = headerPart + "." + payloadPart;
        String signature = sign(signedPart);
        return new IssuedToken("mock-jwt." + signedPart + "." + signature, expiresAt);
    }

    public IssuedRefreshToken issueRefreshToken() {
        String token = refreshTokenSupplier.get();
        return new IssuedRefreshToken(token, hashRefreshToken(token), nowMillis.getAsLong() + refreshTtlMillis);
    }

    public long currentTimeMillis() {
        return nowMillis.getAsLong();
    }

    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 4 || !"mock-jwt".equals(parts[0])) {
            return Optional.empty();
        }

        String signedPart = parts[1] + "." + parts[2];
        String expectedSignature = sign(signedPart);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        try {
            JsonObject payload = JsonParser
                    .parseString(new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String phone = payload.get("sub").getAsString();
            long expiresAt = payload.get("exp").getAsLong();
            if (!MAINLAND_CHINA_PHONE.matcher(phone).matches() || expiresAt <= nowMillis.getAsLong()) {
                return Optional.empty();
            }
            return Optional.of(phone);
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign mock token", error);
        }
    }

    public String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash refresh token", error);
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String secureRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedToken(String token, long expiresAtMillis) {
    }

    public record IssuedRefreshToken(String token, String hash, long expiresAtMillis) {
    }
}
