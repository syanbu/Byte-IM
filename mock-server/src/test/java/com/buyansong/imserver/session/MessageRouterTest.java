package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.ChatMessagePayload;
import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.Heartbeat;
import com.buyansong.im.protocol.v2.HeartbeatAck;
import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.im.protocol.v2.MessageType;
import com.buyansong.im.protocol.v2.SendMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageRouterTest {

    private static final class RecordingClient implements OutboundClient {
        final List<ImEnvelope> sent = new ArrayList<>();

        @Override
        public void send(ImEnvelope envelope) {
            sent.add(envelope);
        }
    }

    private static MessageRouter router(ClientSessionRegistry registry) {
        return new MessageRouter(registry);
    }

    private static void sendSingleMessage(MessageRouter router, String senderId, String messageId, String receiverId) {
        router.handleSendMessage(senderId, SendMessage.newBuilder()
                .setMessage(ChatMessagePayload.newBuilder()
                        .setMessageId(messageId)
                        .setConversationType(ConversationType.CONVERSATION_TYPE_SINGLE)
                        .setSenderId(senderId)
                        .setReceiverId(receiverId)
                        .setConversationId("single:" + senderId + ":" + receiverId)
                        .setMessageType(MessageType.MESSAGE_TYPE_TEXT)
                        .setContent("hello"))
                .build());
    }

    private static Heartbeat heartbeat(String... unackedMessageIds) {
        return Heartbeat.newBuilder()
                .setClientTime(1L)
                .addAllUnackedMessageIds(List.of(unackedMessageIds))
                .build();
    }

    private static HeartbeatAck lastHeartbeatAck(RecordingClient client) {
        ImEnvelope envelope = client.sent.get(client.sent.size() - 1);
        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT_ACK, envelope.getPayloadCase());
        return envelope.getHeartbeatAck();
    }

    @Test
    public void heartbeat_echoesKnownMessageIdsAsReceived() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient client = new RecordingClient();
        registry.register("u_a", client);
        sendSingleMessage(router, "u_a", "m_1", "u_b");

        router.handleHeartbeat(client, heartbeat("m_1"));

        HeartbeatAck ack = lastHeartbeatAck(client);
        assertTrue(ack.getServerTime() > 0L);
        assertEquals(List.of("m_1"), ack.getReceivedMessageIdsList());
    }

    @Test
    public void heartbeat_excludesUnknownMessageIds() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient client = new RecordingClient();
        registry.register("u_a", client);
        sendSingleMessage(router, "u_a", "m_1", "u_b");

        router.handleHeartbeat(client, heartbeat("m_1", "m_unknown"));

        HeartbeatAck ack = lastHeartbeatAck(client);
        assertEquals(List.of("m_1"), ack.getReceivedMessageIdsList());
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

        router.handleHeartbeat(clientB, heartbeat("m_1"));

        HeartbeatAck ack = lastHeartbeatAck(clientB);
        assertEquals(0, ack.getReceivedMessageIdsCount());
    }

    @Test
    public void heartbeat_withoutUnackedMessageIds_omitsReceivedMessageIds() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = router(registry);
        RecordingClient client = new RecordingClient();
        registry.register("u_a", client);

        router.handleHeartbeat(client, Heartbeat.newBuilder().setClientTime(1L).build());

        HeartbeatAck ack = lastHeartbeatAck(client);
        assertTrue(ack.getServerTime() > 0L);
        assertEquals(0, ack.getReceivedMessageIdsCount());
    }
}
