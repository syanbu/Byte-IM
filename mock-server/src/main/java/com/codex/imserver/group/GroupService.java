package com.codex.imserver.group;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class GroupService {
    private final LongSupplier clock;
    private final GroupStore groupStore;
    private final AtomicLong nextGroupNumber;

    public GroupService() {
        this(System::currentTimeMillis);
    }

    public GroupService(LongSupplier clock) {
        this(new InMemoryGroupStore(), clock);
    }

    public GroupService(GroupStore groupStore, LongSupplier clock) {
        this.clock = clock;
        this.groupStore = groupStore;
        this.nextGroupNumber = new AtomicLong(groupStore.maxGroupNumber());
    }

    public GroupRecord createGroup(String ownerId, String name, List<String> memberUserIds) {
        Set<String> normalizedMembers = new LinkedHashSet<>();
        normalizedMembers.add(ownerId);
        for (String memberUserId : memberUserIds) {
            if (memberUserId != null && !memberUserId.isBlank()) {
                normalizedMembers.add(memberUserId.trim());
            }
        }
        String groupId = "g_" + nextGroupNumber.incrementAndGet();
        long now = clock.getAsLong();
        GroupRecord group = new GroupRecord(
                groupId,
                name == null || name.isBlank() ? "群聊(" + normalizedMembers.size() + ")" : name,
                ownerId,
                List.copyOf(normalizedMembers),
                now,
                now
        );
        groupStore.save(group);
        return group;
    }

    public String createGroupJson(String ownerId, String name, List<String> memberUserIds) {
        GroupRecord group = createGroup(ownerId, name, memberUserIds);
        return success(group).toString();
    }

    public String groupJson(String groupId, String requesterId) {
        GroupRecord group = groupStore.findById(groupId).orElse(null);
        if (group == null) {
            return failure(404, "Group not found").toString();
        }
        if (!group.memberUserIds().contains(requesterId)) {
            return failure(403, "Forbidden").toString();
        }
        return success(group).toString();
    }

    public String groupsJson(String requesterId) {
        JsonArray groups = new JsonArray();
        groupStore.findByMember(requesterId).forEach(group -> groups.add(groupJsonObject(group)));
        JsonObject data = new JsonObject();
        data.add("groups", groups);
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public String membersJson(String groupId, String requesterId) {
        GroupRecord group = groupStore.findById(groupId).orElse(null);
        if (group == null) {
            return failure(404, "Group not found").toString();
        }
        if (!group.memberUserIds().contains(requesterId)) {
            return failure(403, "Forbidden").toString();
        }
        JsonArray members = new JsonArray();
        for (String memberUserId : group.memberUserIds()) {
            JsonObject member = new JsonObject();
            member.addProperty("groupId", group.groupId());
            member.addProperty("userId", memberUserId);
            member.addProperty("displayName", memberUserId);
            member.addProperty("role", memberUserId.equals(group.ownerId()) ? "OWNER" : "MEMBER");
            member.addProperty("joinedAt", group.createdAt());
            member.addProperty("updatedAt", group.updatedAt());
            members.add(member);
        }
        JsonObject data = groupJsonObject(group);
        data.add("members", members);
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", data);
        return root.toString();
    }

    public String renameGroupJson(String groupId, String requesterId, String name) {
        GroupRecord group = groupStore.findById(groupId).orElse(null);
        if (group == null) {
            return failure(404, "Group not found").toString();
        }
        if (!group.memberUserIds().contains(requesterId)) {
            return failure(403, "Forbidden").toString();
        }
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            return failure(400, "Group name is required").toString();
        }
        long now = clock.getAsLong();
        GroupRecord updated = new GroupRecord(
                group.groupId(),
                trimmedName,
                group.ownerId(),
                group.memberUserIds(),
                group.createdAt(),
                now
        );
        groupStore.save(updated);
        return success(updated).toString();
    }

    public boolean isMember(String groupId, String userId) {
        return groupStore.findById(groupId)
                .map(group -> group.memberUserIds().contains(userId))
                .orElse(false);
    }

    public String groupName(String groupId) {
        return groupStore.findById(groupId)
                .map(GroupRecord::name)
                .orElse(groupId);
    }

    public List<String> recipientsForSend(String groupId, String senderUserId) {
        GroupRecord group = groupStore.findById(groupId).orElse(null);
        if (group == null || !group.memberUserIds().contains(senderUserId)) {
            return List.of();
        }
        List<String> recipients = new ArrayList<>();
        for (String memberUserId : group.memberUserIds()) {
            if (!memberUserId.equals(senderUserId)) {
                recipients.add(memberUserId);
            }
        }
        return recipients;
    }

    private JsonObject success(GroupRecord group) {
        JsonObject root = new JsonObject();
        root.addProperty("code", 0);
        root.addProperty("message", "ok");
        root.add("data", groupJsonObject(group));
        return root;
    }

    private JsonObject groupJsonObject(GroupRecord group) {
        JsonObject data = new JsonObject();
        data.addProperty("groupId", group.groupId());
        data.addProperty("name", group.name());
        data.addProperty("ownerId", group.ownerId());
        data.addProperty("createdAt", group.createdAt());
        data.addProperty("updatedAt", group.updatedAt());
        JsonArray memberUserIds = new JsonArray();
        group.memberUserIds().forEach(memberUserIds::add);
        data.add("memberUserIds", memberUserIds);
        return data;
    }

    private JsonObject failure(int code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("code", code);
        root.addProperty("message", message);
        return root;
    }

    public record GroupRecord(
            String groupId,
            String name,
            String ownerId,
            List<String> memberUserIds,
            long createdAt,
            long updatedAt
    ) {
    }
}
