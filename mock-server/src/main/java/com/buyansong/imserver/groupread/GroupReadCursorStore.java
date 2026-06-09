package com.buyansong.imserver.groupread;

import java.util.List;

public interface GroupReadCursorStore {
    boolean upsertIfGreater(String groupId, String readerId, long readUpToServerSeq, long readAt);

    List<GroupReadCursor> findByMemberOf(List<String> groupIds);
}
