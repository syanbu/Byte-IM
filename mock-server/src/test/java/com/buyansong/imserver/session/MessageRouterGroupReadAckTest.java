package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.im.protocol.v2.ReadAck;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.InMemoryGroupStore;
import com.buyansong.imserver.groupread.GroupReadCursor;
import com.buyansong.imserver.groupread.GroupReadCursorStore;
import com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageRouterGroupReadAckTest {

    private static final class CapturingClient implements OutboundClient {
        final java.util.List<ImEnvelope> sent = new java.util.ArrayList<>();

        @Override
        public void send(ImEnvelope envelope) {
            sent.add(envelope);
        }
    }

    private ReadAck groupReadAck(String conversationId, String readerId, long readUpToServerSeq, long readAt) {
        return ReadAck.newBuilder()
                .setConversationId(conversationId)
                .setConversationType(ConversationType.CONVERSATION_TYPE_GROUP)
                .setReaderId(readerId)
                .setReadUpToServerSeq(readUpToServerSeq)
                .setReadAt(readAt)
                .build();
    }

    private MessageRouter newRouter(
            ClientSessionRegistry registry,
            GroupService groupService,
            GroupReadCursorStore cursorStore,
            AtomicLong clock
    ) {
        return new MessageRouter(
                registry,
                TokenService.defaultService(),
                new MessageRouter.InMemoryServerSeqStore(),
                new MessageRouter.InMemoryAcceptedMessageStore(),
                groupService,
                cursorStore,
                clock::get
        );
    }

    @Test
    public void groupReadAck_isBroadcastToAllOnlineGroupMembers() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient senderClient = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        CapturingClient memberC = new CapturingClient();
        registry.register("u_a", senderClient);
        registry.register("u_b", memberB);
        registry.register("u_c", memberC);

        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String groupId = groupService.createGroup("u_a", "Test", List.of("u_b", "u_c")).groupId();

        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, groupService, cursorStore, clock);

        router.handleReadAck("u_b", groupReadAck("group:" + groupId, "u_b", 100L, 1_000L));

        List<GroupReadCursor> rows = cursorStore.findByMemberOf(List.of(groupId));
        assertEquals(1, rows.size());
        assertEquals("u_b", rows.get(0).readerId());
        assertEquals(100L, rows.get(0).readUpToServerSeq());

        assertEquals(1, senderClient.sent.size());
        assertEquals(1, memberB.sent.size());
        assertEquals(1, memberC.sent.size());
        for (CapturingClient client : List.of(senderClient, memberB, memberC)) {
            assertEquals(ImEnvelope.PayloadCase.READ_ACK, client.sent.get(0).getPayloadCase());
        }
    }

    @Test
    public void groupReadAck_rejectedWhenReaderIdMismatchesSocket() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient memberB = new CapturingClient();
        registry.register("u_b", memberB);
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String groupId = groupService.createGroup("u_a", "Test", List.of("u_b")).groupId();
        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, groupService, cursorStore, clock);

        router.handleReadAck("u_b", groupReadAck("group:" + groupId, "u_a", 50L, 1_000L));

        assertTrue(memberB.sent.isEmpty());
        assertTrue(cursorStore.findByMemberOf(List.of(groupId)).isEmpty());
    }

    @Test
    public void groupReadAck_rejectedWhenSenderNotGroupMember() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient memberB = new CapturingClient();
        registry.register("u_b", memberB);
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String groupId = groupService.createGroup("u_a", "Test", List.of("u_b")).groupId();
        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, groupService, cursorStore, clock);

        router.handleReadAck("u_x", groupReadAck("group:" + groupId, "u_x", 50L, 1_000L));

        assertTrue(memberB.sent.isEmpty());
        assertTrue(cursorStore.findByMemberOf(List.of(groupId)).isEmpty());
    }

    @Test
    public void groupReadAck_staleCursorIsNotBroadcast() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        registry.register("u_a", sender);
        registry.register("u_b", memberB);
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String groupId = groupService.createGroup("u_a", "Test", List.of("u_b")).groupId();
        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, groupService, cursorStore, clock);

        router.handleReadAck("u_b", groupReadAck("group:" + groupId, "u_b", 100L, 1_000L));
        sender.sent.clear();
        memberB.sent.clear();

        router.handleReadAck("u_b", groupReadAck("group:" + groupId, "u_b", 50L, 2_000L));
        assertTrue(sender.sent.isEmpty());
        assertTrue(memberB.sent.isEmpty());

        router.handleReadAck("u_b", groupReadAck("group:" + groupId, "u_b", 100L, 2_500L));
        assertTrue(sender.sent.isEmpty());
        assertTrue(memberB.sent.isEmpty());
    }
}
