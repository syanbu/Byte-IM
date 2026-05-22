package com.codex.imserver.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AuthServiceTest {
    @Test
    public void registerStoresSaltedPasswordHashAndReturnsNestedTokenResponse() throws Exception {
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(store, new PasswordHasher(new FixedSaltGenerator("fixed-salt")), fixedTokenService());

        String json = service.register("13800138000", "P@ssw0rd");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        Optional<UserRecord> stored = store.findByPhone("13800138000");

        assertEquals(0, root.get("code").getAsInt());
        assertTrue(data.get("accessToken").getAsString().startsWith("mock-jwt."));
        assertTrue(data.get("refreshToken").getAsString().length() > 32);
        assertEquals(901_000L, data.get("accessExpiresAt").getAsLong());
        assertEquals(604_801_000L, data.get("refreshExpiresAt").getAsLong());
        assertEquals(data.get("accessToken").getAsString(), data.get("token").getAsString());
        assertEquals(data.get("accessExpiresAt").getAsLong(), data.get("expiresAt").getAsLong());
        assertEquals("13800138000", data.get("userId").getAsString());
        assertEquals("13800138000", data.get("username").getAsString());
        assertTrue(stored.isPresent());
        assertEquals("fixed-salt", stored.get().salt());
        assertNotEquals("P@ssw0rd", stored.get().passwordHash());
        assertFalse(stored.get().passwordHash().contains("P@ssw0rd"));
    }

    @Test
    public void loginRequiresExistingUserAndCorrectPassword() throws Exception {
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(store, new PasswordHasher(new FixedSaltGenerator("fixed-salt")), fixedTokenService());
        service.register("13800138001", "P@ssw0rd");

        JsonObject success = JsonParser.parseString(service.login("13800138001", "P@ssw0rd")).getAsJsonObject();
        JsonObject wrongPassword = JsonParser.parseString(service.login("13800138001", "wrong")).getAsJsonObject();
        JsonObject missing = JsonParser.parseString(service.login("13800138002", "P@ssw0rd")).getAsJsonObject();

        assertEquals(0, success.get("code").getAsInt());
        assertEquals(401, wrongPassword.get("code").getAsInt());
        assertEquals(404, missing.get("code").getAsInt());
    }

    @Test
    public void refreshIssuesNewAccessTokenWhenRefreshTokenIsValid() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(
                store,
                new PasswordHasher(new FixedSaltGenerator("fixed-salt")),
                new TokenService("test-secret", clock::now, 900_000L, 604_800_000L, () -> "refresh-token-a-with-enough-entropy-for-test")
        );
        JsonObject registered = JsonParser.parseString(service.register("13800138003", "P@ssw0rd")).getAsJsonObject();
        String refreshToken = registered.getAsJsonObject("data").get("refreshToken").getAsString();

        clock.now = 10_000L;
        JsonObject refreshed = JsonParser.parseString(service.refresh(refreshToken)).getAsJsonObject();

        assertEquals(0, refreshed.get("code").getAsInt());
        JsonObject data = refreshed.getAsJsonObject("data");
        assertTrue(data.get("accessToken").getAsString().startsWith("mock-jwt."));
        assertEquals("13800138003", data.get("userId").getAsString());
        assertEquals(910_000L, data.get("accessExpiresAt").getAsLong());
        assertEquals(refreshToken, data.get("refreshToken").getAsString());
    }

    @Test
    public void logoutRevokesRefreshToken() throws Exception {
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(
                store,
                new PasswordHasher(new FixedSaltGenerator("fixed-salt")),
                new TokenService("test-secret", () -> 1_000L, 900_000L, 604_800_000L, () -> "refresh-token-a-with-enough-entropy-for-test")
        );
        JsonObject registered = JsonParser.parseString(service.register("13800138004", "P@ssw0rd")).getAsJsonObject();
        String refreshToken = registered.getAsJsonObject("data").get("refreshToken").getAsString();

        JsonObject logout = JsonParser.parseString(service.logout(refreshToken)).getAsJsonObject();
        JsonObject refreshed = JsonParser.parseString(service.refresh(refreshToken)).getAsJsonObject();

        assertEquals(0, logout.get("code").getAsInt());
        assertEquals(401, refreshed.get("code").getAsInt());
        assertEquals("Refresh token expired or revoked", refreshed.get("message").getAsString());
    }

    @Test
    public void registerRejectsInvalidMainlandChinaPhone() throws Exception {
        AuthService service = new AuthService(new UserStore(tempDb()), new PasswordHasher(new FixedSaltGenerator("fixed-salt")), fixedTokenService());

        JsonObject result = JsonParser.parseString(service.register("12345", "P@ssw0rd")).getAsJsonObject();

        assertEquals(400, result.get("code").getAsInt());
        assertEquals("Invalid mainland China phone number", result.get("message").getAsString());
    }

    private Path tempDb() throws Exception {
        return Files.createTempFile("mock-im-users", ".db");
    }

    private TokenService fixedTokenService() {
        return new TokenService("test-secret", () -> 1_000L, 900_000L, 604_800_000L, () -> "refresh-token-a-with-enough-entropy-for-test");
    }

    private static final class MutableClock {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        private long now() {
            return now;
        }
    }
}
