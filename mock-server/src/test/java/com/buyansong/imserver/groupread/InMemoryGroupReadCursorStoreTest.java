package com.buyansong.imserver.groupread;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InMemoryGroupReadCursorStoreTest {

    @Test
    public void upsertIfGreater_insertsNewRow() {
        GroupReadCursorStore store = new InMemoryGroupReadCursorStore();
        boolean advanced = store.upsertIfGreater("g_1", "u_a", 100L, 1_000L);
        assertTrue(advanced);
        List<GroupReadCursor> rows = store.findByMemberOf(List.of("g_1"));
        assertEquals(1, rows.size());
        assertEquals(100L, rows.get(0).readUpToServerSeq());
        assertEquals(1_000L, rows.get(0).readAt());
    }

    @Test
    public void upsertIfGreater_ignoresSmallerOrEqual() {
        GroupReadCursorStore store = new InMemoryGroupReadCursorStore();
        store.upsertIfGreater("g_1", "u_a", 100L, 1_000L);
        assertFalse(store.upsertIfGreater("g_1", "u_a", 100L, 1_500L));
        assertFalse(store.upsertIfGreater("g_1", "u_a", 50L, 1_500L));
        assertTrue(store.upsertIfGreater("g_1", "u_a", 101L, 2_000L));
        List<GroupReadCursor> rows = store.findByMemberOf(List.of("g_1"));
        assertEquals(1, rows.size());
        assertEquals(101L, rows.get(0).readUpToServerSeq());
        assertEquals(2_000L, rows.get(0).readAt());
    }

    @Test
    public void findByMemberOf_returnsOnlyRowsForGivenGroups() {
        GroupReadCursorStore store = new InMemoryGroupReadCursorStore();
        store.upsertIfGreater("g_1", "u_a", 1L, 1L);
        store.upsertIfGreater("g_1", "u_b", 2L, 2L);
        store.upsertIfGreater("g_2", "u_a", 3L, 3L);
        List<GroupReadCursor> rows = store.findByMemberOf(List.of("g_1", "g_3"));
        Set<String> keys = rows.stream()
                .map(c -> c.groupId() + ":" + c.readerId())
                .collect(Collectors.toSet());
        assertEquals(Set.of("g_1:u_a", "g_1:u_b"), keys);
    }
}
