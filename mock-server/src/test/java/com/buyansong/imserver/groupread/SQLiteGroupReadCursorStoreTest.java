package com.buyansong.imserver.groupread;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SQLiteGroupReadCursorStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SQLiteGroupReadCursorStore newStore() throws Exception {
        Path db = folder.newFile("mock-im-messages.sqlite").toPath();
        return new SQLiteGroupReadCursorStore(db);
    }

    @Test
    public void upsertIfGreater_persistsAndIgnoresStale() throws Exception {
        SQLiteGroupReadCursorStore store = newStore();
        assertTrue(store.upsertIfGreater("g_1", "u_a", 100L, 1_000L));
        assertFalse(store.upsertIfGreater("g_1", "u_a", 100L, 1_500L));
        assertFalse(store.upsertIfGreater("g_1", "u_a", 50L, 2_000L));
        assertTrue(store.upsertIfGreater("g_1", "u_a", 101L, 3_000L));
        assertTrue(store.upsertIfGreater("g_1", "u_b", 10L, 4_000L));

        List<GroupReadCursor> rows = store.findByMemberOf(List.of("g_1"));
        assertEquals(2, rows.size());
        GroupReadCursor a = rows.stream().filter(c -> c.readerId().equals("u_a")).findFirst().orElseThrow();
        assertEquals(101L, a.readUpToServerSeq());
        assertEquals(3_000L, a.readAt());
    }

    @Test
    public void findByMemberOf_filtersByGroup() throws Exception {
        SQLiteGroupReadCursorStore store = newStore();
        store.upsertIfGreater("g_1", "u_a", 1L, 1L);
        store.upsertIfGreater("g_2", "u_a", 2L, 2L);
        Set<String> keys = store.findByMemberOf(List.of("g_1")).stream()
                .map(c -> c.groupId() + ":" + c.readerId())
                .collect(Collectors.toSet());
        assertEquals(Set.of("g_1:u_a"), keys);
    }

    @Test
    public void reopen_seesExistingRows() throws Exception {
        Path db = folder.newFile("mock-im-messages.sqlite").toPath();
        SQLiteGroupReadCursorStore writer = new SQLiteGroupReadCursorStore(db);
        writer.upsertIfGreater("g_1", "u_a", 50L, 5L);
        SQLiteGroupReadCursorStore reopened = new SQLiteGroupReadCursorStore(db);
        List<GroupReadCursor> rows = reopened.findByMemberOf(List.of("g_1"));
        assertEquals(1, rows.size());
        assertEquals(50L, rows.get(0).readUpToServerSeq());
    }
}
