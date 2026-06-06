package com.codex.imserver.friend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class FriendServiceTest {
    @Test
    public void friendsJsonReturnsFriendUserIdsForCurrentUser() throws Exception {
        FriendStore store = new FriendStore(tempDb());
        FriendService service = new FriendService(store, () -> 1_000L);
        store.addMutualFriendship("15000000000", "15000000001", 1_000L);
        store.addMutualFriendship("15000000000", "15000000002", 1_000L);

        JsonObject root = JsonParser.parseString(service.friendsJson("15000000000")).getAsJsonObject();

        assertEquals(0, root.get("code").getAsInt());
        assertEquals("ok", root.get("message").getAsString());
        assertEquals(
                "[\"15000000001\",\"15000000002\"]",
                root.getAsJsonObject("data").getAsJsonArray("friendUserIds").toString()
        );
    }

    @Test
    public void friendsJsonReturnsFriendProfileVersions() throws Exception {
        FriendStore store = new FriendStore(tempDb());
        FriendService service = new FriendService(
                store,
                () -> 1_000L,
                userId -> Map.of(
                        "15000000001", 3_000L,
                        "15000000002", 4_000L
                ).getOrDefault(userId, 0L)
        );
        store.addMutualFriendship("15000000000", "15000000001", 1_000L);
        store.addMutualFriendship("15000000000", "15000000002", 1_000L);

        JsonObject root = JsonParser.parseString(service.friendsJson("15000000000")).getAsJsonObject();

        assertEquals(
                "[{\"userId\":\"15000000001\",\"profileUpdatedAt\":3000},{\"userId\":\"15000000002\",\"profileUpdatedAt\":4000}]",
                root.getAsJsonObject("data").getAsJsonArray("friends").toString()
        );
    }

    private Path tempDb() throws Exception {
        return Files.createTempFile("mock-im-friends", ".db");
    }
}
