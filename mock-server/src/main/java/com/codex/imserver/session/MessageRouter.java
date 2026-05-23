package com.codex.imserver.session;

import com.codex.imserver.auth.TokenService;
import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public final class MessageRouter {
    private final ClientSessionRegistry registry;
    private final TokenService tokenService;
    private final AtomicLong serverSeq = new AtomicLong(1000);

    public MessageRouter(ClientSessionRegistry registry) {
        this(registry, TokenService.defaultService());
    }

    public MessageRouter(ClientSessionRegistry registry, TokenService tokenService) {
        this.registry = registry;
        this.tokenService = tokenService;
    }

    public void handleSendMessage(String senderUserId, ImPacket packet) {
        JsonObject message = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String messageId = message.get("messageId").getAsString();
        String receiverId = message.get("receiverId").getAsString();
        long nextServerSeq = serverSeq.incrementAndGet();
        long serverTime = System.currentTimeMillis();

        System.out.printf(
                "[IM] SEND_MESSAGE sender=%s receiver=%s messageId=%s serverSeq=%d content=%s%n",
                senderUserId,
                receiverId,
                messageId,
                nextServerSeq,
                message.get("content").getAsString()
        );

        JsonObject ack = new JsonObject();
        ack.addProperty("messageId", messageId);
        ack.addProperty("conversationId", message.get("conversationId").getAsString());
        ack.addProperty("clientSeq", message.get("clientSeq").getAsLong());
        ack.addProperty("serverSeq", nextServerSeq);
        ack.addProperty("serverTime", serverTime);
        registry.find(senderUserId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.MESSAGE_ACK, ack));
                    System.out.printf("[IM] MESSAGE_ACK sent sender=%s messageId=%s%n", senderUserId, messageId);
                },
                () -> System.out.printf("[IM] MESSAGE_ACK skipped sender offline sender=%s messageId=%s%n", senderUserId, messageId)
        );

        message.addProperty("serverSeq", nextServerSeq);
        message.addProperty("serverTime", serverTime);
        registry.find(receiverId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.RECEIVE_MESSAGE, message));
                    System.out.printf("[IM] RECEIVE_MESSAGE forwarded receiver=%s messageId=%s%n", receiverId, messageId);
                },
                () -> System.out.printf("[IM] RECEIVE_MESSAGE skipped receiver offline receiver=%s messageId=%s%n", receiverId, messageId)
        );
    }

    public void handleHeartbeat(OutboundClient client) {
        JsonObject body = new JsonObject();
        body.addProperty("serverTime", System.currentTimeMillis());
        client.send(packet(ImCommand.HEARTBEAT_ACK, body));
        System.out.println("[IM] HEARTBEAT_ACK sent");
    }

    public void handleAuth(String token, OutboundClient client) {
        String userId = tokenService.verify(token).orElse(null);
        if (userId == null) {
            System.out.println("[IM] AUTH rejected invalid or expired token");
            return;
        }
        registry.register(userId, client);
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        body.addProperty("serverTime", System.currentTimeMillis());
        client.send(packet(ImCommand.AUTH_ACK, body));
        client.recordStatus("AUTHENTICATED userId=" + userId + " authAck=sent");
    }

    private ImPacket packet(ImCommand command, JsonObject body) {
        return new ImPacket(command.value(), body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
