package com.buyansong.imserver.session;

import com.buyansong.imserver.protocol.ImCommand;
import com.buyansong.imserver.protocol.ImPacket;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageRouterTest {

    private static final class RecordingClient implements OutboundClient {
        final List<ImPacket> sent = new ArrayList<>();

        @Override
        public void send(ImPacket packet) {
            sent.add(packet);
        }
    }

    private static MessageRouter router(ClientSessionRegistry registry) {
        return new MessageRouter(registry);
    }

    private static void sendSingleMessage(MessageRouter router, String senderId, String messageId, String receiverId) {
        JsonObject message = new JsonObject();
        message.addProperty("messageId", messageId);
        message.addProperty("conversationType", "SINGLE");
        message.addProperty("senderId", senderId);
        message.addProperty("receiverId", receiverId);
        message.addProperty("conversationId", "single:" + senderId + ":" + receiverId);
        message.addProperty("clientSeq", 1L);
        message.addProperty("content", "hello");
        router.handleSendMessage(senderId, new ImPacket(
                ImCommand.SEND_MESSAGE.value(),
                message.toString().getBytes(StandardCharsets.UTF_8)
        ));
    }

    private static byte[] heartbeatBody(String... unackedMessageIds) {
        JsonObject body = new JsonObject();
        body.addProperty("clientTime", 1L);
        JsonArray ids = new JsonArray();
        for (String messageId : unackedMessageIds) {
            ids.add(messageId);
        }
        body.add("unackedMessageIds", ids);
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject lastHeartbeatAck(RecordingClient client) {
        ImPacket packet = client.sent.get(client.sent.size() - 1);
        assertEquals(ImCommand.HEARTBEAT_ACK.value(), packet.cmd());
        return JsonParser.parseString(new String(packet.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void heartbeat_echoesKnownMessageIdsAsReceived() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient client = new RecordingClient();
        registry.register("u_a", client);
        sendSingleMessage(router, "u_a", "m_1", "u_b");

        router.handleHeartbeat(client, heartbeatBody("m_1"));

        JsonObject ack = lastHeartbeatAck(client);
        assertTrue(ack.has("serverTime"));
        JsonArray received = ack.getAsJsonArray("receivedMessageIds");
        assertEquals(1, received.size());
        assertEquals("m_1", received.get(0).getAsString());
    }

    @Test
    public void heartbeat_excludesUnknownMessageIds() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient client = new RecordingClient();
        registry.register("u_a", client);
        sendSingleMessage(router, "u_a", "m_1", "u_b");

        router.handleHeartbeat(client, heartbeatBody("m_1", "m_unknown"));

        JsonObject ack = lastHeartbeatAck(client);
        JsonArray received = ack.getAsJsonArray("receivedMessageIds");
        assertEquals(1, received.size());
        assertEquals("m_1", received.get(0).getAsString());
    }

    @Test
    public void heartbeat_excludesMessagesSentByOtherUsers() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient clientA = new RecordingClient();
        RecordingClient clientB = new RecordingClient();
        registry.register("u_a", clientA);
        registry.register("u_b", clientB);
        sendSingleMessage(router, "u_a", "m_1", "u_b");

        router.handleHeartbeat(clientB, heartbeatBody("m_1"));

        JsonObject ack = lastHeartbeatAck(clientB);
        assertEquals(0, ack.getAsJsonArray("receivedMessageIds").size());
    }

    @Test
    public void heartbeat_withoutUnackedMessageIds_omitsReceivedMessageIds() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient client = new RecordingClient();
        registry.register("u_a", client);

        JsonObject legacyBody = new JsonObject();
        legacyBody.addProperty("clientTime", 1L);
        router.handleHeartbeat(client, legacyBody.toString().getBytes(StandardCharsets.UTF_8));

        JsonObject ack = lastHeartbeatAck(client);
        assertTrue(ack.has("serverTime"));
        assertFalse(ack.has("receivedMessageIds"));
    }
}
