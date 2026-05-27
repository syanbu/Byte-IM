package com.codex.imserver.session;

import com.codex.imserver.ImServerLogger;
import com.codex.imserver.auth.TokenService;
import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class MessageRouter {
    private final ClientSessionRegistry registry;
    private final TokenService tokenService;
    private final ServerSeqStore serverSeqStore;
    private final ConcurrentMap<String, AcceptedMessage> acceptedMessagesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, AcceptedMessage>> undeliveredMessagesByReceiver = new ConcurrentHashMap<>();

    public MessageRouter(ClientSessionRegistry registry) {
        this(registry, TokenService.defaultService());
    }

    public MessageRouter(ClientSessionRegistry registry, TokenService tokenService) {
        this(registry, tokenService, new InMemoryServerSeqStore());
    }

    public MessageRouter(ClientSessionRegistry registry, TokenService tokenService, ServerSeqStore serverSeqStore) {
        this.registry = registry;
        this.tokenService = tokenService;
        this.serverSeqStore = serverSeqStore;
    }

    public synchronized void handleSendMessage(String senderUserId, ImPacket packet) {
        JsonObject message = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String messageId = message.get("messageId").getAsString();
        AcceptedMessage accepted = acceptedMessagesById.get(messageId);
        if (accepted != null) {
            sendAck(senderUserId, accepted.ack());
            ImServerLogger.log(
                    "[IM] SEND_MESSAGE duplicate sender=%s messageId=%s serverSeq=%d ackOnly=true",
                    senderUserId,
                    messageId,
                    accepted.serverSeq()
            );
            return;
        }
        String receiverId = message.get("receiverId").getAsString();
        String conversationId = message.get("conversationId").getAsString();
        long clientSeq = message.get("clientSeq").getAsLong();
        long nextServerSeq = nextServerSeq(conversationId);
        long serverTime = System.currentTimeMillis();

        ImServerLogger.log(
                "[IM] SEND_MESSAGE sender=%s receiver=%s conversationId=%s messageId=%s clientSeq=%d serverSeq=%d content=%s",
                senderUserId,
                receiverId,
                conversationId,
                messageId,
                clientSeq,
                nextServerSeq,
                message.get("content").getAsString()
        );

        JsonObject ack = new JsonObject();
        ack.addProperty("messageId", messageId);
        ack.addProperty("conversationId", conversationId);
        ack.addProperty("clientSeq", clientSeq);
        ack.addProperty("serverSeq", nextServerSeq);
        ack.addProperty("serverTime", serverTime);

        message.addProperty("serverSeq", nextServerSeq);
        message.addProperty("serverTime", serverTime);
        acceptedMessagesById.put(messageId, new AcceptedMessage(
                ack.deepCopy(),
                message.deepCopy(),
                receiverId,
                nextServerSeq,
                false
        ));
        undeliveredMessagesByReceiver
                .computeIfAbsent(receiverId, ignored -> new ConcurrentHashMap<>())
                .put(messageId, acceptedMessagesById.get(messageId));
        sendAck(senderUserId, ack);
        deliverOrKeepPending(receiverId, messageId, message, nextServerSeq);
    }

    public synchronized void handleDeliveryAck(String receiverUserId, ImPacket packet) {
        JsonObject ack = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String messageId = ack.get("messageId").getAsString();
        long serverSeq = ack.get("serverSeq").getAsLong();
        acceptedMessagesById.computeIfPresent(messageId, (ignored, accepted) -> {
            if (!accepted.receiverUserId().equals(receiverUserId)) {
                return accepted;
            }
            ImServerLogger.log(
                    "[IM] DELIVERY_ACK received receiver=%s messageId=%s serverSeq=%d",
                    receiverUserId,
                    messageId,
                    serverSeq
            );
            removeUndelivered(receiverUserId, messageId);
            return accepted.markDelivered();
        });
    }

    private void sendAck(String senderUserId, JsonObject ack) {
        String messageId = ack.get("messageId").getAsString();
        long clientSeq = ack.get("clientSeq").getAsLong();
        long serverSeq = ack.get("serverSeq").getAsLong();
        registry.find(senderUserId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.MESSAGE_ACK, ack));
                    ImServerLogger.log(
                            "[IM] MESSAGE_ACK sent sender=%s messageId=%s clientSeq=%d serverSeq=%d",
                            senderUserId,
                            messageId,
                            clientSeq,
                            serverSeq
                    );
                },
                () -> ImServerLogger.log("[IM] MESSAGE_ACK skipped sender offline sender=%s messageId=%s", senderUserId, messageId)
        );
    }

    public void handleHeartbeat(OutboundClient client) {
        String userId = registry.userIdOf(client).orElse("unknown");
        ImServerLogger.log("[IM] HEARTBEAT received userId=%s", userId);
        JsonObject body = new JsonObject();
        body.addProperty("serverTime", System.currentTimeMillis());
        client.send(packet(ImCommand.HEARTBEAT_ACK, body));
        ImServerLogger.log("[IM] HEARTBEAT_ACK sent userId=%s", userId);
    }

    public void handleAuth(String token, OutboundClient client) {
        String userId = tokenService.verify(token).orElse(null);
        if (userId == null) {
            ImServerLogger.log("[IM] AUTH rejected invalid or expired token");
            return;
        }
        registry.register(userId, client);
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        body.addProperty("serverTime", System.currentTimeMillis());
        client.send(packet(ImCommand.AUTH_ACK, body));
        client.recordStatus("AUTHENTICATED userId=" + userId + " authAck=sent");
        deliverQueuedMessages(userId, client);
    }

    private void deliverQueuedMessages(String userId, OutboundClient client) {
        ConcurrentMap<String, AcceptedMessage> receiverMessages = undeliveredMessagesByReceiver.get(userId);
        if (receiverMessages == null) {
            return;
        }
        for (AcceptedMessage accepted : receiverMessages.values()) {
            client.send(packet(ImCommand.RECEIVE_MESSAGE, accepted.message()));
            ImServerLogger.log(
                    "[IM] RECEIVE_MESSAGE delivered queued receiver=%s messageId=%s serverSeq=%d",
                    userId,
                    accepted.message().get("messageId").getAsString(),
                    accepted.message().get("serverSeq").getAsLong()
            );
        }
    }

    List<String> undeliveredMessageIdsForReceiver(String receiverUserId) {
        ConcurrentMap<String, AcceptedMessage> receiverMessages = undeliveredMessagesByReceiver.get(receiverUserId);
        if (receiverMessages == null) {
            return List.of();
        }
        List<String> messageIds = new ArrayList<>(receiverMessages.keySet());
        messageIds.sort(String::compareTo);
        return messageIds;
    }

    private void deliverOrKeepPending(String receiverId, String messageId, JsonObject message, long serverSeq) {
        registry.find(receiverId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.RECEIVE_MESSAGE, message));
                    ImServerLogger.log(
                            "[IM] RECEIVE_MESSAGE forwarded receiver=%s messageId=%s serverSeq=%d",
                            receiverId,
                            messageId,
                            serverSeq
                    );
                },
                () -> ImServerLogger.log(
                        "[IM] RECEIVE_MESSAGE queued receiver offline receiver=%s messageId=%s serverSeq=%d",
                        receiverId,
                        messageId,
                        serverSeq
                )
        );
    }

    private long nextServerSeq(String conversationId) {
        return serverSeqStore.next(conversationId);
    }

    private void removeUndelivered(String receiverUserId, String messageId) {
        undeliveredMessagesByReceiver.computeIfPresent(receiverUserId, (ignored, messagesById) -> {
            messagesById.remove(messageId);
            return messagesById.isEmpty() ? null : messagesById;
        });
    }

    private ImPacket packet(ImCommand command, JsonObject body) {
        return new ImPacket(command.value(), body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record AcceptedMessage(
            JsonObject ack,
            JsonObject message,
            String receiverUserId,
            long serverSeq,
            boolean delivered
    ) {
        private AcceptedMessage markDelivered() {
            return new AcceptedMessage(ack, message, receiverUserId, serverSeq, true);
        }
    }

    public interface ServerSeqStore {
        long next(String conversationId);
    }

    public static final class InMemoryServerSeqStore implements ServerSeqStore {
        private final ConcurrentMap<String, AtomicLong> serverSeqByConversation = new ConcurrentHashMap<>();

        @Override
        public long next(String conversationId) {
            return serverSeqByConversation
                    .computeIfAbsent(conversationId, ignored -> new AtomicLong(1000))
                    .incrementAndGet();
        }
    }

    public static final class SQLiteServerSeqStore implements ServerSeqStore {
        private final String jdbcUrl;
        private final LongSupplier initialSequenceSupplier;

        public SQLiteServerSeqStore(Path databasePath) {
            this(databasePath, System::currentTimeMillis);
        }

        SQLiteServerSeqStore(Path databasePath, LongSupplier initialSequenceSupplier) {
            try {
                Path parent = databasePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Unable to create sequence database directory", error);
            }
            this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().toUri();
            this.initialSequenceSupplier = initialSequenceSupplier;
            initialize();
        }

        @Override
        public synchronized long next(String conversationId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
                long current = currentSeq(connection, conversationId);
                long next = current + 1;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT OR REPLACE INTO conversation_sequences(conversation_id, last_server_seq) VALUES(?, ?)"
                )) {
                    statement.setString(1, conversationId);
                    statement.setLong(2, next);
                    statement.executeUpdate();
                }
                return next;
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to allocate serverSeq", error);
            }
        }

        private void initialize() {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS conversation_sequences (
                          conversation_id TEXT PRIMARY KEY,
                          last_server_seq INTEGER NOT NULL
                        )
                        """
                );
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to initialize sequence database", error);
            }
        }

        private long currentSeq(Connection connection, String conversationId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_server_seq FROM conversation_sequences WHERE conversation_id = ?"
            )) {
                statement.setString(1, conversationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong("last_server_seq") : Math.max(1000L, initialSequenceSupplier.getAsLong());
                }
            }
        }
    }
}
