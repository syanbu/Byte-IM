package com.codex.imserver.session;

import com.codex.imserver.protocol.ImCommand;
import com.codex.imserver.protocol.ImPacket;
import com.codex.imserver.auth.TokenService;
import com.codex.imserver.group.GroupService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals(1001, ack.get("serverSeq").getAsLong());
        JsonObject received = body(receiver.sentPackets.get(0));
        assertEquals("hello", received.get("content").getAsString());
        assertEquals("13800113800", received.get("senderId").getAsString());
        assertEquals(1001, received.get("serverSeq").getAsLong());
    }

    @Test
    public void duplicateMessageIdReturnsOriginalAckWithoutDuplicateForward() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry);
        ImPacket packet = packet("""
            {
              "messageId":"same-message",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """);

        router.handleSendMessage("13800113800", packet);
        router.handleSendMessage("13800113800", packet);

        assertEquals(2, sender.sentPackets.size());
        assertEquals(1, receiver.sentPackets.size());
        assertEquals(1001, body(sender.sentPackets.get(0)).get("serverSeq").getAsLong());
        assertEquals(1001, body(sender.sentPackets.get(1)).get("serverSeq").getAsLong());
        assertEquals("same-message", body(receiver.sentPackets.get(0)).get("messageId").getAsString());
    }

    @Test
    public void duplicateImageMessageIdReturnsOriginalAckWithoutDuplicateForward() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry);
        ImPacket packet = packet("""
            {
              "messageId":"same-image-message",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "type":"IMAGE",
              "content":"[图片]",
              "image":{
                "imageUrl":"https://oss.example.com/origin.jpg",
                "thumbnailUrl":"https://oss.example.com/thumb.jpg",
                "width":900,
                "height":600,
                "mimeType":"image/jpeg",
                "sizeBytes":456789
              },
              "timestamp":1000
            }
            """);

        router.handleSendMessage("13800113800", packet);
        router.handleSendMessage("13800113800", packet);

        assertEquals(2, sender.sentPackets.size());
        assertEquals(1, receiver.sentPackets.size());
        JsonObject received = body(receiver.sentPackets.get(0));
        assertEquals("[图片]", received.get("content").getAsString());
        assertTrue(received.has("image"));
        assertEquals("https://oss.example.com/thumb.jpg", received.getAsJsonObject("image").get("thumbnailUrl").getAsString());
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
    public void unackedDeliveredMessageIsRedeliveredAfterReceiverReauth() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        CapturingClient sender = new CapturingClient();
        CapturingClient firstReceiver = new CapturingClient();
        registry.register("13900113900", sender);
        registry.register("13800113800", firstReceiver);
        MessageRouter router = new MessageRouter(registry, tokenService);

        router.handleSendMessage("13900113900", packet("""
            {
              "messageId":"needs-redelivery",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":2,
              "content":"Hello again",
              "timestamp":1000
            }
            """));

        CapturingClient secondReceiver = new CapturingClient();
        router.handleAuth(tokenService.issue("13800113800").token(), secondReceiver);

        assertEquals(ImCommand.AUTH_ACK.value(), secondReceiver.sentPackets.get(0).cmd());
        assertEquals(ImCommand.RECEIVE_MESSAGE.value(), secondReceiver.sentPackets.get(1).cmd());
        assertEquals("needs-redelivery", body(secondReceiver.sentPackets.get(1)).get("messageId").getAsString());
    }

    @Test
    public void deliveryAckStopsFurtherRedelivery() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        CapturingClient sender = new CapturingClient();
        CapturingClient firstReceiver = new CapturingClient();
        registry.register("13900113900", sender);
        registry.register("13800113800", firstReceiver);
        MessageRouter router = new MessageRouter(registry, tokenService);

        router.handleSendMessage("13900113900", packet("""
            {
              "messageId":"acked-no-redelivery",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":3,
              "content":"Delivered",
              "timestamp":1000
            }
            """));

        router.handleDeliveryAck("13800113800", deliveryAck("""
            {
              "messageId":"acked-no-redelivery",
              "conversationId":"single:13800113800:13900113900",
              "serverSeq":1001,
              "receiverId":"13800113800"
            }
            """));

        CapturingClient secondReceiver = new CapturingClient();
        router.handleAuth(tokenService.issue("13800113800").token(), secondReceiver);

        assertEquals(List.of(ImCommand.AUTH_ACK.value()), secondReceiver.sentPackets.stream().map(ImPacket::cmd).toList());
    }

    @Test
    public void readAckForwardsToSingleChatPeerWhenReaderMatchesSocketUser() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient reader = new CapturingClient();
        CapturingClient peer = new CapturingClient();
        registry.register("13900113900", reader);
        registry.register("13800113800", peer);
        MessageRouter router = new MessageRouter(registry);

        router.handleReadAck("13900113900", readAck("""
            {
              "conversationId":"single:13800113800:13900113900",
              "readerId":"13900113900",
              "peerId":"13800113800",
              "readUpToServerSeq":1001,
              "readAt":2000
            }
            """));

        assertEquals(1, peer.sentPackets.size());
        assertEquals(ImCommand.READ_ACK.value(), peer.sentPackets.get(0).cmd());
        JsonObject forwarded = body(peer.sentPackets.get(0));
        assertEquals("13900113900", forwarded.get("readerId").getAsString());
        assertEquals(1001L, forwarded.get("readUpToServerSeq").getAsLong());
    }

    @Test
    public void readAckWithMismatchedReaderIsIgnored() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient peer = new CapturingClient();
        registry.register("13800113800", peer);
        MessageRouter router = new MessageRouter(registry);

        router.handleReadAck("13900113900", readAck("""
            {
              "conversationId":"single:13800113800:13900113900",
              "readerId":"someone-else",
              "peerId":"13800113800",
              "readUpToServerSeq":1001,
              "readAt":2000
            }
            """));

        assertTrue(peer.sentPackets.isEmpty());
    }

    @Test
    public void recallByOriginalSenderWithinTwoMinutesSendsAckAndNotify() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), () -> 130_000L);
        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"recall-ok",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """));
        sender.sentPackets.clear();
        receiver.sentPackets.clear();

        router.handleRecallMessage("13800113800", recallMessage("""
            {
              "messageId":"recall-ok",
              "conversationId":"single:13800113800:13900113900",
              "requesterId":"13800113800",
              "requestAt":130000
            }
            """));

        assertEquals(ImCommand.RECALL_ACK.value(), sender.sentPackets.get(0).cmd());
        assertEquals(ImCommand.RECALL_NOTIFY.value(), receiver.sentPackets.get(0).cmd());
        assertTrue(body(sender.sentPackets.get(0)).get("success").getAsBoolean());
        assertEquals("recall-ok", body(receiver.sentPackets.get(0)).get("messageId").getAsString());
    }

    @Test
    public void nonSenderCannotRecallMessage() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), () -> 2_000L);
        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"recall-denied",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """));
        receiver.sentPackets.clear();

        router.handleRecallMessage("13900113900", recallMessage("""
            {
              "messageId":"recall-denied",
              "conversationId":"single:13800113800:13900113900",
              "requesterId":"13900113900",
              "requestAt":2000
            }
            """));

        assertEquals(ImCommand.RECALL_ACK.value(), receiver.sentPackets.get(0).cmd());
        assertFalse(body(receiver.sentPackets.get(0)).get("success").getAsBoolean());
        assertEquals("NOT_SENDER", body(receiver.sentPackets.get(0)).get("reason").getAsString());
        assertEquals(1, sender.sentPackets.size());
    }

    @Test
    public void recallAfterTwoMinutesFails() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MutableClock clock = new MutableClock(1_000L);
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), clock);
        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"recall-expired",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """));
        sender.sentPackets.clear();
        receiver.sentPackets.clear();
        clock.now = 121_001L;

        router.handleRecallMessage("13800113800", recallMessage("""
            {
              "messageId":"recall-expired",
              "conversationId":"single:13800113800:13900113900",
              "requesterId":"13800113800",
              "requestAt":130001
            }
            """));

        assertFalse(body(sender.sentPackets.get(0)).get("success").getAsBoolean());
        assertEquals("EXPIRED", body(sender.sentPackets.get(0)).get("reason").getAsString());
        assertTrue(receiver.sentPackets.isEmpty());
    }

    @Test
    public void repeatedRecallIsIdempotent() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), () -> 2_000L);
        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"recall-repeat",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """));
        ImPacket recall = recallMessage("""
            {
              "messageId":"recall-repeat",
              "conversationId":"single:13800113800:13900113900",
              "requesterId":"13800113800",
              "requestAt":2000
            }
            """);
        sender.sentPackets.clear();
        receiver.sentPackets.clear();

        router.handleRecallMessage("13800113800", recall);
        router.handleRecallMessage("13800113800", recall);

        assertEquals(2, sender.sentPackets.size());
        assertTrue(body(sender.sentPackets.get(0)).get("success").getAsBoolean());
        assertTrue(body(sender.sentPackets.get(1)).get("success").getAsBoolean());
        assertEquals(1, receiver.sentPackets.size());
    }

    @Test
    public void duplicateDeliveryAckIsIdempotent() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13900113900", sender);
        registry.register("13800113800", receiver);
        MessageRouter router = new MessageRouter(registry);

        router.handleSendMessage("13900113900", packet("""
            {
              "messageId":"delivery-dup-ack",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":4,
              "content":"Hello",
              "timestamp":1000
            }
            """));

        ImPacket ack = deliveryAck("""
            {
              "messageId":"delivery-dup-ack",
              "conversationId":"single:13800113800:13900113900",
              "serverSeq":1001,
              "receiverId":"13800113800"
            }
            """);
        router.handleDeliveryAck("13800113800", ack);
        router.handleDeliveryAck("13800113800", ack);
    }

    @Test
    public void undeliveredMessagesAreIndexedByReceiverUserId() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient senderA = new CapturingClient();
        CapturingClient senderB = new CapturingClient();
        registry.register("13900113900", senderA);
        registry.register("13700113700", senderB);
        MessageRouter router = new MessageRouter(registry);

        router.handleSendMessage("13900113900", packet("""
            {
              "messageId":"for-138-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":1,
              "content":"one",
              "timestamp":1000
            }
            """));
        router.handleSendMessage("13700113700", packet("""
            {
              "messageId":"for-138-2",
              "conversationId":"single:13700113700:13800113800",
              "senderId":"13700113700",
              "receiverId":"13800113800",
              "clientSeq":1,
              "content":"two",
              "timestamp":1001
            }
            """));
        router.handleSendMessage("13900113900", packet("""
            {
              "messageId":"for-136-1",
              "conversationId":"single:13600113600:13900113900",
              "senderId":"13900113900",
              "receiverId":"13600113600",
              "clientSeq":2,
              "content":"three",
              "timestamp":1002
            }
            """));

        assertEquals(List.of("for-138-1", "for-138-2"), router.undeliveredMessageIdsForReceiver("13800113800"));
        assertEquals(List.of("for-136-1"), router.undeliveredMessageIdsForReceiver("13600113600"));

        router.handleDeliveryAck("13800113800", deliveryAck("""
            {
              "messageId":"for-138-1",
              "conversationId":"single:13800113800:13900113900",
              "serverSeq":1001,
              "receiverId":"13800113800"
            }
            """));

        assertEquals(List.of("for-138-2"), router.undeliveredMessageIdsForReceiver("13800113800"));
        assertEquals(List.of("for-136-1"), router.undeliveredMessageIdsForReceiver("13600113600"));
    }

    @Test
    public void serverSeqIsIndependentPerConversationAndIncreasesWithinConversation() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiverB = new CapturingClient();
        CapturingClient receiverC = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiverB);
        registry.register("13700113700", receiverC);
        MessageRouter router = new MessageRouter(registry);

        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"ab-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"to B first",
              "timestamp":1000
            }
            """));
        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"ac-1",
              "conversationId":"single:13700113700:13800113800",
              "senderId":"13800113800",
              "receiverId":"13700113700",
              "clientSeq":2,
              "content":"to C first",
              "timestamp":1001
            }
            """));
        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"ab-2",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":3,
              "content":"to B second",
              "timestamp":1002
            }
            """));

        assertEquals(1001, body(receiverB.sentPackets.get(0)).get("serverSeq").getAsLong());
        assertEquals(1001, body(receiverC.sentPackets.get(0)).get("serverSeq").getAsLong());
        assertEquals(1002, body(receiverB.sentPackets.get(1)).get("serverSeq").getAsLong());
    }

    @Test
    public void serverSeqStoreKeepsConversationSequenceAcrossRouterRestart() {
        MessageRouter.ServerSeqStore seqStore = new MessageRouter.InMemoryServerSeqStore();

        ClientSessionRegistry firstRegistry = new ClientSessionRegistry();
        CapturingClient firstSender = new CapturingClient();
        CapturingClient firstReceiver = new CapturingClient();
        firstRegistry.register("13800113800", firstSender);
        firstRegistry.register("13900113900", firstReceiver);
        MessageRouter firstRouter = new MessageRouter(firstRegistry, TokenService.defaultService(), seqStore);
        firstRouter.handleSendMessage("13800113800", packet("""
            {
              "messageId":"before-restart",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"before",
              "timestamp":1000
            }
            """));

        ClientSessionRegistry secondRegistry = new ClientSessionRegistry();
        CapturingClient secondSender = new CapturingClient();
        CapturingClient secondReceiver = new CapturingClient();
        secondRegistry.register("13800113800", secondSender);
        secondRegistry.register("13900113900", secondReceiver);
        MessageRouter secondRouter = new MessageRouter(secondRegistry, TokenService.defaultService(), seqStore);
        secondRouter.handleSendMessage("13800113800", packet("""
            {
              "messageId":"after-restart",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":2,
              "content":"after",
              "timestamp":2000
            }
            """));

        assertEquals(1001, body(firstReceiver.sentPackets.get(0)).get("serverSeq").getAsLong());
        assertEquals(1002, body(secondReceiver.sentPackets.get(0)).get("serverSeq").getAsLong());
    }

    @Test
    public void sqliteServerSeqStorePersistsAndStartsAboveLegacySmallSequences() throws Exception {
        Path directory = Files.createTempDirectory("im-seq-store-test");
        Path database = directory.resolve("sequences.sqlite");

        MessageRouter.SQLiteServerSeqStore firstStore = new MessageRouter.SQLiteServerSeqStore(database, () -> 50_000L);
        assertEquals(50_001L, firstStore.next("single:13800113800:13900113900"));

        MessageRouter.SQLiteServerSeqStore secondStore = new MessageRouter.SQLiteServerSeqStore(database, () -> 1L);
        assertEquals(50_002L, secondStore.next("single:13800113800:13900113900"));

        MessageRouter.SQLiteServerSeqStore thirdStore = new MessageRouter.SQLiteServerSeqStore(database, () -> 50_000L);
        assertEquals(50_001L, thirdStore.next("single:13700113700:13800113800"));
    }

    @Test
    public void sqliteAcceptedMessageStorePersistsAcceptedAndUndeliveredState() throws Exception {
        Path directory = Files.createTempDirectory("im-message-store-test");
        Path database = directory.resolve("messages.sqlite");
        MessageRouter.SQLiteAcceptedMessageStore firstStore = new MessageRouter.SQLiteAcceptedMessageStore(database);

        JsonObject ack = JsonParser.parseString("""
            {
              "messageId":"persisted-1",
              "conversationId":"single:13800113800:13900113900",
              "clientSeq":7,
              "serverSeq":1005,
              "serverTime":2000
            }
            """).getAsJsonObject();
        JsonObject message = JsonParser.parseString("""
            {
              "messageId":"persisted-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":7,
              "serverSeq":1005,
              "serverTime":2000,
              "content":"persist me",
              "timestamp":1000
            }
            """).getAsJsonObject();
        MessageRouter.AcceptedMessage accepted = new MessageRouter.AcceptedMessage(
                ack,
                message,
                "13900113900",
                1005,
                false
        );

        assertTrue(firstStore.saveIfAbsent("persisted-1", accepted).isEmpty());

        MessageRouter.SQLiteAcceptedMessageStore secondStore = new MessageRouter.SQLiteAcceptedMessageStore(database);
        List<MessageRouter.StoredAcceptedMessage> restored = secondStore.loadAll();

        assertEquals(1, restored.size());
        assertEquals("persisted-1", restored.get(0).messageId());
        assertEquals("13900113900", restored.get(0).accepted().receiverUserId());
        assertEquals(1005L, restored.get(0).accepted().serverSeq());
        assertFalse(restored.get(0).accepted().delivered());
    }

    @Test
    public void sqliteAcceptedMessageStoreMigratesLegacyDatabaseWithRecallColumns() throws Exception {
        Path directory = Files.createTempDirectory("im-message-store-migration-test");
        Path database = directory.resolve("messages.sqlite");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().toUri());
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE accepted_messages (
                  message_id TEXT PRIMARY KEY,
                  conversation_id TEXT NOT NULL,
                  sender_id TEXT NOT NULL,
                  receiver_id TEXT NOT NULL,
                  client_seq INTEGER NOT NULL,
                  server_seq INTEGER NOT NULL,
                  content TEXT NOT NULL,
                  timestamp INTEGER NOT NULL,
                  server_time INTEGER NOT NULL,
                  delivered INTEGER NOT NULL DEFAULT 0,
                  ack_json TEXT NOT NULL,
                  message_json TEXT NOT NULL,
                  UNIQUE(conversation_id, server_seq)
                )
                """);
            statement.executeUpdate("""
                INSERT INTO accepted_messages(
                  message_id, conversation_id, sender_id, receiver_id, client_seq, server_seq,
                  content, timestamp, server_time, delivered, ack_json, message_json
                ) VALUES(
                  'legacy-1', 'single:13800113800:13900113900', '13800113800', '13900113900',
                  1, 1001, 'legacy', 1000, 2000, 0,
                  '{"messageId":"legacy-1","conversationId":"single:13800113800:13900113900","clientSeq":1,"serverSeq":1001,"serverTime":2000}',
                  '{"messageId":"legacy-1","conversationId":"single:13800113800:13900113900","senderId":"13800113800","receiverId":"13900113900","clientSeq":1,"serverSeq":1001,"serverTime":2000,"content":"legacy","timestamp":1000}'
                )
                """);
        }

        MessageRouter.SQLiteAcceptedMessageStore store = new MessageRouter.SQLiteAcceptedMessageStore(database);
        List<MessageRouter.StoredAcceptedMessage> restored = store.loadAll();

        assertEquals(1, restored.size());
        assertEquals("legacy-1", restored.get(0).messageId());
        assertFalse(restored.get(0).accepted().recalled());
    }

    @Test
    public void duplicateMessageIdRemainsIdempotentAcrossRouterRestart() throws Exception {
        Path directory = Files.createTempDirectory("im-router-restart-idempotency");
        Path sequenceDatabase = directory.resolve("sequences.sqlite");
        Path messageDatabase = directory.resolve("messages.sqlite");
        MessageRouter.ServerSeqStore seqStore = new MessageRouter.SQLiteServerSeqStore(sequenceDatabase, () -> 1L);
        MessageRouter.AcceptedMessageStore acceptedStore = new MessageRouter.SQLiteAcceptedMessageStore(messageDatabase);

        ClientSessionRegistry firstRegistry = new ClientSessionRegistry();
        CapturingClient firstSender = new CapturingClient();
        firstRegistry.register("13800113800", firstSender);
        MessageRouter firstRouter = new MessageRouter(firstRegistry, TokenService.defaultService(), seqStore, acceptedStore);
        ImPacket packet = packet("""
            {
              "messageId":"restart-dup-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13800113800",
              "receiverId":"13900113900",
              "clientSeq":1,
              "content":"hello",
              "timestamp":1000
            }
            """);
        firstRouter.handleSendMessage("13800113800", packet);

        ClientSessionRegistry secondRegistry = new ClientSessionRegistry();
        CapturingClient secondSender = new CapturingClient();
        CapturingClient secondReceiver = new CapturingClient();
        secondRegistry.register("13800113800", secondSender);
        secondRegistry.register("13900113900", secondReceiver);
        MessageRouter secondRouter = new MessageRouter(secondRegistry, TokenService.defaultService(), seqStore, acceptedStore);
        secondRouter.handleSendMessage("13800113800", packet);

        assertEquals(1, secondSender.sentPackets.size());
        assertEquals(0, secondReceiver.sentPackets.size());
        assertEquals(1001L, body(secondSender.sentPackets.get(0)).get("serverSeq").getAsLong());
    }

    @Test
    public void undeliveredMessagesAreRestoredAndRedeliveredAfterRouterRestart() throws Exception {
        Path directory = Files.createTempDirectory("im-router-restart-redelivery");
        Path sequenceDatabase = directory.resolve("sequences.sqlite");
        Path messageDatabase = directory.resolve("messages.sqlite");
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        MessageRouter.ServerSeqStore seqStore = new MessageRouter.SQLiteServerSeqStore(sequenceDatabase, () -> 1L);
        MessageRouter.AcceptedMessageStore acceptedStore = new MessageRouter.SQLiteAcceptedMessageStore(messageDatabase);

        ClientSessionRegistry firstRegistry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        firstRegistry.register("13900113900", sender);
        MessageRouter firstRouter = new MessageRouter(firstRegistry, tokenService, seqStore, acceptedStore);
        firstRouter.handleSendMessage("13900113900", packet("""
            {
              "messageId":"restart-undelivered-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":2,
              "content":"deliver after restart",
              "timestamp":1000
            }
            """));

        ClientSessionRegistry secondRegistry = new ClientSessionRegistry();
        CapturingClient receiver = new CapturingClient();
        MessageRouter secondRouter = new MessageRouter(secondRegistry, tokenService, seqStore, acceptedStore);
        secondRouter.handleAuth(tokenService.issue("13800113800").token(), receiver);

        assertEquals(List.of(ImCommand.AUTH_ACK.value(), ImCommand.RECEIVE_MESSAGE.value()), receiver.sentPackets.stream().map(ImPacket::cmd).toList());
        assertEquals("restart-undelivered-1", body(receiver.sentPackets.get(1)).get("messageId").getAsString());
    }

    @Test
    public void deliveryAckedMessageIsNotRedeliveredAfterRouterRestart() throws Exception {
        Path directory = Files.createTempDirectory("im-router-restart-delivered");
        Path sequenceDatabase = directory.resolve("sequences.sqlite");
        Path messageDatabase = directory.resolve("messages.sqlite");
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        MessageRouter.ServerSeqStore seqStore = new MessageRouter.SQLiteServerSeqStore(sequenceDatabase, () -> 1L);
        MessageRouter.AcceptedMessageStore acceptedStore = new MessageRouter.SQLiteAcceptedMessageStore(messageDatabase);

        ClientSessionRegistry firstRegistry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        firstRegistry.register("13900113900", sender);
        MessageRouter firstRouter = new MessageRouter(firstRegistry, tokenService, seqStore, acceptedStore);
        firstRouter.handleSendMessage("13900113900", packet("""
            {
              "messageId":"restart-acked-1",
              "conversationId":"single:13800113800:13900113900",
              "senderId":"13900113900",
              "receiverId":"13800113800",
              "clientSeq":3,
              "content":"already delivered",
              "timestamp":1000
            }
            """));
        firstRouter.handleDeliveryAck("13800113800", deliveryAck("""
            {
              "messageId":"restart-acked-1",
              "conversationId":"single:13800113800:13900113900",
              "serverSeq":1001,
              "receiverId":"13800113800"
            }
            """));

        ClientSessionRegistry secondRegistry = new ClientSessionRegistry();
        CapturingClient receiver = new CapturingClient();
        MessageRouter secondRouter = new MessageRouter(secondRegistry, tokenService, seqStore, acceptedStore);
        secondRouter.handleAuth(tokenService.issue("13800113800").token(), receiver);

        assertEquals(List.of(ImCommand.AUTH_ACK.value()), receiver.sentPackets.stream().map(ImPacket::cmd).toList());
    }

    @Test
    public void sendMessageLogsOrderingFields() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient receiver = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", receiver);
        MessageRouter router = new MessageRouter(registry);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output));
            router.handleSendMessage("13800113800", packet("""
                {
                  "messageId":"logged-1",
                  "conversationId":"single:13800113800:13900113900",
                  "senderId":"13800113800",
                  "receiverId":"13900113900",
                  "clientSeq":3,
                  "content":"hello",
                  "timestamp":1000
                }
                """));
        } finally {
            System.setOut(originalOut);
        }

        String log = output.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("[IM] SEND_MESSAGE sender=13800113800 receiver=13900113900 conversationId=single:13800113800:13900113900 messageId=logged-1 clientSeq=3 serverSeq=1001 content=hello"));
        assertTrue(log.contains("[IM] MESSAGE_ACK sent sender=13800113800 messageId=logged-1 clientSeq=3 serverSeq=1001"));
        assertTrue(log.contains("[IM] RECEIVE_MESSAGE forwarded receiver=13900113900 messageId=logged-1 serverSeq=1001"));
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
    public void unauthenticatedHeartbeatDoesNotSendHeartbeatAck() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        MessageRouter router = new MessageRouter(registry);
        CapturingClient client = new CapturingClient();

        router.handleHeartbeat(client);

        assertTrue(client.sentPackets.isEmpty());
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

    @Test
    public void groupMessageFansOutToOnlineMembersExceptSender() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        CapturingClient memberC = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", memberB);
        registry.register("13700113700", memberC);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(3)", List.of("13900113900", "13700113700"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);

        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"group-m1",
              "conversationId":"group:g_1001",
              "conversationType":"GROUP",
              "groupId":"g_1001",
              "senderId":"13800113800",
              "receiverId":"g_1001",
              "clientSeq":1,
              "type":"TEXT",
              "content":"@B hello",
              "mentionedUserIds":["13900113900"],
              "timestamp":1000
            }
            """));

        assertEquals(ImCommand.MESSAGE_ACK.value(), sender.sentPackets.get(0).cmd());
        assertEquals(1, sender.sentPackets.size());
        assertEquals(ImCommand.RECEIVE_MESSAGE.value(), memberB.sentPackets.get(0).cmd());
        assertEquals(ImCommand.RECEIVE_MESSAGE.value(), memberC.sentPackets.get(0).cmd());
        assertEquals("13900113900", body(memberB.sentPackets.get(0)).get("receiverId").getAsString());
        assertEquals("13700113700", body(memberC.sentPackets.get(0)).get("receiverId").getAsString());
        assertEquals("GROUP", body(memberB.sentPackets.get(0)).get("conversationType").getAsString());
        assertEquals(1001L, body(memberB.sentPackets.get(0)).get("serverSeq").getAsLong());
        assertEquals(1001L, body(memberC.sentPackets.get(0)).get("serverSeq").getAsLong());
    }

    @Test
    public void groupImageMessageFansOutWithImagePayload() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", memberB);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(2)", List.of("13900113900"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);

        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"group-image-1",
              "conversationId":"group:g_1001",
              "conversationType":"GROUP",
              "groupId":"g_1001",
              "senderId":"13800113800",
              "receiverId":"g_1001",
              "clientSeq":1,
              "type":"IMAGE",
              "content":"[图片]",
              "image":{
                "imageUrl":"https://oss.example.com/group-origin.jpg",
                "thumbnailUrl":"https://oss.example.com/group-thumb.jpg",
                "width":1440,
                "height":960,
                "mimeType":"image/jpeg",
                "sizeBytes":345678
              },
              "mentionedUserIds":[],
              "timestamp":1000
            }
            """));

        JsonObject forwarded = body(memberB.sentPackets.get(0));
        assertEquals("IMAGE", forwarded.get("type").getAsString());
        assertEquals("13900113900", forwarded.get("receiverId").getAsString());
        assertEquals("https://oss.example.com/group-origin.jpg", forwarded.getAsJsonObject("image").get("imageUrl").getAsString());
        assertEquals("https://oss.example.com/group-thumb.jpg", forwarded.getAsJsonObject("image").get("thumbnailUrl").getAsString());
    }

    @Test
    public void groupSendLogsGroupFields() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", memberB);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(2)", List.of("13900113900"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output));
            router.handleSendMessage("13800113800", packet("""
                {
                  "messageId":"group-log-1",
                  "conversationId":"group:g_1001",
                  "conversationType":"GROUP",
                  "groupId":"g_1001",
                  "senderId":"13800113800",
                  "receiverId":"g_1001",
                  "clientSeq":1,
                  "content":"hello group",
                  "timestamp":1000
                }
                """));
        } finally {
            System.setOut(originalOut);
        }

        String log = output.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("[IM] GROUP_SEND sender=13800113800 groupId=g_1001 conversationId=group:g_1001 messageId=group-log-1 clientSeq=1 serverSeq=1001 recipients=1 content=hello group"));
    }

    @Test
    public void groupMessageQueuesOfflineMemberUntilAuth() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        TokenService tokenService = new TokenService("test-secret", () -> 1_000L, 60_000L);
        CapturingClient sender = new CapturingClient();
        CapturingClient onlineMember = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", onlineMember);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(3)", List.of("13900113900", "13700113700"));
        MessageRouter router = new MessageRouter(registry, tokenService, new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);

        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"group-offline-1",
              "conversationId":"group:g_1001",
              "conversationType":"GROUP",
              "groupId":"g_1001",
              "senderId":"13800113800",
              "receiverId":"g_1001",
              "clientSeq":1,
              "content":"queued",
              "timestamp":1000
            }
            """));
        CapturingClient offlineMember = new CapturingClient();
        router.handleAuth(tokenService.issue("13700113700").token(), offlineMember);

        assertEquals(List.of(ImCommand.AUTH_ACK.value(), ImCommand.RECEIVE_MESSAGE.value()), offlineMember.sentPackets.stream().map(ImPacket::cmd).toList());
        assertEquals("group-offline-1", body(offlineMember.sentPackets.get(1)).get("messageId").getAsString());
        assertEquals("13700113700", body(offlineMember.sentPackets.get(1)).get("receiverId").getAsString());
    }

    @Test
    public void groupDeliveryAckClearsOnlyThatMemberPendingDelivery() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        CapturingClient memberC = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", memberB);
        registry.register("13700113700", memberC);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(3)", List.of("13900113900", "13700113700"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);

        router.handleSendMessage("13800113800", packet("""
            {
              "messageId":"group-delivery-ack-1",
              "conversationId":"group:g_1001",
              "conversationType":"GROUP",
              "groupId":"g_1001",
              "senderId":"13800113800",
              "receiverId":"g_1001",
              "clientSeq":1,
              "content":"ack me",
              "timestamp":1000
            }
            """));
        router.handleDeliveryAck("13900113900", deliveryAck("""
            {
              "messageId":"group-delivery-ack-1",
              "conversationId":"group:g_1001",
              "serverSeq":1001,
              "receiverId":"13900113900"
            }
            """));

        assertTrue(router.undeliveredMessageIdsForReceiver("13900113900").isEmpty());
        assertEquals(List.of("group-delivery-ack-1"), router.undeliveredMessageIdsForReceiver("13700113700"));
    }

    @Test
    public void groupReceiveAndDeliveryAckLogsIncludeGroupAndReceiver() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", memberB);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(2)", List.of("13900113900"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setOut(new PrintStream(output));
            router.handleSendMessage("13800113800", packet("""
                {
                  "messageId":"group-receive-log-1",
                  "conversationId":"group:g_1001",
                  "conversationType":"GROUP",
                  "groupId":"g_1001",
                  "senderId":"13800113800",
                  "receiverId":"g_1001",
                  "clientSeq":1,
                  "content":"log me",
                  "timestamp":1000
                }
                """));
            router.handleDeliveryAck("13900113900", deliveryAck("""
                {
                  "messageId":"group-receive-log-1",
                  "conversationId":"group:g_1001",
                  "serverSeq":1001,
                  "receiverId":"13900113900"
                }
                """));
        } finally {
            System.setOut(originalOut);
        }

        String log = output.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("[IM] GROUP_RECEIVE forwarded groupId=g_1001 receiver=13900113900 messageId=group-receive-log-1 serverSeq=1001"));
        assertTrue(log.contains("[IM] GROUP_DELIVERY_ACK received groupId=g_1001 receiver=13900113900 messageId=group-receive-log-1 serverSeq=1001"));
    }

    @Test
    public void groupDuplicateMessageIdReturnsOriginalAckWithoutDuplicateFanout() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        registry.register("13800113800", sender);
        registry.register("13900113900", memberB);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(2)", List.of("13900113900"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);
        ImPacket packet = packet("""
            {
              "messageId":"group-dup-1",
              "conversationId":"group:g_1001",
              "conversationType":"GROUP",
              "groupId":"g_1001",
              "senderId":"13800113800",
              "receiverId":"g_1001",
              "clientSeq":1,
              "content":"once",
              "timestamp":1000
            }
            """);

        router.handleSendMessage("13800113800", packet);
        router.handleSendMessage("13800113800", packet);

        assertEquals(2, sender.sentPackets.size());
        assertEquals(1, memberB.sentPackets.size());
        assertEquals(1001L, body(sender.sentPackets.get(0)).get("serverSeq").getAsLong());
        assertEquals(1001L, body(sender.sentPackets.get(1)).get("serverSeq").getAsLong());
    }

    @Test
    public void nonMemberCannotSendGroupMessage() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient intruder = new CapturingClient();
        CapturingClient member = new CapturingClient();
        registry.register("13600113600", intruder);
        registry.register("13900113900", member);
        GroupService groupService = new GroupService(() -> 1_000L);
        groupService.createGroup("13800113800", "群聊(2)", List.of("13900113900"));
        MessageRouter router = new MessageRouter(registry, TokenService.defaultService(), new MessageRouter.InMemoryServerSeqStore(), new MessageRouter.InMemoryAcceptedMessageStore(), groupService, () -> 2_000L);

        router.handleSendMessage("13600113600", packet("""
            {
              "messageId":"group-denied-1",
              "conversationId":"group:g_1001",
              "conversationType":"GROUP",
              "groupId":"g_1001",
              "senderId":"13600113600",
              "receiverId":"g_1001",
              "clientSeq":1,
              "content":"nope",
              "timestamp":1000
            }
            """));

        assertTrue(intruder.sentPackets.isEmpty());
        assertTrue(member.sentPackets.isEmpty());
    }

    private static ImPacket packet(String json) {
        return new ImPacket(ImCommand.SEND_MESSAGE.value(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static ImPacket deliveryAck(String json) {
        return new ImPacket(ImCommand.DELIVERY_ACK.value(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static ImPacket readAck(String json) {
        return new ImPacket(ImCommand.READ_ACK.value(), json.getBytes(StandardCharsets.UTF_8));
    }

    private static ImPacket recallMessage(String json) {
        return new ImPacket(ImCommand.RECALL_MESSAGE.value(), json.getBytes(StandardCharsets.UTF_8));
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

    private static final class MutableClock implements LongSupplier {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long getAsLong() {
            return now;
        }
    }
}
