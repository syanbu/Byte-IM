package com.codex.imserver.session;

import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
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
        CapturingClient alice = new CapturingClient();
        CapturingClient bob = new CapturingClient();
        registry.register("alice", alice);
        registry.register("bob", bob);
        MessageRouter router = new MessageRouter(registry);

        router.handleSendMessage("alice", packet("""
            {
              "messageId":"m1",
              "conversationId":"single:alice:bob",
              "senderId":"alice",
              "receiverId":"bob",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """));

        assertEquals(ImCommand.MESSAGE_ACK.value(), alice.sentPackets.get(0).cmd());
        assertEquals(ImCommand.RECEIVE_MESSAGE.value(), bob.sentPackets.get(0).cmd());
        JsonObject ack = body(alice.sentPackets.get(0));
        assertEquals("m1", ack.get("messageId").getAsString());
        assertEquals(1, ack.get("clientSeq").getAsLong());
        JsonObject received = body(bob.sentPackets.get(0));
        assertEquals("hello", received.get("content").getAsString());
        assertEquals("alice", received.get("senderId").getAsString());
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
