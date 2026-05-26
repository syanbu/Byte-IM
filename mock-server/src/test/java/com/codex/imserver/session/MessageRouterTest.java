package com.codex.imserver.session;

import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
import com.codex.imserver.auth.TokenService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageRouterTest {
    @Test
    public void sendMessageProducesAckForSenderAndReceiveMessageForOnlineReceiver() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry);

        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"m1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """));

        assertEquals(ImCommand.MESSAGE_ACK.value(), sender.sentPackets.get(0).cmd());
        assertEquals(ImCommand.RECEIVE_MESSAGE.value(), receiver.sentPackets.get(0).cmd());
        JsonObject ack = body(sender.sentPackets.get(0));
        assertEquals("m1", ack.get("messageId").getAsString());
        assertEquals(1, ack.get("clientSeq").getAsLong());
        JsonObject received = body(receiver.sentPackets.get(0));
        assertEquals("hello", received.get("content").getAsString());
        assertEquals("13800113800", received.get("senderId").getAsString());
    }

    @Test
    public void sendMessageQueuesReceiveMessageForOfflineReceiverUntilAuth() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        CapturingClient sender = new CapturingClient();
        registry.register("13900113900", sender);
        MessageRouter router = new MessageRouter(registry, tokenService);

        router.handleSendMessage("13900113900", packet("""
            {
              "messageId":"offline-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":2,
              "content":"Hello",
              "timestamp":1000
            }
            """));

        CapturingClient receiver = new CapturingClient();
        router.handleAuth(tokenService.issue("13800113800").token(), receiver);

        assertEquals(ImCommand.AUTH_ACK.value(), receiver.sentPackets.get(0).cmd());
        assertEquals(ImCommand.RECEIVE_MESSAGE.value(), receiver.sentPackets.get(1).cmd());
        JsonObject delivered = body(receiver.sentPackets.get(1));
        assertEquals("offline-1", delivered.get("messageId").getAsString());
        assertEquals("Hello", delivered.get("content").getAsString());
        assertTrue(delivered.has("serverSeq"));
    }

    @Test
    public void authRejectsLegacyTokenWithoutRegisteringUser() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = new MessageRouter(registry, new TokenService("test-secret", () -> 1_000L, 60_000L));

        router.handleAuth("mock-token-13800113800", new CapturingClient());

        assertEquals(0, registry.find("13800113800").stream().count());
    }

    @Test
    public void authAckRecordsSingleAuthenticatedStatusEvent() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        MessageRouter router = new MessageRouter(registry, tokenService);
        CapturingClient client = new CapturingClient();

        router.handleAuth(tokenService.issue("13800138000").token(), client);

        assertEquals(List.of("AUTHENTICATED userId=13800138000 authAck=sent"), client.statusEvents);
    }

    @Test
    public void heartbeatLogIdentifiesClientAndAckRecipient() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient client = new CapturingClient();
        registry.register("13800113800", client);
        MessageRouter router = new MessageRouter(registry);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output));
            router.handleHeartbeat(client);
        } finally {
            System.setOut(originalOut);
        }

        assertEquals(ImCommand.HEARTBEAT_ACK.value(), client.sentPackets.get(0).cmd());
        String log = output.toString(StandardCharsets.UTF_8);
        assertTrue(log.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[IM] HEARTBEAT received userId=13800113800.*"));
        assertTrue(log.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[IM] HEARTBEAT_ACK sent userId=13800113800.*"));
    }

    private static ImPacket packet(String json) {
        return new ImPacket(ImCommand.SEND_MESSAGE.value(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject body(ImPacket packet) {
        return JsonParser.parseString(new String(packet.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class CapturingClient implements OutboundClient {
        private final List<ImPacket> sentPackets = new java.util.ArrayList<>();
        private final List<String> statusEvents = new java.util.ArrayList<>();

        @Override
        public void send(ImPacket packet) {
            sentPackets.add(packet);
        }

        @Override
        public void recordStatus(String status) {
            statusEvents.add(status);
        }
    }
}
