package com.codex.imserver.tools;

import com.codex.imserver.auth.AuthService;
import com.codex.imserver.auth.PasswordHasher;
import com.codex.imserver.auth.SaltGenerator;
import com.codex.imserver.auth.TokenService;
import com.codex.imserver.auth.UserStore;
import com.codex.imserver.friend.FriendStore;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MockFriendSeederTest {
    @Test
    public void seedRegistersFiveHundredUsersAndGivesFirstThreeEveryOtherAccountAsFriends() throws Exception {
        Path directory = Files.createTempDirectory("mock-im-seed");
        UserStore userStore = new UserStore(directory.resolve("users.sqlite"));
        FriendStore friendStore = new FriendStore(directory.resolve("friends.sqlite"));
        AuthService authService = new AuthService(
                userStore,
                new PasswordHasher(new FixedSaltGenerator()),
                new TokenService("test-secret", () -> 1_000L, 900_000L, 604_800_000L, () -> "refresh-token-with-enough-entropy-for-test")
        );

        MockFriendSeeder seeder = new MockFriendSeeder(authService, userStore, friendStore, () -> 1_000L);
        MockFriendSeeder.SeedResult first = seeder.seed();
        MockFriendSeeder.SeedResult second = seeder.seed();

        assertEquals(500, first.registeredUsers());
        assertEquals(0, second.registeredUsers());
        assertEquals(499, friendStore.friendsOf("15000000000").size());
        assertEquals(499, friendStore.friendsOf("15000000001").size());
        assertEquals(499, friendStore.friendsOf("15000000002").size());
        assertTrue(friendStore.friendsOf("15000000000").contains("15000000001"));
        assertTrue(friendStore.friendsOf("15000000000").contains("15000000002"));
        assertTrue(friendStore.friendsOf("15000000003").contains("15000000000"));
        assertTrue(userStore.findByPhone("15000000499").isPresent());
    }

    private static final class FixedSaltGenerator implements SaltGenerator {
        @Override
        public String nextSalt() {
            return "fixed-salt";
        }
    }
}
