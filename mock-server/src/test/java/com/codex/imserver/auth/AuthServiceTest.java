package com.codex.imserver.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertEquals("13800138000", data.get("phone").getAsString());
        assertEquals("13800138000", data.get("nickname").getAsString());
        assertTrue(data.get("avatarUrl").isJsonNull());
        assertTrue(stored.isPresent());
        assertEquals("fixed-salt", stored.get().salt());
        assertEquals("13800138000", stored.get().nickname());
        assertNotEquals("P@ssw0rd", stored.get().passwordHash());
        assertFalse(stored.get().passwordHash().contains("P@ssw0rd"));
    }

    @Test
    public void updateProfileChangesNicknameAndAvatarUrl() throws Exception {
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(store, new PasswordHasher(new FixedSaltGenerator("fixed-salt")), fixedTokenService());
        service.register("13800138005", "P@ssw0rd");

        JsonObject updated = JsonParser.parseString(
                service.updateProfile(
                        "13800138005",
                        "Syan",
                        "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138005/2000.jpg",
                        "avatars/13800138005/2000.jpg",
                        null,
                        null
                )
        ).getAsJsonObject();
        JsonObject data = updated.getAsJsonObject("data");

        assertEquals(0, updated.get("code").getAsInt());
        assertEquals("Syan", data.get("nickname").getAsString());
        assertEquals("https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138005/2000.jpg", data.get("avatarUrl").getAsString());
        assertEquals("avatars/13800138005/2000.jpg", data.get("avatarObjectKey").getAsString());
    }

    @Test
    public void loginKeepsUsernameAsPhoneAfterNicknameChanges() throws Exception {
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(store, new PasswordHasher(new FixedSaltGenerator("fixed-salt")), fixedTokenService());
        service.register("13900139000", "P@ssw0rd");
        service.updateProfile("13900139000", "Megumi", null, null, null, null);

        JsonObject login = JsonParser.parseString(service.login("13900139000", "P@ssw0rd")).getAsJsonObject();
        JsonObject data = login.getAsJsonObject("data");

        assertEquals(0, login.get("code").getAsInt());
        assertEquals("13900139000", data.get("username").getAsString());
        assertEquals("Megumi", data.get("nickname").getAsString());
    }

    @Test
    public void batchProfilesReturnsKnownUsers() throws Exception {
        UserStore store = new UserStore(tempDb());
        AuthService service = new AuthService(store, new PasswordHasher(new FixedSaltGenerator("fixed-salt")), fixedTokenService());
        service.register("13800138006", "P@ssw0rd");
        service.register("13900139006", "P@ssw0rd");
        service.updateProfile("13900139006", "Megumi", null, null, null, null);

        JsonObject result = JsonParser.parseString(service.profiles(java.util.List.of("13800138006", "13900139006", "13700137006"))).getAsJsonObject();

        assertEquals(0, result.get("code").getAsInt());
        assertEquals(2, result.getAsJsonObject("data").getAsJsonArray("profiles").size());
        assertEquals("13800138006", result.getAsJsonObject("data").getAsJsonArray("profiles").get(0).getAsJsonObject().get("nickname").getAsString());
        assertEquals("Megumi", result.getAsJsonObject("data").getAsJsonArray("profiles").get(1).getAsJsonObject().get("nickname").getAsString());
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
    public void refreshIssuesNewTokenPairAndRevokesPreviousRefreshToken() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        UserStore store = new UserStore(tempDb());
        AtomicInteger refreshTokenIndex = new AtomicInteger();
        AuthService service = new AuthService(
                store,
                new PasswordHasher(new FixedSaltGenerator("fixed-salt")),
                new TokenService(
                        "test-secret",
                        clock::now,
                        900_000L,
                        604_800_000L,
                        () -> refreshTokenIndex.getAndIncrement() == 0
                                ? "refresh-token-a-with-enough-entropy-for-test"
                                : "refresh-token-b-with-enough-entropy-for-test"
                )
        );
        JsonObject registered = JsonParser.parseString(service.register("13800138003", "P@ssw0rd")).getAsJsonObject();
        String oldRefreshToken = registered.getAsJsonObject("data").get("refreshToken").getAsString();

        clock.now = 10_000L;
        JsonObject refreshed = JsonParser.parseString(service.refresh(oldRefreshToken)).getAsJsonObject();

        assertEquals(0, refreshed.get("code").getAsInt());
        JsonObject data = refreshed.getAsJsonObject("data");
        assertTrue(data.get("accessToken").getAsString().startsWith("mock-jwt."));
        assertEquals("13800138003", data.get("userId").getAsString());
        assertEquals(910_000L, data.get("accessExpiresAt").getAsLong());
        assertEquals(604_810_000L, data.get("refreshExpiresAt").getAsLong());
        assertNotEquals(oldRefreshToken, data.get("refreshToken").getAsString());

        JsonObject oldRefreshRejected = JsonParser.parseString(service.refresh(oldRefreshToken)).getAsJsonObject();
        assertEquals(401, oldRefreshRejected.get("code").getAsInt());
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
