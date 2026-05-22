package com.codex.imserver.session;

import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
import com.codex.imserver.auth.TokenService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

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
    public void authRejectsLegacyTokenWithoutRegisteringUser() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = new MessageRouter(registry, new TokenService("test-secret", () -> 1_000L, 60_000L));

        router.handleAuth("mock-token-13800113800", new CapturingClient());

        assertEquals(0, registry.find("13800113800").stream().count());
    }

    private static ImPacket packet(String json) {
        return new ImPacket(ImCommand.SEND_MESSAGE.value(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject body(ImPacket packet) {
        return JsonParser.parseString(new String(packet.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static final class CapturingClient implements OutboundClient {
        private final List<ImPacket> sentPackets = new java.util.ArrayList<>();

        @Override
        public void send(ImPacket packet) {
            sentPackets.add(packet);
        }
    }
}
