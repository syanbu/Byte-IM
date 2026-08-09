package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.ChatMessagePayload;
import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.MessageType;
import com.buyansong.im.protocol.v2.SendMessage;
import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.InMemoryGroupStore;
import com.buyansong.imserver.push.InMemoryPushNotificationStore;
import org.junit.Test;

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

        router.handleSendMessage("u_sender", singleMessage("m_1", "u_receiver"));
        assertEquals(1, pushStore.pending("u_receiver", 0L, 50).size());

        registry.register("u_receiver", envelope -> { });
        router.handleSendMessage("u_sender", singleMessage("m_2", "u_receiver"));
        assertEquals(1, pushStore.pending("u_receiver", 0L, 50).size());
    }

    @Test
    public void handleGroupSendMessage_enqueuesPerOfflineMember() {
        ClientSessionRegistry registry = new ClientSessionRegistry();
        InMemoryPushNotificationStore pushStore = new InMemoryPushNotificationStore();
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        String groupId = groupService.createGroup("u_sender", "Group", List.of("u_online", "u_offline")).groupId();
        registry.register("u_online", envelope -> { });
        MessageRouter router = router(registry, pushStore, groupService, clock);

        router.handleSendMessage("u_sender", groupMessage("m_group", groupId));

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

    private static SendMessage singleMessage(String messageId, String receiverId) {
        return SendMessage.newBuilder()
                .setMessage(ChatMessagePayload.newBuilder()
                        .setMessageId(messageId)
                        .setConversationType(ConversationType.CONVERSATION_TYPE_SINGLE)
                        .setSenderId("u_sender")
                        .setReceiverId(receiverId)
                        .setConversationId("single:u_sender:" + receiverId)
                        .setClientSeq(1L)
                        .setMessageType(MessageType.MESSAGE_TYPE_TEXT)
                        .setContent("hello"))
                .build();
    }

    private static SendMessage groupMessage(String messageId, String groupId) {
        return SendMessage.newBuilder()
                .setMessage(ChatMessagePayload.newBuilder()
                        .setMessageId(messageId)
                        .setConversationType(ConversationType.CONVERSATION_TYPE_GROUP)
                        .setSenderId("u_sender")
                        .setConversationId("group:" + groupId)
                        .setGroupId(groupId)
                        .setClientSeq(1L)
                        .setMessageType(MessageType.MESSAGE_TYPE_TEXT)
                        .setContent("hello group"))
                .build();
    }
}
