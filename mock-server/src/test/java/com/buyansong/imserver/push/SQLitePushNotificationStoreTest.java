package com.buyansong.imserver.push;

import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SQLitePushNotificationStoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SQLitePushNotificationStore newStore() throws Exception {
        Path db = folder.newFile("mock-im-push.sqlite").toPath();
        return new SQLitePushNotificationStore(db);
    }

    @Test
    public void enqueueIfAbsent_persistsPreviewAndIgnoresDuplicateMessageId() throws Exception {
        SQLitePushNotificationStore store = newStore();

        assertTrue(store.enqueueIfAbsent("u_receiver", textMessage("m_1", "hello"), 1_000L));
        assertFalse(store.enqueueIfAbsent("u_receiver", textMessage("m_1", "hello again"), 2_000L));

        List<PushNotificationStore.PushNotificationRecord> pending = store.pending("u_receiver", 0L, 50);
        assertEquals(1, pending.size());
        assertEquals("m_1", pending.get(0).messageId());
        assertEquals("hello", pending.get(0).preview());
        assertEquals("TEXT", pending.get(0).messageType());
    }

    @Test
    public void pending_filtersSinceAndAckedRows() throws Exception {
        SQLitePushNotificationStore store = newStore();
        store.enqueueIfAbsent("u_receiver", textMessage("m_1", "one"), 1_000L);
        store.enqueueIfAbsent("u_receiver", textMessage("m_2", "two"), 2_000L);
        long firstPushId = store.pending("u_receiver", 0L, 50).get(0).pushId();

        store.ack("u_receiver", List.of(firstPushId), 3_000L);

        List<PushNotificationStore.PushNotificationRecord> pending = store.pending("u_receiver", firstPushId, 50);
        assertEquals(1, pending.size());
        assertEquals("m_2", pending.get(0).messageId());
    }

    @Test
    public void imageMessage_usesImagePreview() throws Exception {
        SQLitePushNotificationStore store = newStore();
        JsonObject message = textMessage("m_img", "ignored");
        message.addProperty("messageType", "IMAGE");

        store.enqueueIfAbsent("u_receiver", message, 1_000L);

        assertEquals("[图片]", store.pending("u_receiver", 0L, 50).get(0).preview());
    }

    private static JsonObject textMessage(String messageId, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("senderId", "u_sender");
        message.addProperty("conversationId", "single:u_sender:u_receiver");
        message.addProperty("messageId", messageId);
        message.addProperty("messageType", "TEXT");
        message.addProperty("content", content);
        message.addProperty("serverSeq", 42L);
        message.addProperty("serverTime", 123_456L);
        return message;
    }
}
