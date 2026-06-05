package com.codex.imserver.friend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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

    private Path tempDb() throws Exception {
        return Files.createTempFile("mock-im-friends", ".db");
    }
}
