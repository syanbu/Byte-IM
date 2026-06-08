package com.buyansong.imserver.group;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryGroupStore implements GroupStore {
    private final ConcurrentMap<String, GroupService.GroupRecord> groupsById = new ConcurrentHashMap<>();

    @Override
    public Optional<GroupService.GroupRecord> findById(String groupId) {
        return Optional.ofNullable(groupsById.get(groupId));
    }

    @Override
    public List<GroupService.GroupRecord> findByMember(String userId) {
        return groupsById.values().stream()
                .filter(group -> group.memberUserIds().contains(userId))
                .sorted(Comparator.comparing(GroupService.GroupRecord::groupId))
                .toList();
    }

    @Override
    public void save(GroupService.GroupRecord group) {
        groupsById.put(group.groupId(), group);
    }

    @Override
    public long maxGroupNumber() {
        return groupsById.keySet().stream()
                .mapToLong(InMemoryGroupStore::groupNumber)
                .max()
                .orElse(1000L);
    }

    private static long groupNumber(String groupId) {
        if (groupId == null || !groupId.startsWith("g_")) {
            return 0L;
        }
        try {
            return Long.parseLong(groupId.substring(2));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
