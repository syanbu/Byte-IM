package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.im.protocol.v2.ReadAck;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.InMemoryGroupStore;
import com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionRegistryReplayTest {

    private static final class CapturingClient implements OutboundClient {
        final java.util.List<ImEnvelope> sent = new java.util.ArrayList<>();

        @Override
        public void send(ImEnvelope envelope) {
            sent.add(envelope);
        }
    }

    @Test
    public void replayGroupReadCursorsFor_emitsAllCursorsForJoinedGroups() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String g1 = groupService.createGroup("u_a", "G1", List.of("u_b", "u_c")).groupId();
        String g2 = groupService.createGroup("u_a", "G2", List.of("u_b")).groupId();
        String g3 = groupService.createGroup("u_x", "Other", List.of("u_y")).groupId();

        InMemoryGroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        cursorStore.upsertIfGreater(g1, "u_b", 10L, 1_000L);
        cursorStore.upsertIfGreater(g2, "u_b", 99L, 2_000L);
        cursorStore.upsertIfGreater(g3, "u_y", 123L, 3_000L);

        MessageRouter router = new MessageRouter(
                registry,
                TokenService.defaultService(),
                new MessageRouter.InMemoryServerSeqStore(),
                new MessageRouter.InMemoryAcceptedMessageStore(),
                groupService,
                cursorStore,
                clock::get
        );

        CapturingClient uBReconnect = new CapturingClient();
        router.replayGroupReadCursorsFor("u_b", uBReconnect);

        long readAcks = uBReconnect.sent.stream()
                .filter(envelope -> envelope.getPayloadCase() == ImEnvelope.PayloadCase.READ_ACK)
                .count();
        assertEquals(2L, readAcks);
        for (ImEnvelope envelope : uBReconnect.sent) {
            ReadAck ack = envelope.getReadAck();
            assertEquals(ConversationType.CONVERSATION_TYPE_GROUP, ack.getConversationType());
            assertTrue(ack.getConversationId().startsWith("group:"));
        }
    }
}
