package com.buyansong.imserver.tools;

import com.buyansong.imserver.auth.AuthService;
import com.buyansong.imserver.auth.PasswordHasher;
import com.buyansong.imserver.auth.SecureSaltGenerator;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.auth.UserStore;
import com.buyansong.imserver.friend.FriendStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class MockFriendSeeder {
    public static final int ACCOUNT_COUNT = 1000;
    public static final int HUB_ACCOUNT_COUNT = 3;
    public static final String PASSWORD = "123456";
    private static final long FIRST_PHONE = 15_000_000_000L;

    private final AuthService authService;
    private final UserStore userStore;
    private final FriendStore friendStore;
    private final LongSupplier clock;

    public MockFriendSeeder(AuthService authService, UserStore userStore, FriendStore friendStore, LongSupplier clock) {
        this.authService = authService;
        this.userStore = userStore;
        this.friendStore = friendStore;
        this.clock = clock;
    }

    public SeedResult seed() {
        List<String> userIds = generatedUserIds();
        int registeredUsers = 0;
        for (String userId : userIds) {
            if (userStore.findByPhone(userId).isEmpty()) {
                JsonObject result = JsonParser.parseString(authService.register(userId, PASSWORD)).getAsJsonObject();
                if (result.get("code").getAsInt() == 0) {
                    registeredUsers += 1;
                }
            }
        }

        List<String> hubUserIds = userIds.subList(0, HUB_ACCOUNT_COUNT);
        for (String hubUserId : hubUserIds) {
            for (String candidateFriendId : userIds) {
                if (!hubUserId.equals(candidateFriendId)) {
                    friendStore.addMutualFriendship(hubUserId, candidateFriendId, clock.getAsLong());
                }
            }
        }
        return new SeedResult(registeredUsers, userIds.size(), hubUserIds);
    }

    public static List<String> generatedUserIds() {
        List<String> userIds = new ArrayList<>();
        for (int index = 0; index < ACCOUNT_COUNT; index++) {
            userIds.add(Long.toString(FIRST_PHONE + index));
        }
        return userIds;
    }

    public static void main(String[] args) {
        UserStore userStore = new UserStore(Path.of("data", "mock-im-users.sqlite"));
        TokenService tokenService = TokenService.defaultService();
        AuthService authService = new AuthService(
                userStore,
                new PasswordHasher(new SecureSaltGenerator()),
                tokenService
        );
        FriendStore friendStore = new FriendStore(Path.of("data", "mock-im-friends.sqlite"));
        SeedResult result = new MockFriendSeeder(authService, userStore, friendStore, System::currentTimeMillis).seed();
        System.out.printf(
                "Mock friend seed complete: registeredUsers=%d totalUsers=%d hubUserIds=%s%n",
                result.registeredUsers(),
                result.totalUsers(),
                result.hubUserIds()
        );
    }

    public record SeedResult(int registeredUsers, int totalUsers, List<String> hubUserIds) {
    }
}
