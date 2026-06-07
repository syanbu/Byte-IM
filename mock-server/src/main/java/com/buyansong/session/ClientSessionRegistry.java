package com.buyansong.imserver.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ClientSessionRegistry {
    private final ConcurrentMap<String, OutboundClient> clientsByUserId = new ConcurrentHashMap<>();
    private final ConcurrentMap<OutboundClient, String> userIdsByClient = new ConcurrentHashMap<>();

    public void register(String userId, OutboundClient client) {
        clientsByUserId.put(userId, client);
        userIdsByClient.put(client, userId);
    }

    public Optional<OutboundClient> find(String userId) {
        return Optional.ofNullable(clientsByUserId.get(userId));
    }

    public Optional<String> userIdOf(OutboundClient client) {
        return Optional.ofNullable(userIdsByClient.get(client));
    }

    public void remove(OutboundClient client) {
        String userId = userIdsByClient.remove(client);
        if (userId != null) {
            clientsByUserId.remove(userId, client);
            client.recordStatus("DISCONNECTED userId=" + userId);
        }
    }
}
