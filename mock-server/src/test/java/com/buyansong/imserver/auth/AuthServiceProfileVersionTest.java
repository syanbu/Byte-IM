package com.buyansong.imserver.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class AuthServiceProfileVersionTest {
    @Test
    public void registerProfileAndBatchExposeProfileVersionAndUpdateIncrementsIt() throws Exception {
        Path database = Files.createTempFile("users", ".sqlite");
        AtomicLong clock = new AtomicLong(1_000L);
        UserStore userStore = new UserStore(database);
        AuthService authService = new AuthService(
                userStore,
                new PasswordHasher(new FixedSaltGenerator("fixed-salt")),
                new TokenService("test-secret", clock::get, 60_000L, 60_000L, () -> "refresh-token")
        );

        JsonObject register = JsonParser.parseString(authService.register("13800000001", "password")).getAsJsonObject();
        assertEquals(0L, register.getAsJsonObject("data").get("profileVersion").getAsLong());
        assertEquals(0L, userStore.findByPhone("13800000001").orElseThrow().profileVersion());

        clock.set(2_000L);
        JsonObject update = JsonParser
                .parseString(authService.updateProfile("13800000001", "Alice", null, null, null, null))
                .getAsJsonObject();
        assertEquals(1L, update.getAsJsonObject("data").get("profileVersion").getAsLong());
        assertEquals(1L, userStore.findByPhone("13800000001").orElseThrow().profileVersion());

        JsonObject batch = JsonParser
                .parseString(authService.profiles(List.of("13800000001")))
                .getAsJsonObject();
        JsonObject profile = batch.getAsJsonObject("data").getAsJsonArray("profiles").get(0).getAsJsonObject();
        assertEquals(1L, profile.get("profileVersion").getAsLong());
    }

    @Test
    public void updateProfilePreservesAvatarObjectKeyWhenLaterRequestOmitsIt() throws Exception {
        Path database = Files.createTempFile("users", ".sqlite");
        AtomicLong clock = new AtomicLong(1_000L);
        UserStore userStore = new UserStore(database);
        AuthService authService = new AuthService(
                userStore,
                new PasswordHasher(new FixedSaltGenerator("fixed-salt")),
                new TokenService("test-secret", clock::get, 60_000L, 60_000L, () -> "refresh-token")
        );
        authService.register("13800000001", "password");

        clock.set(2_000L);
        authService.updateProfile(
                "13800000001",
                "Alice",
                "https://example.com/avatar.jpg",
                "avatars/13800000001/avatar.jpg",
                null,
                null
        );

        clock.set(3_000L);
        JsonObject update = JsonParser
                .parseString(authService.updateProfile("13800000001", "Alice", "https://example.com/avatar.jpg", null, "MALE", null))
                .getAsJsonObject();

        assertEquals("avatars/13800000001/avatar.jpg", userStore.findByPhone("13800000001").orElseThrow().avatarObjectKey());
        assertEquals("avatars/13800000001/avatar.jpg", update.getAsJsonObject("data").get("avatarObjectKey").getAsString());
    }
}
