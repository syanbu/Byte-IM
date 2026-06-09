package com.buyansong.imserver.session;

import com.buyansong.imserver.ImServerLogger;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.auth.TokenService.AuthFailureReason;
import com.buyansong.imserver.auth.TokenService.VerificationResult;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.groupread.GroupReadCursor;
import com.buyansong.imserver.groupread.GroupReadCursorStore;
import com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore;
import com.buyansong.imserver.protocol.ImCommand;
import com.buyansong.imserver.protocol.ImPacket;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class MessageRouter {
    private final ClientSessionRegistry registry;
    private final TokenService tokenService;
    private final ServerSeqStore serverSeqStore;
    private final AcceptedMessageStore acceptedMessageStore;
    private final GroupService groupService;
    private final GroupReadCursorStore groupReadCursorStore;
    private final LongSupplier clock;
    private final ConcurrentMap<String, AcceptedMessage> acceptedMessagesById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, AcceptedMessage>> undeliveredMessagesByReceiver = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, RecallNotifyEvent>> pendingRecallNotifiesByReceiver = new ConcurrentHashMap<>();

    public MessageRouter(ClientSessionRegistry registry) {
        this(registry, TokenService.defaultService());
    }

    public MessageRouter(ClientSessionRegistry registry, TokenService tokenService) {
        this(registry, tokenService, new InMemoryServerSeqStore());
    }

    public MessageRouter(ClientSessionRegistry registry, TokenService tokenService, ServerSeqStore serverSeqStore) {
        this(registry, tokenService, serverSeqStore, new InMemoryAcceptedMessageStore());
    }

    public MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore
    ) {
        this(registry, tokenService, serverSeqStore, acceptedMessageStore, System::currentTimeMillis);
    }

    MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore,
            LongSupplier clock
    ) {
        this(registry, tokenService, serverSeqStore, acceptedMessageStore, new GroupService(clock), clock);
    }

    public MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore,
            GroupService groupService,
            LongSupplier clock
    ) {
        this(registry, tokenService, serverSeqStore, acceptedMessageStore, groupService,
                new InMemoryGroupReadCursorStore(), clock);
    }

    public MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore,
            GroupService groupService,
            GroupReadCursorStore groupReadCursorStore,
            LongSupplier clock
    ) {
        this.registry = registry;
        this.tokenService = tokenService;
        this.serverSeqStore = serverSeqStore;
        this.acceptedMessageStore = acceptedMessageStore;
        this.groupService = groupService;
        this.groupReadCursorStore = groupReadCursorStore;
        this.clock = clock;
        restoreAcceptedMessages();
    }

    public synchronized void handleSendMessage(String senderUserId, ImPacket packet) {
        JsonObject message = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String messageId = message.get("messageId").getAsString();
        String conversationType = optionalString(message, "conversationType", "SINGLE");
        AcceptedMessage accepted = acceptedMessagesById.get(messageId);
        if (accepted != null) {
            sendAck(senderUserId, accepted.ack());
            ImServerLogger.log(
                    "[IM] %s duplicate sender=%s messageId=%s serverSeq=%d ackOnly=true",
                    "GROUP".equals(conversationType) ? "GROUP_SEND" : "SEND_MESSAGE",
                    senderUserId,
                    messageId,
                    accepted.serverSeq()
            );
            return;
        }
        if ("GROUP".equals(conversationType)) {
            handleGroupSendMessage(senderUserId, messageId, message);
            return;
        }
        String receiverId = message.get("receiverId").getAsString();
        String conversationId = message.get("conversationId").getAsString();
        long clientSeq = message.get("clientSeq").getAsLong();
        long nextServerSeq = nextServerSeq(conversationId);
        long serverTime = clock.getAsLong();

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
        AcceptedMessage newlyAccepted = new AcceptedMessage(
                ack.deepCopy(),
                message.deepCopy(),
                receiverId,
                nextServerSeq,
                false
        );
        Optional<AcceptedMessage> existing = acceptedMessageStore.saveIfAbsent(messageId, newlyAccepted);
        if (existing.isPresent()) {
            AcceptedMessage restored = existing.get();
            acceptedMessagesById.putIfAbsent(messageId, restored);
            if (!restored.delivered()) {
                undeliveredMessagesByReceiver
                        .computeIfAbsent(restored.receiverUserId(), ignored -> new ConcurrentHashMap<>())
                        .put(messageId, restored);
            }
            sendAck(senderUserId, restored.ack());
            ImServerLogger.log(
                    "[IM] SEND_MESSAGE duplicate sender=%s messageId=%s serverSeq=%d ackOnly=true",
                    senderUserId,
                    messageId,
                    restored.serverSeq()
            );
            return;
        }
        acceptedMessagesById.put(messageId, newlyAccepted);
        undeliveredMessagesByReceiver
                .computeIfAbsent(receiverId, ignored -> new ConcurrentHashMap<>())
                .put(messageId, newlyAccepted);
        sendAck(senderUserId, ack);
        deliverOrKeepPending(receiverId, messageId, message, nextServerSeq);
    }

    private void handleGroupSendMessage(String senderUserId, String messageId, JsonObject message) {
        String groupId = message.get("groupId").getAsString();
        String conversationId = message.get("conversationId").getAsString();
        long clientSeq = message.get("clientSeq").getAsLong();
        if (!groupService.isMember(groupId, senderUserId)) {
            ImServerLogger.log("[IM] GROUP_SEND rejected non-member sender=%s groupId=%s messageId=%s", senderUserId, groupId, messageId);
            return;
        }
        List<String> recipients = groupService.recipientsForSend(groupId, senderUserId);
        long nextServerSeq = nextServerSeq(conversationId);
        long serverTime = clock.getAsLong();

        ImServerLogger.log(
                "[IM] GROUP_SEND sender=%s groupId=%s conversationId=%s messageId=%s clientSeq=%d serverSeq=%d recipients=%d content=%s",
                senderUserId,
                groupId,
                conversationId,
                messageId,
                clientSeq,
                nextServerSeq,
                recipients.size(),
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
        message.addProperty("groupName", groupService.groupName(groupId));

        JsonObject firstRecipientMessage = message.deepCopy();
        if (!recipients.isEmpty()) {
            firstRecipientMessage.addProperty("receiverId", recipients.get(0));
        }
        AcceptedMessage senderAccepted = new AcceptedMessage(
                ack.deepCopy(),
                firstRecipientMessage.deepCopy(),
                recipients.isEmpty() ? groupId : recipients.get(0),
                nextServerSeq,
                recipients.isEmpty()
        );
        Optional<AcceptedMessage> existing = acceptedMessageStore.saveIfAbsent(messageId, senderAccepted);
        if (existing.isPresent()) {
            AcceptedMessage restored = existing.get();
            acceptedMessagesById.putIfAbsent(messageId, restored);
            sendAck(senderUserId, restored.ack());
            ImServerLogger.log(
                    "[IM] GROUP_SEND duplicate sender=%s groupId=%s messageId=%s serverSeq=%d ackOnly=true",
                    senderUserId,
                    groupId,
                    messageId,
                    restored.serverSeq()
            );
            return;
        }
        acceptedMessagesById.put(messageId, senderAccepted);
        sendAck(senderUserId, ack);
        for (int index = 0; index < recipients.size(); index++) {
            String receiverId = recipients.get(index);
            JsonObject recipientMessage = message.deepCopy();
            recipientMessage.addProperty("receiverId", receiverId);
            AcceptedMessage receiverAccepted = new AcceptedMessage(
                    ack.deepCopy(),
                    recipientMessage.deepCopy(),
                    receiverId,
                    nextServerSeq,
                    false
            );
            if (index > 0) {
                acceptedMessageStore.saveDelivery(messageId, receiverAccepted);
            }
            undeliveredMessagesByReceiver
                    .computeIfAbsent(receiverId, ignored -> new ConcurrentHashMap<>())
                    .put(messageId, receiverAccepted);
            deliverOrKeepPending(receiverId, messageId, recipientMessage, nextServerSeq);
        }
    }

    public synchronized void handleDeliveryAck(String receiverUserId, ImPacket packet) {
        JsonObject ack = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String messageId = ack.get("messageId").getAsString();
        long serverSeq = ack.get("serverSeq").getAsLong();
        acceptedMessageStore.markDelivered(messageId, receiverUserId);
        AcceptedMessage receiverAccepted = undeliveredMessagesByReceiver
                .getOrDefault(receiverUserId, new ConcurrentHashMap<>())
                .get(messageId);
        if (receiverAccepted != null) {
            JsonObject message = receiverAccepted.message();
            if ("GROUP".equals(optionalString(message, "conversationType", "SINGLE"))) {
                ImServerLogger.log(
                        "[IM] GROUP_DELIVERY_ACK received groupId=%s receiver=%s messageId=%s serverSeq=%d",
                        optionalString(message, "groupId", ""),
                        receiverUserId,
                        messageId,
                        serverSeq
                );
            } else {
                ImServerLogger.log(
                        "[IM] DELIVERY_ACK received receiver=%s messageId=%s serverSeq=%d",
                        receiverUserId,
                        messageId,
                        serverSeq
                );
            }
            removeUndelivered(receiverUserId, messageId);
        }
        acceptedMessagesById.computeIfPresent(messageId, (ignored, accepted) -> {
            if (!accepted.receiverUserId().equals(receiverUserId)) {
                return accepted;
            }
            return accepted.markDelivered();
        });
    }

    public synchronized void handleReadAck(String socketUserId, ImPacket packet) {
        JsonObject ack = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String readerId = ack.get("readerId").getAsString();
        if (!socketUserId.equals(readerId)) {
            ImServerLogger.log("[IM] READ_ACK rejected socketUserId=%s readerId=%s", socketUserId, readerId);
            return;
        }
        String conversationType = optionalString(ack, "conversationType", "SINGLE");
        if ("GROUP".equals(conversationType)) {
            handleGroupReadAck(ack);
            return;
        }
        String peerId = ack.get("peerId").getAsString();
        registry.find(peerId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.READ_ACK, ack));
                    ImServerLogger.log(
                            "[IM] READ_ACK forwarded reader=%s peer=%s readUpToServerSeq=%d",
                            readerId,
                            peerId,
                            ack.get("readUpToServerSeq").getAsLong()
                    );
                },
                () -> ImServerLogger.log("[IM] READ_ACK skipped peer offline reader=%s peer=%s", readerId, peerId)
        );
    }

    private void handleGroupReadAck(JsonObject ack) {
        String conversationId = ack.get("conversationId").getAsString();
        String groupId = conversationId.startsWith("group:") ? conversationId.substring("group:".length()) : conversationId;
        String readerId = ack.get("readerId").getAsString();
        long readUpToServerSeq = ack.get("readUpToServerSeq").getAsLong();
        long readAt = ack.get("readAt").getAsLong();
        if (!groupService.isMember(groupId, readerId)) {
            ImServerLogger.log("[IM] GROUP_READ_ACK rejected non-member reader=%s groupId=%s", readerId, groupId);
            return;
        }
        boolean advanced = groupReadCursorStore.upsertIfGreater(groupId, readerId, readUpToServerSeq, readAt);
        if (!advanced) {
            ImServerLogger.log("[IM] GROUP_READ_ACK stale reader=%s groupId=%s seq=%d", readerId, groupId, readUpToServerSeq);
            return;
        }
        for (String memberId : groupService.membersForReadAck(groupId)) {
            registry.find(memberId).ifPresent(client -> client.send(packet(ImCommand.READ_ACK, ack)));
        }
        ImServerLogger.log("[IM] GROUP_READ_ACK broadcast reader=%s groupId=%s seq=%d", readerId, groupId, readUpToServerSeq);
    }

    public synchronized void handleRecallMessage(String socketUserId, ImPacket packet) {
        JsonObject request = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String messageId = request.get("messageId").getAsString();
        String requesterId = request.get("requesterId").getAsString();
        String conversationId = request.get("conversationId").getAsString();
        AcceptedMessage accepted = acceptedMessagesById.get(messageId);
        if (!socketUserId.equals(requesterId)) {
            sendRecallFailure(socketUserId, messageId, conversationId, "REQUESTER_MISMATCH");
            return;
        }
        if (accepted == null) {
            sendRecallFailure(socketUserId, messageId, conversationId, "NOT_FOUND");
            return;
        }
        String senderId = accepted.message().get("senderId").getAsString();
        if (!requesterId.equals(senderId)) {
            sendRecallFailure(socketUserId, messageId, conversationId, "NOT_SENDER");
            return;
        }
        if (!conversationId.equals(accepted.message().get("conversationId").getAsString())) {
            sendRecallFailure(socketUserId, messageId, conversationId, "CONVERSATION_MISMATCH");
            return;
        }
        if (accepted.recalled()) {
            sendRecallSuccess(socketUserId, accepted);
            return;
        }
        long serverTime = accepted.ack().get("serverTime").getAsLong();
        long recalledAt = clock.getAsLong();
        if (recalledAt - serverTime > RECALL_WINDOW_MS) {
            sendRecallFailure(socketUserId, messageId, conversationId, "EXPIRED");
            return;
        }
        AcceptedMessage recalled = accepted.markRecalled(requesterId, recalledAt);
        acceptedMessagesById.put(messageId, recalled);
        acceptedMessageStore.markRecalled(messageId, requesterId, recalledAt);
        sendRecallSuccess(socketUserId, recalled);
        queuePendingRecallNotifies(messageId);
        deliverPendingRecallNotifiesToOnlineReceivers(messageId);
    }

    public synchronized void handleRecallNotifyAck(String socketUserId, ImPacket packet) {
        JsonObject ack = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String receiverId = ack.get("receiverId").getAsString();
        String messageId = ack.get("messageId").getAsString();
        if (!socketUserId.equals(receiverId)) {
            ImServerLogger.log("[IM] RECALL_NOTIFY_ACK rejected socketUserId=%s receiverId=%s messageId=%s", socketUserId, receiverId, messageId);
            return;
        }
        acceptedMessageStore.markRecallNotified(messageId, receiverId);
        pendingRecallNotifiesByReceiver.computeIfPresent(receiverId, (ignored, eventsById) -> {
            eventsById.remove(messageId);
            return eventsById.isEmpty() ? null : eventsById;
        });
        acceptedMessagesById.computeIfPresent(messageId, (ignored, accepted) -> {
            if (!accepted.receiverUserId().equals(receiverId)) {
                return accepted;
            }
            return accepted.markRecallNotified();
        });
        ImServerLogger.log("[IM] RECALL_NOTIFY_ACK received receiver=%s messageId=%s", receiverId, messageId);
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
        String userId = registry.userIdOf(client).orElse(null);
        if (userId == null) {
            ImServerLogger.log("[IM] HEARTBEAT rejected unauthenticated client");
            return;
        }
        ImServerLogger.log("[IM] HEARTBEAT received userId=%s", userId);
        JsonObject body = new JsonObject();
        body.addProperty("serverTime", clock.getAsLong());
        client.send(packet(ImCommand.HEARTBEAT_ACK, body));
        ImServerLogger.log("[IM] HEARTBEAT_ACK sent userId=%s", userId);
    }

    public AuthFailureReason handleAuth(String token, OutboundClient client) {
        VerificationResult result = tokenService.verifyDetailed(token);
        String userId = result.userId();
        if (userId == null) {
            AuthFailureReason reason = result.failureReason();
            ImServerLogger.log("[IM] AUTH rejected reason=%s", reason);
            return reason;
        }
        registry.register(userId, client);
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        body.addProperty("serverTime", System.currentTimeMillis());
        client.send(packet(ImCommand.AUTH_ACK, body));
        client.recordStatus("AUTHENTICATED userId=" + userId + " authAck=sent");
        deliverQueuedMessages(userId, client);
        deliverPendingRecallNotifies(userId, client);
        replayGroupReadCursorsFor(userId, client);
        return null;
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

    private void deliverPendingRecallNotifies(String userId, OutboundClient client) {
        ConcurrentMap<String, RecallNotifyEvent> receiverEvents = pendingRecallNotifiesByReceiver.get(userId);
        if (receiverEvents == null) {
            return;
        }
        for (RecallNotifyEvent event : receiverEvents.values()) {
            client.send(packet(ImCommand.RECALL_NOTIFY, recallNotifyBody(event)));
            ImServerLogger.log(
                    "[IM] RECALL_NOTIFY delivered queued receiver=%s messageId=%s",
                    userId,
                    event.messageId()
            );
        }
    }

    void replayGroupReadCursorsFor(String userId, OutboundClient client) {
        List<GroupService.GroupRecord> joinedGroups = groupService.findGroupsByMember(userId);
        if (joinedGroups.isEmpty()) {
            return;
        }
        List<String> groupIds = new ArrayList<>();
        for (GroupService.GroupRecord group : joinedGroups) {
            groupIds.add(group.groupId());
        }
        for (GroupReadCursor cursor : groupReadCursorStore.findByMemberOf(groupIds)) {
            JsonObject body = new JsonObject();
            body.addProperty("conversationId", "group:" + cursor.groupId());
            body.addProperty("conversationType", "GROUP");
            body.addProperty("readerId", cursor.readerId());
            body.addProperty("readUpToServerSeq", cursor.readUpToServerSeq());
            body.addProperty("readAt", cursor.readAt());
            client.send(packet(ImCommand.READ_ACK, body));
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
        boolean isGroup = "GROUP".equals(optionalString(message, "conversationType", "SINGLE"));
        registry.find(receiverId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.RECEIVE_MESSAGE, message));
                    if (isGroup) {
                        ImServerLogger.log(
                                "[IM] GROUP_RECEIVE forwarded groupId=%s receiver=%s messageId=%s serverSeq=%d",
                                optionalString(message, "groupId", ""),
                                receiverId,
                                messageId,
                                serverSeq
                        );
                    } else {
                        ImServerLogger.log(
                                "[IM] RECEIVE_MESSAGE forwarded receiver=%s messageId=%s serverSeq=%d",
                                receiverId,
                                messageId,
                                serverSeq
                        );
                    }
                },
                () -> {
                    if (isGroup) {
                        ImServerLogger.log(
                                "[IM] GROUP_RECEIVE queued groupId=%s receiver=%s messageId=%s serverSeq=%d",
                                optionalString(message, "groupId", ""),
                                receiverId,
                                messageId,
                                serverSeq
                        );
                    } else {
                        ImServerLogger.log(
                                "[IM] RECEIVE_MESSAGE queued receiver offline receiver=%s messageId=%s serverSeq=%d",
                                receiverId,
                                messageId,
                                serverSeq
                        );
                    }
                }
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

    private void sendRecallFailure(String requesterId, String messageId, String conversationId, String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", messageId);
        body.addProperty("conversationId", conversationId);
        body.addProperty("success", false);
        body.addProperty("reason", reason);
        registry.find(requesterId).ifPresent(client -> client.send(packet(ImCommand.RECALL_ACK, body)));
    }

    private void sendRecallSuccess(String requesterId, AcceptedMessage accepted) {
        JsonObject body = recallNotifyBody(accepted);
        body.addProperty("success", true);
        registry.find(requesterId).ifPresent(client -> client.send(packet(ImCommand.RECALL_ACK, body)));
    }

    private JsonObject recallNotifyBody(AcceptedMessage accepted) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", accepted.message().get("messageId").getAsString());
        body.addProperty("conversationId", accepted.message().get("conversationId").getAsString());
        body.addProperty("recalledBy", accepted.recalledBy());
        body.addProperty("recalledAt", accepted.recalledAt());
        return body;
    }

    private JsonObject recallNotifyBody(RecallNotifyEvent event) {
        JsonObject body = new JsonObject();
        body.addProperty("messageId", event.messageId());
        body.addProperty("conversationId", event.conversationId());
        body.addProperty("recalledBy", event.recalledBy());
        body.addProperty("recalledAt", event.recalledAt());
        return body;
    }

    private void queuePendingRecallNotifies(String messageId) {
        for (StoredAcceptedMessage stored : acceptedMessageStore.loadAll()) {
            AcceptedMessage accepted = stored.accepted();
            if (!messageId.equals(accepted.message().get("messageId").getAsString()) || !accepted.recalled() || accepted.recallNotified()) {
                continue;
            }
            pendingRecallNotifiesByReceiver
                    .computeIfAbsent(accepted.receiverUserId(), ignored -> new ConcurrentHashMap<>())
                    .put(messageId, RecallNotifyEvent.from(accepted));
        }
    }

    private void deliverPendingRecallNotifiesToOnlineReceivers(String messageId) {
        for (ConcurrentMap<String, RecallNotifyEvent> eventsById : pendingRecallNotifiesByReceiver.values()) {
            RecallNotifyEvent event = eventsById.get(messageId);
            if (event == null) {
                continue;
            }
            registry.find(event.receiverId()).ifPresent(client -> client.send(packet(ImCommand.RECALL_NOTIFY, recallNotifyBody(event))));
        }
    }

    private void restoreAcceptedMessages() {
        for (StoredAcceptedMessage stored : acceptedMessageStore.loadAll()) {
            acceptedMessagesById.putIfAbsent(stored.messageId(), stored.accepted());
            if (!stored.accepted().delivered()) {
                undeliveredMessagesByReceiver
                        .computeIfAbsent(stored.accepted().receiverUserId(), ignored -> new ConcurrentHashMap<>())
                        .put(stored.messageId(), stored.accepted());
            }
            if (stored.accepted().recalled() && !stored.accepted().recallNotified()) {
                pendingRecallNotifiesByReceiver
                        .computeIfAbsent(stored.accepted().receiverUserId(), ignored -> new ConcurrentHashMap<>())
                        .put(stored.messageId(), RecallNotifyEvent.from(stored.accepted()));
            }
        }
    }

    private static String optionalString(JsonObject body, String name, String fallback) {
        return body.has(name) && !body.get(name).isJsonNull() ? body.get(name).getAsString() : fallback;
    }

    static record AcceptedMessage(
            JsonObject ack,
            JsonObject message,
            String receiverUserId,
            long serverSeq,
            boolean delivered,
            boolean recalled,
            String recalledBy,
            long recalledAt,
            boolean recallNotified
    ) {
        public AcceptedMessage(JsonObject ack, JsonObject message, String receiverUserId, long serverSeq, boolean delivered) {
            this(ack, message, receiverUserId, serverSeq, delivered, false, null, 0L, false);
        }

        private AcceptedMessage markDelivered() {
            return new AcceptedMessage(ack, message, receiverUserId, serverSeq, true, recalled, recalledBy, recalledAt, recallNotified);
        }

        private AcceptedMessage markRecalled(String recalledBy, long recalledAt) {
            return new AcceptedMessage(ack, message, receiverUserId, serverSeq, delivered, true, recalledBy, recalledAt, false);
        }

        private AcceptedMessage markRecallNotified() {
            return new AcceptedMessage(ack, message, receiverUserId, serverSeq, delivered, recalled, recalledBy, recalledAt, true);
        }
    }

    private record RecallNotifyEvent(
            String messageId,
            String conversationId,
            String receiverId,
            String recalledBy,
            long recalledAt
    ) {
        private static RecallNotifyEvent from(AcceptedMessage accepted) {
            return new RecallNotifyEvent(
                    accepted.message().get("messageId").getAsString(),
                    accepted.message().get("conversationId").getAsString(),
                    accepted.receiverUserId(),
                    accepted.recalledBy(),
                    accepted.recalledAt()
            );
        }
    }

    public record StoredAcceptedMessage(String messageId, AcceptedMessage accepted) {
    }

    public interface AcceptedMessageStore {
        Optional<AcceptedMessage> saveIfAbsent(String messageId, AcceptedMessage accepted);

        void saveDelivery(String messageId, AcceptedMessage accepted);

        void markDelivered(String messageId, String receiverUserId);

        void markRecalled(String messageId, String recalledBy, long recalledAt);

        void markRecallNotified(String messageId, String receiverUserId);

        List<StoredAcceptedMessage> loadAll();
    }

    public static final class InMemoryAcceptedMessageStore implements AcceptedMessageStore {
        private final ConcurrentMap<String, AcceptedMessage> acceptedMessagesById = new ConcurrentHashMap<>();

        @Override
        public Optional<AcceptedMessage> saveIfAbsent(String messageId, AcceptedMessage accepted) {
            return Optional.ofNullable(acceptedMessagesById.putIfAbsent(messageId, accepted));
        }

        @Override
        public void saveDelivery(String messageId, AcceptedMessage accepted) {
            acceptedMessagesById.putIfAbsent(storeKey(messageId, accepted.receiverUserId()), accepted);
        }

        @Override
        public void markDelivered(String messageId, String receiverUserId) {
            acceptedMessagesById.computeIfPresent(messageId, (ignored, accepted) ->
                    accepted.receiverUserId().equals(receiverUserId) ? accepted.markDelivered() : accepted
            );
            acceptedMessagesById.computeIfPresent(storeKey(messageId, receiverUserId), (ignored, accepted) -> accepted.markDelivered());
        }

        @Override
        public void markRecalled(String messageId, String recalledBy, long recalledAt) {
            acceptedMessagesById.replaceAll((ignored, accepted) ->
                    messageId.equals(accepted.message().get("messageId").getAsString())
                            ? accepted.markRecalled(recalledBy, recalledAt)
                            : accepted
            );
        }

        @Override
        public void markRecallNotified(String messageId, String receiverUserId) {
            acceptedMessagesById.replaceAll((ignored, accepted) ->
                    messageId.equals(accepted.message().get("messageId").getAsString()) && receiverUserId.equals(accepted.receiverUserId())
                            ? accepted.markRecallNotified()
                            : accepted
            );
        }

        @Override
        public List<StoredAcceptedMessage> loadAll() {
            return acceptedMessagesById.entrySet().stream()
                    .map(entry -> new StoredAcceptedMessage(entry.getValue().message().get("messageId").getAsString(), entry.getValue()))
                    .toList();
        }

        private String storeKey(String messageId, String receiverUserId) {
            return messageId + "\u0000" + receiverUserId;
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

    public static final class SQLiteAcceptedMessageStore implements AcceptedMessageStore {
        private final String jdbcUrl;

        public SQLiteAcceptedMessageStore(Path databasePath) {
            try {
                Path parent = databasePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Unable to create accepted message database directory", error);
            }
            this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().toUri();
            initialize();
        }

        @Override
        public synchronized Optional<AcceptedMessage> saveIfAbsent(String messageId, AcceptedMessage accepted) {
            Optional<AcceptedMessage> existing = find(messageId, null);
            if (existing.isPresent()) {
                return existing;
            }
            return insertDelivery(messageId, accepted) ? Optional.empty() : find(messageId, null);
        }

        @Override
        public synchronized void saveDelivery(String messageId, AcceptedMessage accepted) {
            insertDelivery(messageId, accepted);
        }

        @Override
        public synchronized void markDelivered(String messageId, String receiverUserId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         """
                         UPDATE accepted_messages
                         SET delivered = 1
                         WHERE message_id = ? AND receiver_id = ?
                         """
                 )) {
                statement.setString(1, messageId);
                statement.setString(2, receiverUserId);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to update accepted message delivery state", error);
            }
        }

        @Override
        public synchronized void markRecalled(String messageId, String recalledBy, long recalledAt) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         """
                         UPDATE accepted_messages
                         SET recalled = 1, recalled_by = ?, recalled_at = ?, recall_notified = 0
                         WHERE message_id = ?
                         """
                 )) {
                statement.setString(1, recalledBy);
                statement.setLong(2, recalledAt);
                statement.setString(3, messageId);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to update accepted message recall state", error);
            }
        }

        @Override
        public synchronized void markRecallNotified(String messageId, String receiverUserId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         """
                         UPDATE accepted_messages
                         SET recall_notified = 1
                         WHERE message_id = ? AND receiver_id = ?
                         """
                 )) {
                statement.setString(1, messageId);
                statement.setString(2, receiverUserId);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to update accepted message recall notify state", error);
            }
        }

        @Override
        public synchronized List<StoredAcceptedMessage> loadAll() {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         """
                         SELECT message_id, receiver_id, server_seq, delivered, recalled, recalled_by, recalled_at, recall_notified, ack_json, message_json
                         FROM accepted_messages
                         ORDER BY conversation_id ASC, server_seq ASC
                         """
                 );
                 ResultSet resultSet = statement.executeQuery()) {
                List<StoredAcceptedMessage> restored = new ArrayList<>();
                while (resultSet.next()) {
                    JsonObject ack = JsonParser.parseString(resultSet.getString("ack_json")).getAsJsonObject();
                    JsonObject message = JsonParser.parseString(resultSet.getString("message_json")).getAsJsonObject();
                    restored.add(new StoredAcceptedMessage(
                            resultSet.getString("message_id"),
                            new AcceptedMessage(
                                    ack,
                                    message,
                                    resultSet.getString("receiver_id"),
                                    resultSet.getLong("server_seq"),
                                    resultSet.getInt("delivered") == 1,
                                    resultSet.getInt("recalled") == 1,
                                    resultSet.getString("recalled_by"),
                                    resultSet.getLong("recalled_at"),
                                    resultSet.getInt("recall_notified") == 1
                            )
                    ));
                }
                return restored;
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to load accepted messages", error);
            }
        }

        private boolean insertDelivery(String messageId, AcceptedMessage accepted) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         """
                         INSERT OR IGNORE INTO accepted_messages(
                           message_id, conversation_id, sender_id, receiver_id, client_seq, server_seq,
                           content, timestamp, server_time, delivered, recalled, recalled_by, recalled_at, recall_notified, ack_json, message_json
                         ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """
                 )) {
                JsonObject message = accepted.message();
                JsonObject ack = accepted.ack();
                statement.setString(1, messageId);
                statement.setString(2, stringField(message, "conversationId"));
                statement.setString(3, stringField(message, "senderId"));
                statement.setString(4, accepted.receiverUserId());
                statement.setLong(5, longField(message, "clientSeq"));
                statement.setLong(6, accepted.serverSeq());
                statement.setString(7, stringField(message, "content"));
                statement.setLong(8, longField(message, "timestamp"));
                statement.setLong(9, longField(ack, "serverTime"));
                statement.setInt(10, accepted.delivered() ? 1 : 0);
                statement.setInt(11, accepted.recalled() ? 1 : 0);
                statement.setString(12, accepted.recalledBy());
                statement.setLong(13, accepted.recalledAt());
                statement.setInt(14, accepted.recallNotified() ? 1 : 0);
                statement.setString(15, ack.toString());
                statement.setString(16, message.toString());
                return statement.executeUpdate() == 1;
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to persist accepted message", error);
            }
        }

        private Optional<AcceptedMessage> find(String messageId, String receiverUserId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         receiverUserId == null
                                 ? """
                                   SELECT receiver_id, server_seq, delivered, recalled, recalled_by, recalled_at, recall_notified, ack_json, message_json
                                   FROM accepted_messages
                                   WHERE message_id = ?
                                   ORDER BY receiver_id ASC
                                   LIMIT 1
                                   """
                                 : """
                                   SELECT receiver_id, server_seq, delivered, recalled, recalled_by, recalled_at, recall_notified, ack_json, message_json
                                   FROM accepted_messages
                                   WHERE message_id = ? AND receiver_id = ?
                                   """
                 )) {
                statement.setString(1, messageId);
                if (receiverUserId != null) {
                    statement.setString(2, receiverUserId);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new AcceptedMessage(
                            JsonParser.parseString(resultSet.getString("ack_json")).getAsJsonObject(),
                            JsonParser.parseString(resultSet.getString("message_json")).getAsJsonObject(),
                            resultSet.getString("receiver_id"),
                            resultSet.getLong("server_seq"),
                            resultSet.getInt("delivered") == 1,
                            resultSet.getInt("recalled") == 1,
                            resultSet.getString("recalled_by"),
                            resultSet.getLong("recalled_at"),
                            resultSet.getInt("recall_notified") == 1
                    ));
                }
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to find accepted message", error);
            }
        }

        private void initialize() {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS accepted_messages (
                          message_id TEXT NOT NULL,
                          conversation_id TEXT NOT NULL,
                          sender_id TEXT NOT NULL,
                          receiver_id TEXT NOT NULL,
                          client_seq INTEGER NOT NULL,
                          server_seq INTEGER NOT NULL,
                          content TEXT NOT NULL,
                          timestamp INTEGER NOT NULL,
                          server_time INTEGER NOT NULL,
                          delivered INTEGER NOT NULL DEFAULT 0,
                          recalled INTEGER NOT NULL DEFAULT 0,
                          recalled_by TEXT,
                          recalled_at INTEGER NOT NULL DEFAULT 0,
                          recall_notified INTEGER NOT NULL DEFAULT 0,
                          ack_json TEXT NOT NULL,
                          message_json TEXT NOT NULL,
                          PRIMARY KEY(message_id, receiver_id),
                          UNIQUE(conversation_id, server_seq, receiver_id)
                        )
                        """
                );
                migrateAcceptedMessagesForPerReceiverDeliveryIfNeeded(connection, statement);
                addColumnIfMissing(connection, statement, "recalled", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(connection, statement, "recalled_by", "TEXT");
                addColumnIfMissing(connection, statement, "recalled_at", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(connection, statement, "recall_notified", "INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to initialize accepted message database", error);
            }
        }

        private void migrateAcceptedMessagesForPerReceiverDeliveryIfNeeded(Connection connection, Statement statement) throws SQLException {
            addColumnIfMissing(connection, statement, "recalled", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, statement, "recalled_by", "TEXT");
            addColumnIfMissing(connection, statement, "recalled_at", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, statement, "recall_notified", "INTEGER NOT NULL DEFAULT 0");
            boolean hasOldUnique = false;
            boolean hasCompositePrimaryKey = false;
            try (ResultSet indexes = connection.createStatement().executeQuery("PRAGMA index_list(accepted_messages)")) {
                while (indexes.next()) {
                    if (indexes.getInt("unique") != 1) {
                        continue;
                    }
                    List<String> columns = indexColumns(connection, indexes.getString("name"));
                    if (columns.equals(List.of("conversation_id", "server_seq"))) {
                        hasOldUnique = true;
                    }
                    if (columns.equals(List.of("message_id", "receiver_id"))) {
                        hasCompositePrimaryKey = true;
                    }
                }
            }
            if (!hasOldUnique && hasCompositePrimaryKey) {
                return;
            }
            statement.executeUpdate("ALTER TABLE accepted_messages RENAME TO accepted_messages_legacy");
            statement.executeUpdate(
                    """
                    CREATE TABLE accepted_messages (
                      message_id TEXT NOT NULL,
                      conversation_id TEXT NOT NULL,
                      sender_id TEXT NOT NULL,
                      receiver_id TEXT NOT NULL,
                      client_seq INTEGER NOT NULL,
                      server_seq INTEGER NOT NULL,
                      content TEXT NOT NULL,
                      timestamp INTEGER NOT NULL,
                      server_time INTEGER NOT NULL,
                      delivered INTEGER NOT NULL DEFAULT 0,
                      recalled INTEGER NOT NULL DEFAULT 0,
                      recalled_by TEXT,
                      recalled_at INTEGER NOT NULL DEFAULT 0,
                      recall_notified INTEGER NOT NULL DEFAULT 0,
                      ack_json TEXT NOT NULL,
                      message_json TEXT NOT NULL,
                      PRIMARY KEY(message_id, receiver_id),
                      UNIQUE(conversation_id, server_seq, receiver_id)
                    )
                    """
            );
            statement.executeUpdate(
                    """
                    INSERT OR IGNORE INTO accepted_messages(
                      message_id, conversation_id, sender_id, receiver_id, client_seq, server_seq,
                      content, timestamp, server_time, delivered, recalled, recalled_by, recalled_at, recall_notified, ack_json, message_json
                    )
                    SELECT message_id, conversation_id, sender_id, receiver_id, client_seq, server_seq,
                      content, timestamp, server_time, delivered, recalled, recalled_by, recalled_at, recall_notified, ack_json, message_json
                    FROM accepted_messages_legacy
                    """
            );
            statement.executeUpdate("DROP TABLE accepted_messages_legacy");
        }

        private List<String> indexColumns(Connection connection, String indexName) throws SQLException {
            List<String> columns = new ArrayList<>();
            try (ResultSet info = connection.createStatement().executeQuery("PRAGMA index_info(" + indexName + ")")) {
                while (info.next()) {
                    columns.add(info.getString("name"));
                }
            }
            return columns;
        }

        private void addColumnIfMissing(
                Connection connection,
                Statement statement,
                String columnName,
                String columnDefinition
        ) throws SQLException {
            Set<String> columns = new HashSet<>();
            try (ResultSet resultSet = connection.createStatement().executeQuery("PRAGMA table_info(accepted_messages)")) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("name"));
                }
            }
            if (!columns.contains(columnName)) {
                statement.executeUpdate("ALTER TABLE accepted_messages ADD COLUMN " + columnName + " " + columnDefinition);
            }
        }

        private static String stringField(JsonObject body, String name) {
            return body.get(name).getAsString();
        }

        private static long longField(JsonObject body, String name) {
            return body.get(name).getAsLong();
        }
    }

    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;
}
