package com.buyansong.imserver.session;

import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.InMemoryGroupStore;
import com.buyansong.imserver.protocol.ImCommand;
import com.buyansong.imserver.protocol.ImPacket;
import com.buyansong.imserver.push.InMemoryPushNotificationStore;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class MessageRouterPushEnqueueTest {
    @Test
    public void handleSendMessage_enqueuesPushOnlyForOfflineReceiver() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        InMemoryPushNotificationStore pushStore = new InMemoryPushNotificationStore();
        AtomicLong clock = new AtomicLong(1_000L);
        MessageRouter router = router(registry, pushStore, new GroupService(new InMemoryGroupStore(), clock::get), clock);

        router.handleSendMessage("u_sender", packet(singleMessage("m_1", "u_receiver")));
        assertEquals(1, pushStore.pending("u_receiver", 0L, 50).size());

        registry.register("u_receiver", packet -> { });
        router.handleSendMessage("u_sender", packet(singleMessage("m_2", "u_receiver")));
        assertEquals(1, pushStore.pending("u_receiver", 0L, 50).size());
    }

    @Test
    public void handleGroupSendMessage_enqueuesPerOfflineMember() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        InMemoryPushNotificationStore pushStore = new InMemoryPushNotificationStore();
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String groupId = groupService.createGroup("u_sender", "Group", List.of("u_online", "u_offline")).groupId();
        registry.register("u_online", packet -> { });
        MessageRouter router = router(registry, pushStore, groupService, clock);

        router.handleSendMessage("u_sender", packet(groupMessage("m_group", groupId)));

        assertEquals(0, pushStore.pending("u_online", 0L, 50).size());
        assertEquals(1, pushStore.pending("u_offline", 0L, 50).size());
    }

    private static MessageRouter router(
            ClientSessionRegistry registry,
            InMemoryPushNotificationStore pushStore,
            GroupService groupService,
            AtomicLong clock
    ) {
        return new MessageRouter(
                registry,
                TokenService.defaultService(),
                new MessageRouter.InMemoryServerSeqStore(),
                new MessageRouter.InMemoryAcceptedMessageStore(),
                groupService,
                new com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore(),
                null,
                pushStore,
                clock::get
        );
    }

    private static JsonObject singleMessage(String messageId, String receiverId) {
        JsonObject message = new JsonObject();
        message.addProperty("messageId", messageId);
        message.addProperty("conversationType", "SINGLE");
        message.addProperty("senderId", "u_sender");
        message.addProperty("receiverId", receiverId);
        message.addProperty("conversationId", "single:u_sender:" + receiverId);
        message.addProperty("clientSeq", 1L);
        message.addProperty("messageType", "TEXT");
        message.addProperty("content", "hello");
        return message;
    }

    private static JsonObject groupMessage(String messageId, String groupId) {
        JsonObject message = new JsonObject();
        message.addProperty("messageId", messageId);
        message.addProperty("conversationType", "GROUP");
        message.addProperty("senderId", "u_sender");
        message.addProperty("conversationId", "group:" + groupId);
        message.addProperty("groupId", groupId);
        message.addProperty("clientSeq", 1L);
        message.addProperty("messageType", "TEXT");
        message.addProperty("content", "hello group");
        return message;
    }

    private static ImPacket packet(JsonObject body) {
        return new ImPacket(ImCommand.SEND_MESSAGE.value(), body.toString().getBytes(StandardCharsets.UTF_8));
    }
}
