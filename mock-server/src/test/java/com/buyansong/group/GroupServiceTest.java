package com.buyansong.imserver.group;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GroupServiceTest {
    @Test
    public void createGroupResponseIncludesOwnerMembersAndStableGroupId() {
        GroupService service = new GroupService(() -> 1_000L);

        String json = service.createGroupJson("13800113800", "群聊(3)", List.of("13900113900", "13700113700"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = root.getAsJsonObject("data");
        assertEquals(0, root.get("code").getAsInt());
        assertEquals("g_1001", data.get("groupId").getAsString());
        assertEquals("群聊(3)", data.get("name").getAsString());
        assertEquals("13800113800", data.get("ownerId").getAsString());
        assertEquals(List.of("13800113800", "13900113900", "13700113700"), data.getAsJsonArray("memberUserIds").asList().stream().map(element -> element.getAsString()).toList());
        assertTrue(service.isMember("g_1001", "13700113700"));
    }

    @Test
    public void membersJsonRequiresMembership() {
        GroupService service = new GroupService(() -> 1_000L);
        service.createGroup("13800113800", "群聊(2)", List.of("13900113900"));

        JsonObject denied = JsonParser.parseString(service.membersJson("g_1001", "13700113700")).getAsJsonObject();

        assertEquals(403, denied.get("code").getAsInt());
    }

    @Test
    public void renameGroupUpdatesNameForMembers() {
        GroupService service = new GroupService(() -> 1_000L);
        service.createGroup("13800113800", "旧群名", List.of("13900113900"));

        String json = service.renameGroupJson("g_1001", "13900113900", "新群名");

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(0, root.get("code").getAsInt());
        assertEquals("新群名", root.getAsJsonObject("data").get("name").getAsString());
        assertEquals("新群名", service.groupName("g_1001"));
    }

    @Test
    public void sqliteStorePersistsGroupsMembersAndRenameAcrossRestart() throws Exception {
        Path directory = Files.createTempDirectory("mock-im-groups");
        Path database = directory.resolve("groups.sqlite");
        GroupService firstService = new GroupService(new SQLiteGroupStore(database), () -> 1_000L);
        firstService.createGroup("13800113800", "旧群名", List.of("13900113900", "13700113700"));
        firstService.renameGroupJson("g_1001", "13900113900", "新群名");

        GroupService secondService = new GroupService(new SQLiteGroupStore(database), () -> 2_000L);

        assertEquals("新群名", secondService.groupName("g_1001"));
        assertTrue(secondService.isMember("g_1001", "13700113700"));
        JsonObject groups = JsonParser.parseString(secondService.groupsJson("13900113900")).getAsJsonObject();
        JsonObject group = groups.getAsJsonObject("data").getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals("g_1001", group.get("groupId").getAsString());
        assertEquals("新群名", group.get("name").getAsString());

        GroupService thirdService = new GroupService(new SQLiteGroupStore(database), () -> 3_000L);
        thirdService.createGroup("13800113800", "第二个群", List.of("13600113600"));

        assertEquals("第二个群", thirdService.groupName("g_1002"));
    }
}
