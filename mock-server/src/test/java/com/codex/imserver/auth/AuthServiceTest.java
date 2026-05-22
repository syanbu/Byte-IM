package com.codex.imserver.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuthServiceTest {
    @Test
    public void loginReturnsNestedTokenResponseCompatibleWithAndroidClient() {
        AuthService service = new AuthService();

        String json = service.login("alice");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");

        assertEquals(0, root.get("code").getAsInt());
        assertEquals("mock-token-alice", data.get("token").getAsString());
        assertEquals("alice", data.get("userId").getAsString());
        assertEquals("alice", data.get("username").getAsString());
    }
}
