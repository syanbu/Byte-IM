package com.codex.imserver.friend;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FriendStoreTest {
    @Test
    public void addMutualFriendshipStoresBothDirectionsAndDeduplicates() throws Exception {
        FriendStore store = new FriendStore(tempDb());

        store.addMutualFriendship("15000000000", "15000000001", 1_000L);
        store.addMutualFriendship("15000000000", "15000000001", 2_000L);

        assertEquals(List.of("15000000001"), store.friendsOf("15000000000"));
        assertEquals(List.of("15000000000"), store.friendsOf("15000000001"));
    }

    @Test
    public void addMutualFriendshipIgnoresSelfLinksAndBlankIds() throws Exception {
        FriendStore store = new FriendStore(tempDb());

        store.addMutualFriendship("15000000000", "15000000000", 1_000L);
        store.addMutualFriendship("", "15000000001", 1_000L);
        store.addMutualFriendship("15000000002", " ", 1_000L);

        assertEquals(List.of(), store.friendsOf("15000000000"));
        assertEquals(List.of(), store.friendsOf("15000000001"));
        assertEquals(List.of(), store.friendsOf("15000000002"));
    }

    private Path tempDb() throws Exception {
        return Files.createTempFile("mock-im-friends", ".db");
    }
}
