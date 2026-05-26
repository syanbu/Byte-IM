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
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals(1001, ack.get("serverSeq").getAsLong());
        JsonObject received = body(receiver.sentPackets.get(0));
        assertEquals("hello", received.get("content").getAsString());
        assertEquals("13800113800", received.get("senderId").getAsString());
        assertEquals(1001, received.get("serverSeq").getAsLong());
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
