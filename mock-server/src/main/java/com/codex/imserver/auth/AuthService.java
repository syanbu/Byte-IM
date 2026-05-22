package com.codex.imserver.auth;

import com.google.gson.JsonObject;

public final class AuthService {
    public String login(String username) {
        return success(username);
    }

    public String register(String username) {
        return success(username);
    }

    public String success(String username) {
        JsonObject data = new JsonObject();
        data.addProperty("token", "mock-token-" + username);
        data.addProperty("userId", username);
        data.addProperty("username", username);

        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public String failure(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 400);
        root.addProperty("message", message);
        return root.toString();
    }
}
