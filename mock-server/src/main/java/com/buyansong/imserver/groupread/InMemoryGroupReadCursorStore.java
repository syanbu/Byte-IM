package com.buyansong.imserver.groupread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InMemoryGroupReadCursorStore implements GroupReadCursorStore {
    private final Map<String, Map<String, GroupReadCursor>> byGroup = new HashMap<>();

    @Override
    public synchronized boolean upsertIfGreater(String groupId, String readerId, long readUpToServerSeq, long readAt) {
        Map<String, GroupReadCursor> byReader = byGroup.computeIfAbsent(groupId, ignored -> new HashMap<>());
        GroupReadCursor existing = byReader.get(readerId);
        if (existing != null && existing.readUpToServerSeq() >= readUpToServerSeq) {
            return false;
        }
        byReader.put(readerId, new GroupReadCursor(groupId, readerId, readUpToServerSeq, readAt));
        return true;
    }

    @Override
    public synchronized List<GroupReadCursor> findByMemberOf(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new HashSet<>(groupIds);
        List<GroupReadCursor> out = new ArrayList<>();
        for (String groupId : unique) {
            Map<String, GroupReadCursor> byReader = byGroup.get(groupId);
            if (byReader != null) {
                out.addAll(byReader.values());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
