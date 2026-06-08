package com.buyansong.imserver.friend;

import com.buyansong.imserver.auth.UserStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.function.Function;

public final class FriendService {
    private final FriendStore friendStore;
    private final LongSupplier clock;
    private final Function<String, Long> profileUpdatedAtByUserId;

    public FriendService() {
        this(
                new FriendStore(Path.of("data", "mock-im-friends.sqlite")),
                System::currentTimeMillis,
                new UserStore(Path.of("data", "mock-im-users.sqlite"))::profileUpdatedAtByPhone
        );
    }

    public FriendService(FriendStore friendStore, LongSupplier clock) {
        this(friendStore, clock, userId -> 0L);
    }

    public FriendService(FriendStore friendStore, LongSupplier clock, Function<String, Long> profileUpdatedAtByUserId) {
        this.friendStore = friendStore;
        this.clock = clock;
        this.profileUpdatedAtByUserId = profileUpdatedAtByUserId;
    }

    public String friendsJson(String userId) {
        JsonArray friendUserIds = new JsonArray();
        JsonArray friends = new JsonArray();
        for (String friendUserId : friendStore.friendsOf(userId)) {
            friendUserIds.add(friendUserId);
            JsonObject friend = new JsonObject();
            friend.addProperty("userId", friendUserId);
            friend.addProperty("profileUpdatedAt", profileUpdatedAtByUserId.apply(friendUserId));
            friends.add(friend);
        }
        JsonObject data = new JsonObject();
        data.add("friendUserIds", friendUserIds);
        data.add("friends", friends);
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
