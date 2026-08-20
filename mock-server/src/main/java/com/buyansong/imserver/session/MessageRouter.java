package com.buyansong.imserver.session;

import com.buyansong.imserver.ImServerLogger;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.auth.TokenService.AuthFailureReason;
import com.buyansong.imserver.auth.TokenService.VerificationResult;
import com.buyansong.imserver.auth.UserStore;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.groupread.GroupReadCursor;
import com.buyansong.imserver.groupread.GroupReadCursorStore;
import com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore;
import com.buyansong.imserver.push.InMemoryPushNotificationStore;
import com.buyansong.imserver.push.PushNotificationStore;
import com.buyansong.im.protocol.v2.ChatMessagePayload;
import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.DeliveryAck;
import com.buyansong.im.protocol.v2.Heartbeat;
import com.buyansong.im.protocol.v2.MessageAck;
import com.buyansong.im.protocol.v2.MessageType;
import com.buyansong.im.protocol.v2.ReadAck;
import com.buyansong.im.protocol.v2.RecallAck;
import com.buyansong.im.protocol.v2.RecallMessage;
import com.buyansong.im.protocol.v2.RecallNotify;
import com.buyansong.im.protocol.v2.RecallNotifyAck;
import com.buyansong.im.protocol.v2.SendMessage;
import com.buyansong.imserver.protocol.ProtocolException;
import com.google.gson.JsonParser;

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
    private final UserStore userStore;
    private final PushNotificationStore pushNotificationStore;
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
        this(registry, tokenService, serverSeqStore, acceptedMessageStore, groupService,
                groupReadCursorStore, null, clock);
    }

    public MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore,
            GroupService groupService,
            GroupReadCursorStore groupReadCursorStore,
            UserStore userStore,
            LongSupplier clock
    ) {
        this(registry, tokenService, serverSeqStore, acceptedMessageStore, groupService, groupReadCursorStore,
                userStore, new InMemoryPushNotificationStore(), clock);
    }

    public MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore,
            GroupService groupService,
            GroupReadCursorStore groupReadCursorStore,
            UserStore userStore,
            PushNotificationStore pushNotificationStore,
            LongSupplier clock
    ) {
        this.registry = registry;
        this.tokenService = tokenService;
        this.serverSeqStore = serverSeqStore;
        this.acceptedMessageStore = acceptedMessageStore;
        this.groupService = groupService;
        this.groupReadCursorStore = groupReadCursorStore;
        this.userStore = userStore;
        this.pushNotificationStore = pushNotificationStore;
        this.clock = clock;
        restoreAcceptedMessages();
    }

    public synchronized void handleSendMessage(String senderUserId, SendMessage request) {
        ChatMessagePayload requestMessage = request.getMessage();
        requireConversationType(requestMessage.getConversationType());
        requireMessageType(requestMessage.getMessageType());
        String messageId = requestMessage.getMessageId();
        boolean isGroup = requestMessage.getConversationType() == ConversationType.CONVERSATION_TYPE_GROUP;
        AcceptedMessage accepted = acceptedMessagesById.get(messageId);
        if (accepted != null) {
            sendAck(senderUserId, accepted.ack());
            ImServerLogger.log(
                    "[IM] %s duplicate sender=%s messageId=%s serverSeq=%d ackOnly=true",
                    isGroup ? "GROUP_SEND" : "SEND_MESSAGE",
                    senderUserId,
                    messageId,
                    accepted.serverSeq()
            );
            return;
        }
        if (isGroup) {
            handleGroupSendMessage(senderUserId, requestMessage);
            return;
        }
        String receiverId = requestMessage.getReceiverId();
        String conversationId = requestMessage.getConversationId();
        long nextServerSeq = nextServerSeq(conversationId);
        long serverTime = clock.getAsLong();

        ImServerLogger.log(
                "[IM] SEND_MESSAGE sender=%s receiver=%s conversationId=%s messageId=%s serverSeq=%d content=%s",
                senderUserId,
                receiverId,
                conversationId,
                messageId,
                nextServerSeq,
                requestMessage.getContent()
        );

        MessageAck ack = MessageAck.newBuilder()
                .setMessageId(messageId)
                .setConversationId(conversationId)
                .setServerSeq(nextServerSeq)
                .setServerTime(serverTime)
                .build();

        // Sender identity always comes from the authenticated socket, never the payload.
        ChatMessagePayload.Builder messageBuilder = requestMessage.toBuilder()
                .setSenderId(senderUserId)
                .setServerSeq(nextServerSeq)
                .setServerTime(serverTime);
        addSenderProfileVersion(messageBuilder, senderUserId);
        ChatMessagePayload message = messageBuilder.build();
        AcceptedMessage newlyAccepted = new AcceptedMessage(
                ack,
                message,
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

    private void handleGroupSendMessage(String senderUserId, ChatMessagePayload requestMessage) {
        String messageId = requestMessage.getMessageId();
        String groupId = requestMessage.getGroupId();
        String conversationId = requestMessage.getConversationId();
        if (!groupService.isMember(groupId, senderUserId)) {
            ImServerLogger.log("[IM] GROUP_SEND rejected non-member sender=%s groupId=%s messageId=%s", senderUserId, groupId, messageId);
            return;
        }
        List<String> recipients = groupService.recipientsForSend(groupId, senderUserId);
        long nextServerSeq = nextServerSeq(conversationId);
        long serverTime = clock.getAsLong();

        ImServerLogger.log(
                "[IM] GROUP_SEND sender=%s groupId=%s conversationId=%s messageId=%s serverSeq=%d recipients=%d content=%s",
                senderUserId,
                groupId,
                conversationId,
                messageId,
                nextServerSeq,
                recipients.size(),
                requestMessage.getContent()
        );

        MessageAck ack = MessageAck.newBuilder()
                .setMessageId(messageId)
                .setConversationId(conversationId)
                .setServerSeq(nextServerSeq)
                .setServerTime(serverTime)
                .build();

        ChatMessagePayload.Builder messageBuilder = requestMessage.toBuilder()
                .setSenderId(senderUserId)
                .setServerSeq(nextServerSeq)
                .setServerTime(serverTime)
                .setGroupName(groupService.groupName(groupId));
        addSenderProfileVersion(messageBuilder, senderUserId);
        ChatMessagePayload message = messageBuilder.build();

        ChatMessagePayload firstRecipientMessage = recipients.isEmpty()
                ? message
                : message.toBuilder().setReceiverId(recipients.get(0)).build();
        AcceptedMessage senderAccepted = new AcceptedMessage(
                ack,
                firstRecipientMessage,
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
            ChatMessagePayload recipientMessage = message.toBuilder().setReceiverId(receiverId).build();
            AcceptedMessage receiverAccepted = new AcceptedMessage(
                    ack,
                    recipientMessage,
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

    public synchronized void handleDeliveryAck(String receiverUserId, DeliveryAck ack) {
        String messageId = ack.getMessageId();
        long serverSeq = ack.getServerSeq();
        acceptedMessageStore.markDelivered(messageId, receiverUserId);
        AcceptedMessage receiverAccepted = undeliveredMessagesByReceiver
                .getOrDefault(receiverUserId, new ConcurrentHashMap<>())
                .get(messageId);
        if (receiverAccepted != null) {
            ChatMessagePayload message = receiverAccepted.message();
            if (message.getConversationType() == ConversationType.CONVERSATION_TYPE_GROUP) {
                ImServerLogger.log(
                        "[IM] GROUP_DELIVERY_ACK received groupId=%s receiver=%s messageId=%s serverSeq=%d",
                        message.getGroupId(),
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

    public synchronized void handleReadAck(String socketUserId, ReadAck ack) {
        String readerId = ack.getReaderId();
        if (!socketUserId.equals(readerId)) {
            ImServerLogger.log("[IM] READ_ACK rejected socketUserId=%s readerId=%s", socketUserId, readerId);
            return;
        }
        if (ack.getConversationType() == ConversationType.CONVERSATION_TYPE_GROUP) {
            handleGroupReadAck(ack);
            return;
        }
        String peerId = ack.getPeerId();
        registry.find(peerId).ifPresentOrElse(
                client -> {
                    client.send(MessageProtoMapper.readAckEnvelope(ack));
                    ImServerLogger.log(
                            "[IM] READ_ACK forwarded reader=%s peer=%s readUpToServerSeq=%d",
                            readerId,
                            peerId,
                            ack.getReadUpToServerSeq()
                    );
                },
                () -> ImServerLogger.log("[IM] READ_ACK skipped peer offline reader=%s peer=%s", readerId, peerId)
        );
    }

    private void handleGroupReadAck(ReadAck ack) {
        String conversationId = ack.getConversationId();
        String groupId = conversationId.startsWith("group:") ? conversationId.substring("group:".length()) : conversationId;
        String readerId = ack.getReaderId();
        long readUpToServerSeq = ack.getReadUpToServerSeq();
        long readAt = ack.getReadAt();
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
            registry.find(memberId).ifPresent(client -> client.send(MessageProtoMapper.readAckEnvelope(ack)));
        }
        ImServerLogger.log("[IM] GROUP_READ_ACK broadcast reader=%s groupId=%s seq=%d", readerId, groupId, readUpToServerSeq);
    }

    public synchronized void handleRecallMessage(String socketUserId, RecallMessage request) {
        String messageId = request.getMessageId();
        String requesterId = request.getRequesterId();
        String conversationId = request.getConversationId();
        AcceptedMessage accepted = acceptedMessagesById.get(messageId);
        if (!socketUserId.equals(requesterId)) {
            sendRecallFailure(socketUserId, messageId, conversationId, "REQUESTER_MISMATCH");
            return;
        }
        if (accepted == null) {
            sendRecallFailure(socketUserId, messageId, conversationId, "NOT_FOUND");
            return;
        }
        String senderId = accepted.message().getSenderId();
        if (!requesterId.equals(senderId)) {
            sendRecallFailure(socketUserId, messageId, conversationId, "NOT_SENDER");
            return;
        }
        if (!conversationId.equals(accepted.message().getConversationId())) {
            sendRecallFailure(socketUserId, messageId, conversationId, "CONVERSATION_MISMATCH");
            return;
        }
        if (accepted.recalled()) {
            sendRecallSuccess(socketUserId, accepted);
            return;
        }
        long serverTime = accepted.ack().getServerTime();
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

    public synchronized void handleRecallNotifyAck(String socketUserId, RecallNotifyAck ack) {
        String receiverId = ack.getReceiverId();
        String messageId = ack.getMessageId();
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

    private void sendAck(String senderUserId, MessageAck ack) {
        registry.find(senderUserId).ifPresentOrElse(
                client -> {
                    client.send(MessageProtoMapper.messageAckEnvelope(ack));
                    ImServerLogger.log(
                            "[IM] MESSAGE_ACK sent sender=%s messageId=%s serverSeq=%d",
                            senderUserId,
                            ack.getMessageId(),
                            ack.getServerSeq()
                    );
                },
                () -> ImServerLogger.log("[IM] MESSAGE_ACK skipped sender offline sender=%s messageId=%s", senderUserId, ack.getMessageId())
        );
    }

    public void handleHeartbeat(OutboundClient client, Heartbeat heartbeat) {
        String userId = registry.userIdOf(client).orElse(null);
        if (userId == null) {
            ImServerLogger.log("[IM] HEARTBEAT rejected unauthenticated client");
            return;
        }
        ImServerLogger.log("[IM] HEARTBEAT received userId=%s", userId);
        long serverTime = clock.getAsLong();
        List<String> unackedMessageIds = heartbeat.getUnackedMessageIdsList();
        List<String> receivedMessageIds = null;
        if (!unackedMessageIds.isEmpty()) {
            receivedMessageIds = new ArrayList<>();
            for (String messageId : unackedMessageIds) {
                if (acceptedMessageStore.existsForSender(messageId, userId)) {
                    receivedMessageIds.add(messageId);
                }
            }
            ImServerLogger.log(
                    "[IM] HEARTBEAT reconcile userId=%s unacked=%d received=%d",
                    userId,
                    unackedMessageIds.size(),
                    receivedMessageIds.size()
            );
        }
        client.send(MessageProtoMapper.heartbeatAckEnvelope(serverTime, receivedMessageIds));
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
        client.send(MessageProtoMapper.authAckEnvelope(userId, System.currentTimeMillis()));
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
            client.send(MessageProtoMapper.receiveMessageEnvelope(accepted.message()));
            ImServerLogger.log(
                    "[IM] RECEIVE_MESSAGE delivered queued receiver=%s messageId=%s serverSeq=%d",
                    userId,
                    accepted.message().getMessageId(),
                    accepted.message().getServerSeq()
            );
        }
    }

    private void deliverPendingRecallNotifies(String userId, OutboundClient client) {
        ConcurrentMap<String, RecallNotifyEvent> receiverEvents = pendingRecallNotifiesByReceiver.get(userId);
        if (receiverEvents == null) {
            return;
        }
        for (RecallNotifyEvent event : receiverEvents.values()) {
            client.send(MessageProtoMapper.recallNotifyEnvelope(recallNotifyOf(event)));
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
            client.send(MessageProtoMapper.readAckEnvelope(ReadAck.newBuilder()
                    .setConversationId("group:" + cursor.groupId())
                    .setConversationType(ConversationType.CONVERSATION_TYPE_GROUP)
                    .setReaderId(cursor.readerId())
                    .setReadUpToServerSeq(cursor.readUpToServerSeq())
                    .setReadAt(cursor.readAt())
                    .build()));
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

    private void deliverOrKeepPending(String receiverId, String messageId, ChatMessagePayload message, long serverSeq) {
        boolean isGroup = message.getConversationType() == ConversationType.CONVERSATION_TYPE_GROUP;
        registry.find(receiverId).ifPresentOrElse(
                client -> {
                    client.send(MessageProtoMapper.receiveMessageEnvelope(message));
                    if (isGroup) {
                        ImServerLogger.log(
                                "[IM] GROUP_RECEIVE forwarded groupId=%s receiver=%s messageId=%s serverSeq=%d",
                                message.getGroupId(),
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
                    // Push store keeps the legacy camelCase JSON snapshot; the mapper
                    // produces it from the typed payload at this persistence boundary.
                    pushNotificationStore.enqueueIfAbsent(
                            receiverId,
                            MessageProtoMapper.messageToStoredJson(message),
                            clock.getAsLong()
                    );
                    if (isGroup) {
                        ImServerLogger.log(
                                "[IM] GROUP_RECEIVE queued groupId=%s receiver=%s messageId=%s serverSeq=%d",
                                message.getGroupId(),
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

    private void addSenderProfileVersion(ChatMessagePayload.Builder message, String senderUserId) {
        if (userStore == null) {
            return;
        }
        userStore.findByPhone(senderUserId)
                .ifPresent(sender -> message.setSenderProfileVersion(sender.profileVersion()));
    }

    private void removeUndelivered(String receiverUserId, String messageId) {
        undeliveredMessagesByReceiver.computeIfPresent(receiverUserId, (ignored, messagesById) -> {
            messagesById.remove(messageId);
            return messagesById.isEmpty() ? null : messagesById;
        });
    }

    private void sendRecallFailure(String requesterId, String messageId, String conversationId, String reason) {
        RecallAck ack = RecallAck.newBuilder()
                .setMessageId(messageId)
                .setConversationId(conversationId)
                .setSuccess(false)
                .setReason(reason)
                .build();
        registry.find(requesterId).ifPresent(client -> client.send(MessageProtoMapper.recallAckEnvelope(ack)));
    }

    private void sendRecallSuccess(String requesterId, AcceptedMessage accepted) {
        RecallAck ack = RecallAck.newBuilder()
                .setMessageId(accepted.message().getMessageId())
                .setConversationId(accepted.message().getConversationId())
                .setSuccess(true)
                .setRecalledBy(accepted.recalledBy())
                .setRecalledAt(accepted.recalledAt())
                .build();
        registry.find(requesterId).ifPresent(client -> client.send(MessageProtoMapper.recallAckEnvelope(ack)));
    }

    private RecallNotify recallNotifyOf(AcceptedMessage accepted) {
        return RecallNotify.newBuilder()
                .setMessageId(accepted.message().getMessageId())
                .setConversationId(accepted.message().getConversationId())
                .setRecalledBy(accepted.recalledBy())
                .setRecalledAt(accepted.recalledAt())
                .build();
    }

    private RecallNotify recallNotifyOf(RecallNotifyEvent event) {
        return RecallNotify.newBuilder()
                .setMessageId(event.messageId())
                .setConversationId(event.conversationId())
                .setRecalledBy(event.recalledBy())
                .setRecalledAt(event.recalledAt())
                .build();
    }

    private void queuePendingRecallNotifies(String messageId) {
        for (StoredAcceptedMessage stored : acceptedMessageStore.loadAll()) {
            AcceptedMessage accepted = stored.accepted();
            if (!messageId.equals(accepted.message().getMessageId()) || !accepted.recalled() || accepted.recallNotified()) {
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
            registry.find(event.receiverId()).ifPresent(client ->
                    client.send(MessageProtoMapper.recallNotifyEnvelope(recallNotifyOf(event))));
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

    private static void requireConversationType(ConversationType type) {
        switch (type) {
            case CONVERSATION_TYPE_SINGLE, CONVERSATION_TYPE_GROUP -> {
            }
            default -> throw new ProtocolException("Unsupported conversation type: " + type);
        }
    }

    private static void requireMessageType(MessageType type) {
        switch (type) {
            case MESSAGE_TYPE_TEXT, MESSAGE_TYPE_IMAGE -> {
            }
            default -> throw new ProtocolException("Unsupported message type: " + type);
        }
    }

    static record AcceptedMessage(
            MessageAck ack,
            ChatMessagePayload message,
            String receiverUserId,
            long serverSeq,
            boolean delivered,
            boolean recalled,
            String recalledBy,
            long recalledAt,
            boolean recallNotified
    ) {
        public AcceptedMessage(MessageAck ack, ChatMessagePayload message, String receiverUserId, long serverSeq, boolean delivered) {
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
                    accepted.message().getMessageId(),
                    accepted.message().getConversationId(),
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

        boolean existsForSender(String messageId, String senderUserId);

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
                    messageId.equals(accepted.message().getMessageId())
                            ? accepted.markRecalled(recalledBy, recalledAt)
                            : accepted
            );
        }

        @Override
        public void markRecallNotified(String messageId, String receiverUserId) {
            acceptedMessagesById.replaceAll((ignored, accepted) ->
                    messageId.equals(accepted.message().getMessageId()) && receiverUserId.equals(accepted.receiverUserId())
                            ? accepted.markRecallNotified()
                            : accepted
            );
        }

        @Override
        public boolean existsForSender(String messageId, String senderUserId) {
            AcceptedMessage accepted = acceptedMessagesById.get(messageId);
            return accepted != null && senderUserId.equals(accepted.message().getSenderId());
        }

        @Override
        public List<StoredAcceptedMessage> loadAll() {
            return acceptedMessagesById.entrySet().stream()
                    .map(entry -> new StoredAcceptedMessage(entry.getValue().message().getMessageId(), entry.getValue()))
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
                    MessageAck ack = MessageProtoMapper.storedJsonToAck(
                            JsonParser.parseString(resultSet.getString("ack_json")).getAsJsonObject());
                    ChatMessagePayload message = MessageProtoMapper.storedJsonToMessage(
                            JsonParser.parseString(resultSet.getString("message_json")).getAsJsonObject());
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

        @Override
        public synchronized boolean existsForSender(String messageId, String senderUserId) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT 1 FROM accepted_messages WHERE message_id = ? AND sender_id = ? LIMIT 1"
                 )) {
                statement.setString(1, messageId);
                statement.setString(2, senderUserId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            } catch (SQLException error) {
                throw new IllegalStateException("Unable to check accepted message existence", error);
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
                ChatMessagePayload message = accepted.message();
                MessageAck ack = accepted.ack();
                statement.setString(1, messageId);
                statement.setString(2, message.getConversationId());
                statement.setString(3, message.getSenderId());
                statement.setString(4, accepted.receiverUserId());
                // client_seq is a legacy column kept only to avoid a destructive table
                // rebuild; the field was removed from the protocol, always store 0.
                statement.setLong(5, 0L);
                statement.setLong(6, accepted.serverSeq());
                statement.setString(7, message.getContent());
                statement.setLong(8, message.getClientTime());
                statement.setLong(9, ack.getServerTime());
                statement.setInt(10, accepted.delivered() ? 1 : 0);
                statement.setInt(11, accepted.recalled() ? 1 : 0);
                statement.setString(12, accepted.recalledBy());
                statement.setLong(13, accepted.recalledAt());
                statement.setInt(14, accepted.recallNotified() ? 1 : 0);
                statement.setString(15, MessageProtoMapper.ackToStoredJson(ack).toString());
                statement.setString(16, MessageProtoMapper.messageToStoredJson(message).toString());
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
                            MessageProtoMapper.storedJsonToAck(
                                    JsonParser.parseString(resultSet.getString("ack_json")).getAsJsonObject()),
                            MessageProtoMapper.storedJsonToMessage(
                                    JsonParser.parseString(resultSet.getString("message_json")).getAsJsonObject()),
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
    }

    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;
}
