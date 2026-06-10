package com.buyansong.imserver.push;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SQLitePushTokenStoreTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SQLitePushTokenStore newStore() throws Exception {
        Path db = folder.newFile("mock-im-push.sqlite").toPath();
        return new SQLitePushTokenStore(db);
    }

    @Test
    public void registerToken_upsertsByUserId() throws Exception {
        SQLitePushTokenStore store = newStore();

        store.registerToken("u_1", "token-1", "android", "device-a", 1_000L);
        store.registerToken("u_1", "token-2", "android", "device-b", 2_000L);

        Optional<PushTokenStore.PushTokenRecord> row = store.findByUserId("u_1");
        assertTrue(row.isPresent());
        assertEquals("token-2", row.get().pushToken());
        assertEquals("device-b", row.get().deviceId());
        assertEquals(2_000L, row.get().updatedAt());
    }

    @Test
    public void unregisterToken_deletesUserRow() throws Exception {
        SQLitePushTokenStore store = newStore();

        store.registerToken("u_1", "token-1", "android", "device-a", 1_000L);
        store.unregisterToken("u_1");

        assertTrue(store.findByUserId("u_1").isEmpty());
    }
}
