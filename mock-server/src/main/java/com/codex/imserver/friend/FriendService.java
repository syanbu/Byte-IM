package com.codex.imserver.friend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.function.LongSupplier;

public final class FriendService {
    private final FriendStore friendStore;
    private final LongSupplier clock;

    public FriendService() {
        this(new FriendStore(java.nio.file.Path.of("data", "mock-im-friends.sqlite")), System::currentTimeMillis);
    }

    public FriendService(FriendStore friendStore, LongSupplier clock) {
        this.friendStore = friendStore;
        this.clock = clock;
    }

    public String friendsJson(String userId) {
        JsonArray friendUserIds = new JsonArray();
        for (String friendUserId : friendStore.friendsOf(userId)) {
            friendUserIds.add(friendUserId);
        }
        JsonObject data = new JsonObject();
        data.add("friendUserIds", friendUserIds);
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public void addMutualFriendship(String firstUserId, String secondUserId) {
        friendStore.addMutualFriendship(firstUserId, secondUserId, clock.getAsLong());
    }
}
