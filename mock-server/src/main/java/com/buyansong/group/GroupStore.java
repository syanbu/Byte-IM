package com.buyansong.imserver.group;

import java.util.List;
import java.util.Optional;

public interface GroupStore {
    Optional<GroupService.GroupRecord> findById(String groupId);

    List<GroupService.GroupRecord> findByMember(String userId);

    void save(GroupService.GroupRecord group);

    long maxGroupNumber();
}
