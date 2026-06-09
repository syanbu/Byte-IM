# Group Read Receipts (B12-G) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a sender-only "X 人已读 >" indicator below the latest SENT message in a group chat, with a bottom sheet that lists the readers, while reusing the existing `READ_ACK` protocol and not touching the single-chat read path.

**Architecture:** A new `group_read_cursors` table on both server and client tracks `(groupId, readerId) → (readUpToServerSeq, readAt)`. The client `combine`s `messages × cursors × members` through two new pure functions in `GroupReadReceiptPolicy` to derive the indicator state. `MessageRepository.sendGroupReadAck` is called on chat-open (via `openConversationById`) and on each `conversationUpdates` tick that follows an incoming message (the per-group monotonic de-dup guards against over-firing), and `MessageRepository.handleReadAck` routes incoming `READ_ACK` packets by `conversationType`. The server `MessageRouter` upserts the cursor and broadcasts to all online group members; `MessageRouter.handleAuth` replays the user's cursors on connect.

**Tech Stack:** Kotlin 1.9 + Coroutines/Flow, Jetpack Compose, hand-written `SQLiteOpenHelper`, Gson packet bodies, Java 21 + JUnit 4 (mock-server), Maven 3 (mock-server), Gradle 8 (app).

---

## File Structure

**Mock-server (new)**
- `mock-server/src/main/java/com/buyansong/imserver/groupread/GroupReadCursor.java` - domain record.
- `mock-server/src/main/java/com/buyansong/imserver/groupread/GroupReadCursorStore.java` - interface (`upsertIfGreater`, `findByMemberOf`).
- `mock-server/src/main/java/com/buyansong/imserver/groupread/InMemoryGroupReadCursorStore.java` - for tests.
- `mock-server/src/main/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStore.java` - JDBC impl, same `mock-im-messages.sqlite` DB as `accepted_messages`.
- `mock-server/src/test/java/com/buyansong/imserver/groupread/InMemoryGroupReadCursorStoreTest.java`.
- `mock-server/src/test/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStoreTest.java`.
- `mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterGroupReadAckTest.java`.
- `mock-server/src/test/java/com/buyansong/imserver/session/SessionRegistryReplayTest.java`.

**Mock-server (modified)**
- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` - new `GROUP` branch in `handleReadAck`; new ctor arg + field for `GroupReadCursorStore`; new `handleGroupReadAck` helper; new `replayGroupReadCursorsFor` method; call it from `handleAuth` after the two existing replay steps.
- `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java` - add `membersForReadAck(groupId)` and `findGroupsByMember(userId)`.
- `mock-server/src/main/java/com/buyansong/imserver/MockImServer.java` - instantiate the new SQLite store and pass it into the `MessageRouter` ctor.

**Android (new)**
- `app/src/main/java/com/buyansong/im/chat/GroupReadReceiptPolicy.kt` - two pure functions.
- `app/src/main/java/com/buyansong/im/chat/GroupReadIndicator.kt` - Composable.
- `app/src/main/java/com/buyansong/im/chat/GroupReadDetailSheet.kt` - Composable.
- `app/src/main/java/com/buyansong/im/storage/GroupReadCursorDao.kt` - interface.
- `app/src/main/java/com/buyansong/im/storage/AndroidGroupReadCursorDao.kt` - SQLite impl.
- `app/src/main/java/com/buyansong/im/storage/InMemoryGroupReadCursorDao.kt` - for unit tests.
- `app/src/main/java/com/buyansong/im/group/GroupReadCursorRepository.kt` - query + change-notification facade.
- `app/src/test/java/com/buyansong/im/storage/InMemoryGroupReadCursorDaoTest.kt`.
- `app/src/test/java/com/buyansong/im/storage/AndroidGroupReadCursorDaoContractTest.kt`.
- `app/src/test/java/com/buyansong/im/message/MessageRepositoryGroupReadAckTest.kt`.
- `app/src/test/java/com/buyansong/im/chat/GroupReadReceiptPolicyTest.kt`.
- `app/src/test/java/com/buyansong/im/chat/ChatViewModelGroupReadReceiptTest.kt`.

**Android (modified)**
- `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt` - add `createGroupReadCursorsTable`; bump `DATABASE_VERSION`; `onUpgrade` calls the new create method when `oldVersion < NEW_VERSION` (additive only).
- `app/src/main/java/com/buyansong/im/storage/StorageModels.kt` - add `GroupReadCursor` data class.
- `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` - add `sendGroupReadAck`, `applyIncomingGroupReadAck`, `observeGroupReadCursors`; new ctor param `groupReadCursorRepository` (default `null`); refactor `handleReadAck` to branch on `conversationType`; call `sendGroupReadAck` in `openConversationById` for group conversations.
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` - add three new fields to `ChatUiState`; add a new `scope.launch` that observes `observeGroupReadCursors(groupId)` and computes the latest-own-sent id + readers via `GroupReadReceiptPolicy`.
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` - render `GroupReadIndicator` and `GroupReadDetailSheet`.
- `app/src/main/java/com/buyansong/im/MainActivity.kt` - wire `AndroidGroupReadCursorDao` into `MessageRepository` ctor inside `AccountScopedRepositories.create`.

**Docs (modified)**
- `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md` - add `conversationType` to `READ_ACK`, two JSON examples.
- `docs/status/B12-message-recall-and-read-receipts.md` - new "Group Read Receipts (B12-G)" subsection; flip the "out of scope" line.
- `docs/bg/DEVELOPMENT_STATUS.md` - B12 row updated.

---

## Task 1: Update protocol spec doc (small but spec-mandated)

**Files:**
- Modify: `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`

- [ ] **Step 1: Read current protocol doc to find the READ_ACK section**

Run: `grep -n "READ_ACK" /home/buyansong/IM/docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`

Expected: a single line with the existing READ_ACK JSON example.

- [ ] **Step 2: Replace the single-chat READ_ACK block with both single and group examples**

Find the `### READ_ACK` (or equivalent) section. Replace its body with:

````markdown
`READ_ACK` (cmd = 13) - sender tells the server it has read up to a given serverSeq.

**Body (single chat, legacy - preserved for compatibility):**

```json
{
  "conversationId": "single:u_1001:u_1002",
  "readerId": "13900113900",
  "peerId": "u_1002",
  "readUpToServerSeq": 1010,
  "readAt": 1717000000000
}
```

Server forwards this packet verbatim to the peer identified by `peerId` (if online). B12 behavior.

**Body (group chat - new in B12-G):**

```json
{
  "conversationId": "group:g_1001",
  "conversationType": "GROUP",
  "readerId": "13900113900",
  "readUpToServerSeq": 1010,
  "readAt": 1717000000000
}
```

`conversationType` MUST be `GROUP` for the new group path. The server:
1. Upserts `(groupId, readerId) -> (readUpToServerSeq, readAt)` monotonically (`>` only).
2. If the upsert actually advanced, broadcasts the same JSON to every online group member.

When `conversationType` is absent, the server treats it as `SINGLE` (legacy fallback).
````

- [ ] **Step 3: Verify the change**

Run: `grep -n "conversationType" /home/buyansong/IM/docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`

Expected: at least two hits - the GROUP body example and the legacy fallback sentence.

- [ ] **Step 4: Commit**

```bash
git add docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md
git commit -m "docs: add conversationType to READ_ACK for group receipts"
```

---

## Task 2: GroupReadCursor domain type + InMemory store (mock-server, TDD)

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/groupread/GroupReadCursor.java`
- Create: `mock-server/src/main/java/com/buyansong/imserver/groupread/GroupReadCursorStore.java`
- Create: `mock-server/src/main/java/com/buyansong/imserver/groupread/InMemoryGroupReadCursorStore.java`
- Create: `mock-server/src/test/java/com/buyansong/imserver/groupread/InMemoryGroupReadCursorStoreTest.java`

- [ ] **Step 1: Write the failing test**

Create `mock-server/src/test/java/com/buyansong/imserver/groupread/InMemoryGroupReadCursorStoreTest.java`:

```java
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
        assertFalse(store.upsertIfGreater("g_1", "u_a", 100L, 1_500L)); // equal
        assertFalse(store.upsertIfGreater("g_1", "u_a",  50L, 1_500L)); // smaller
        // Bigger advances
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
```

- [ ] **Step 2: Run the test - expect compile failure**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=InMemoryGroupReadCursorStoreTest`

Expected: `BUILD FAILURE` with "package com.buyansong.imserver.groupread does not exist" or "cannot find symbol: class GroupReadCursorStore". This is the expected red.

- [ ] **Step 3: Create the domain type**

Create `mock-server/src/main/java/com/buyansong/imserver/groupread/GroupReadCursor.java`:

```java
package com.buyansong.imserver.groupread;

public record GroupReadCursor(
        String groupId,
        String readerId,
        long readUpToServerSeq,
        long readAt
) {
}
```

- [ ] **Step 4: Create the store interface**

Create `mock-server/src/main/java/com/buyansong/imserver/groupread/GroupReadCursorStore.java`:

```java
package com.buyansong.imserver.groupread;

import java.util.List;

public interface GroupReadCursorStore {
    /**
     * Inserts or updates the cursor for (groupId, readerId) only when
     * {@code readUpToServerSeq} is strictly greater than the existing value.
     * Returns {@code true} when the row was actually changed.
     */
    boolean upsertIfGreater(String groupId, String readerId, long readUpToServerSeq, long readAt);

    /**
     * Returns all cursors for any of the given groupIds, in any order.
     */
    List<GroupReadCursor> findByMemberOf(List<String> groupIds);
}
```

- [ ] **Step 5: Create the InMemory impl**

Create `mock-server/src/main/java/com/buyansong/imserver/groupread/InMemoryGroupReadCursorStore.java`:

```java
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
            if (byReader == null) continue;
            out.addAll(byReader.values());
        }
        return Collections.unmodifiableList(out);
    }
}
```

- [ ] **Step 6: Run the test - expect green**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=InMemoryGroupReadCursorStoreTest`

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
cd /home/buyansong/IM
git add mock-server/src/main/java/com/buyansong/imserver/groupread/ \
        mock-server/src/test/java/com/buyansong/imserver/groupread/
git commit -m "feat(server): add group read cursor domain type and in-memory store"
```

---

## Task 3: SQLiteGroupReadCursorStore (mock-server, TDD)

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStore.java`
- Create: `mock-server/src/test/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStoreTest.java`

- [ ] **Step 1: Write the failing test**

Create `mock-server/src/test/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStoreTest.java`:

```java
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
        assertFalse(store.upsertIfGreater("g_1", "u_a", 100L, 1_500L)); // equal
        assertFalse(store.upsertIfGreater("g_1", "u_a",  50L, 2_000L)); // smaller
        assertTrue(store.upsertIfGreater("g_1", "u_a", 101L, 3_000L));
        assertTrue(store.upsertIfGreater("g_1", "u_b",  10L, 4_000L));

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
```

- [ ] **Step 2: Run the test - expect compile failure**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=SQLiteGroupReadCursorStoreTest`

Expected: `BUILD FAILURE` with "cannot find symbol: class SQLiteGroupReadCursorStore".

- [ ] **Step 3: Implement SQLiteGroupReadCursorStore**

Create `mock-server/src/main/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStore.java`:

```java
package com.buyansong.imserver.groupread;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SQLiteGroupReadCursorStore implements GroupReadCursorStore {
    private final String jdbcUrl;

    public SQLiteGroupReadCursorStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to create database directory", error);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().toUri();
        initialize();
    }

    @Override
    public synchronized boolean upsertIfGreater(String groupId, String readerId, long readUpToServerSeq, long readAt) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long existing;
                try (PreparedStatement read = connection.prepareStatement(
                        "SELECT read_up_to_server_seq FROM group_read_cursors WHERE group_id = ? AND reader_id = ?")) {
                    read.setString(1, groupId);
                    read.setString(2, readerId);
                    try (ResultSet rs = read.executeQuery()) {
                        existing = rs.next() ? rs.getLong(1) : Long.MIN_VALUE;
                    }
                }
                if (existing >= readUpToServerSeq) {
                    connection.commit();
                    return false;
                }
                try (PreparedStatement write = connection.prepareStatement(
                        """
                        INSERT INTO group_read_cursors(group_id, reader_id, read_up_to_server_seq, read_at)
                        VALUES(?, ?, ?, ?)
                        ON CONFLICT(group_id, reader_id) DO UPDATE SET
                          read_up_to_server_seq = excluded.read_up_to_server_seq,
                          read_at = excluded.read_at
                        """)) {
                    write.setString(1, groupId);
                    write.setString(2, readerId);
                    write.setLong(3, readUpToServerSeq);
                    write.setLong(4, readAt);
                    write.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to upsert group read cursor", error);
        }
    }

    @Override
    public synchronized List<GroupReadCursor> findByMemberOf(List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new HashSet<>(groupIds);
        StringBuilder sql = new StringBuilder(
                "SELECT group_id, reader_id, read_up_to_server_seq, read_at FROM group_read_cursors WHERE group_id IN (");
        for (int i = 0; i < unique.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int i = 1;
            for (String groupId : unique) {
                statement.setString(i++, groupId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<GroupReadCursor> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new GroupReadCursor(
                            rs.getString("group_id"),
                            rs.getString("reader_id"),
                            rs.getLong("read_up_to_server_seq"),
                            rs.getLong("read_at")
                    ));
                }
                return out;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to read group read cursors", error);
        }
    }

    private void initialize() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS group_read_cursors (
                      group_id              TEXT    NOT NULL,
                      reader_id             TEXT    NOT NULL,
                      read_up_to_server_seq INTEGER NOT NULL,
                      read_at               INTEGER NOT NULL,
                      PRIMARY KEY(group_id, reader_id)
                    )
                    """
            );
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_group_read_cursors_group ON group_read_cursors(group_id)"
            );
        } catch (SQLException error) {
            throw new IllegalStateException("Unable to initialize group read cursor store", error);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
```

- [ ] **Step 4: Run the test - expect green**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=SQLiteGroupReadCursorStoreTest`

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd /home/buyansong/IM
git add mock-server/src/main/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStore.java \
        mock-server/src/test/java/com/buyansong/imserver/groupread/SQLiteGroupReadCursorStoreTest.java
git commit -m "feat(server): persist group read cursors in SQLite"
```

---

## Task 4: Group READ_ACK routing in MessageRouter (mock-server, TDD)

**Files:**
- Modify: `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java`
- Create: `mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterGroupReadAckTest.java`

- [ ] **Step 1: Read the existing handleReadAck and constructor signatures**

Run: `grep -n "handleReadAck\|public MessageRouter\|private final GroupService\|private final ClientSessionRegistry\|handleGroupSendMessage" /home/buyansong/IM/mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java | head -20`

Expected: at least 5 hits. Note the lines of the largest existing ctor (the one taking `GroupService`) for the next step.

- [ ] **Step 2: Write the failing test**

Create `mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterGroupReadAckTest.java`:

```java
package com.buyansong.imserver.session;

import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.InMemoryGroupStore;
import com.buyansong.imserver.groupread.GroupReadCursor;
import com.buyansong.imserver.groupread.GroupReadCursorStore;
import com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore;
import com.buyansong.imserver.protocol.ImCommand;
import com.buyansong.imserver.protocol.ImPacket;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MessageRouterGroupReadAckTest {

    private static class CapturingClient implements OutboundClient {
        final List<ImPacket> sent = new ArrayList<>();
        @Override
        public void send(ImPacket packet) { sent.add(packet); }
    }

    private static class FakeRegistry implements ClientSessionRegistry {
        final Map<String, OutboundClient> map = new HashMap<>();
        @Override public void register(String userId, OutboundClient client) { map.put(userId, client); }
        @Override public Optional<OutboundClient> find(String userId) { return Optional.ofNullable(map.get(userId)); }
        @Override public Optional<String> userIdOf(OutboundClient client) {
            return map.entrySet().stream().filter(e -> e.getValue() == client).map(Map.Entry::getKey).findFirst();
        }
        @Override public void remove(OutboundClient client) { map.values().removeIf(c -> c == client); }
    }

    private ImPacket readAckPacket(String conversationId, String conversationType, String readerId, long readUpToServerSeq, long readAt) {
        JsonObject body = new JsonObject();
        body.addProperty("conversationId", conversationId);
        if (conversationType != null) body.addProperty("conversationType", conversationType);
        body.addProperty("readerId", readerId);
        body.addProperty("readUpToServerSeq", readUpToServerSeq);
        body.addProperty("readAt", readAt);
        return new ImPacket(ImCommand.READ_ACK.value(), body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private MessageRouter newRouter(FakeRegistry registry, GroupReadCursorStore cursorStore, AtomicLong clock) {
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        return new MessageRouter(
                registry,
                TokenService.defaultService(),
                new MessageRouter.InMemoryServerSeqStore(),
                new MessageRouter.InMemoryAcceptedMessageStore(),
                groupService,
                cursorStore,
                clock::get
        );
    }

    @Test
    public void groupReadAck_isBroadcastToAllOnlineGroupMembers() {
        FakeRegistry registry = new FakeRegistry();
        CapturingClient senderClient = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        CapturingClient memberC = new CapturingClient();
        registry.register("u_a", senderClient);
        registry.register("u_b", memberB);
        registry.register("u_c", memberC);

        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        groupService.createGroup("u_a", "Test", List.of("u_b", "u_c"));

        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, cursorStore, clock);

        router.handleReadAck("u_b", readAckPacket("group:g_1", "GROUP", "u_b", 100L, 1_000L));

        List<GroupReadCursor> rows = cursorStore.findByMemberOf(List.of("g_1"));
        assertEquals(1, rows.size());
        assertEquals("u_b", rows.get(0).readerId());
        assertEquals(100L, rows.get(0).readUpToServerSeq());

        assertEquals(1, senderClient.sent.size());
        assertEquals(1, memberB.sent.size());
        assertEquals(1, memberC.sent.size());
        for (OutboundClient c : List.of(senderClient, memberB, memberC)) {
            ImPacket sent = ((CapturingClient) c).sent.get(0);
            assertEquals(ImCommand.READ_ACK.value(), sent.cmd());
        }
    }

    @Test
    public void groupReadAck_rejectedWhenReaderIdMismatchesSocket() {
        FakeRegistry registry = new FakeRegistry();
        CapturingClient memberB = new CapturingClient();
        registry.register("u_b", memberB);
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        groupService.createGroup("u_a", "Test", List.of("u_b"));
        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, cursorStore, clock);
        router.handleReadAck("u_b", readAckPacket("group:g_1", "GROUP", "u_a", 50L, 1_000L));
        assertTrue("expected no broadcast when readerId mismatches socket", memberB.sent.isEmpty());
        assertTrue("expected no cursor written", cursorStore.findByMemberOf(List.of("g_1")).isEmpty());
    }

    @Test
    public void groupReadAck_rejectedWhenSenderNotGroupMember() {
        FakeRegistry registry = new FakeRegistry();
        CapturingClient memberB = new CapturingClient();
        registry.register("u_b", memberB);
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        groupService.createGroup("u_a", "Test", List.of("u_b"));
        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, cursorStore, clock);
        router.handleReadAck("u_x", readAckPacket("group:g_1", "GROUP", "u_x", 50L, 1_000L));
        assertTrue(memberB.sent.isEmpty());
        assertTrue(cursorStore.findByMemberOf(List.of("g_1")).isEmpty());
    }

    @Test
    public void groupReadAck_staleCursorIsNotBroadcast() {
        FakeRegistry registry = new FakeRegistry();
        CapturingClient sender = new CapturingClient();
        CapturingClient memberB = new CapturingClient();
        registry.register("u_a", sender);
        registry.register("u_b", memberB);
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        groupService.createGroup("u_a", "Test", List.of("u_b"));
        GroupReadCursorStore cursorStore = new InMemoryGroupReadCursorStore();
        MessageRouter router = newRouter(registry, cursorStore, clock);

        router.handleReadAck("u_b", readAckPacket("group:g_1", "GROUP", "u_b", 100L, 1_000L));
        sender.sent.clear();
        memberB.sent.clear();

        router.handleReadAck("u_b", readAckPacket("group:g_1", "GROUP", "u_b",  50L, 2_000L));
        assertTrue("stale cursor must not rebroadcast", sender.sent.isEmpty());
        assertTrue(memberB.sent.isEmpty());

        router.handleReadAck("u_b", readAckPacket("group:g_1", "GROUP", "u_b", 100L, 2_500L));
        assertTrue("equal cursor must not rebroadcast", sender.sent.isEmpty());
    }
}
```

- [ ] **Step 3: Run the test - expect compile failure (constructor mismatch)**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=MessageRouterGroupReadAckTest`

Expected: `BUILD FAILURE` with "no suitable constructor found for MessageRouter(...)" or "cannot find symbol: class InMemoryGroupStore".

- [ ] **Step 4: Add the new constructor to MessageRouter**

Open `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`. Add a new field after the existing `private final GroupService groupService;`:

```java
    private final com.buyansong.imserver.groupread.GroupReadCursorStore groupReadCursorStore;
```

In the existing public ctor that takes `(ClientSessionRegistry, TokenService, ServerSeqStore, AcceptedMessageStore, GroupService, LongSupplier)` (the largest existing ctor), replace its body so it delegates to the new ctor:

```java
        this(registry, tokenService, serverSeqStore, acceptedMessageStore, groupService,
                new com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore(), clock);
```

Add a new package-private ctor accepting the cursor store:

```java
    MessageRouter(
            ClientSessionRegistry registry,
            TokenService tokenService,
            ServerSeqStore serverSeqStore,
            AcceptedMessageStore acceptedMessageStore,
            GroupService groupService,
            com.buyansong.imserver.groupread.GroupReadCursorStore groupReadCursorStore,
            LongSupplier clock
    ) {
        this.registry = registry;
        this.tokenService = tokenService;
        this.serverSeqStore = serverSeqStore;
        this.acceptedMessageStore = acceptedMessageStore;
        this.groupService = groupService;
        this.groupReadCursorStore = groupReadCursorStore;
        this.clock = clock;
        restoreAcceptedMessages();
    }
```

- [ ] **Step 5: Refactor handleReadAck to branch on conversationType**

Replace the entire `handleReadAck` body with:

```java
    public synchronized void handleReadAck(String socketUserId, ImPacket packet) {
        JsonObject ack = JsonParser
                .parseString(new String(packet.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String readerId = ack.get("readerId").getAsString();
        if (!socketUserId.equals(readerId)) {
            ImServerLogger.log("[IM] READ_ACK rejected socketUserId=%s readerId=%s", socketUserId, readerId);
            return;
        }
        String conversationType = optionalString(ack, "conversationType", "SINGLE");
        if ("GROUP".equals(conversationType)) {
            handleGroupReadAck(socketUserId, ack);
            return;
        }
        String peerId = ack.get("peerId").getAsString();
        registry.find(peerId).ifPresentOrElse(
                client -> {
                    client.send(packet(ImCommand.READ_ACK, ack));
                    ImServerLogger.log(
                            "[IM] READ_ACK forwarded reader=%s peer=%s readUpToServerSeq=%d",
                            readerId,
                            peerId,
                            ack.get("readUpToServerSeq").getAsLong()
                    );
                },
                () -> ImServerLogger.log("[IM] READ_ACK skipped peer offline reader=%s peer=%s", readerId, peerId)
        );
    }

    private void handleGroupReadAck(String socketUserId, JsonObject ack) {
        String conversationId = ack.get("conversationId").getAsString();
        String groupId = conversationId.startsWith("group:") ? conversationId.substring("group:".length()) : conversationId;
        String readerId = ack.get("readerId").getAsString();
        long readUpToServerSeq = ack.get("readUpToServerSeq").getAsLong();
        long readAt = ack.get("readAt").getAsLong();
        if (!groupService.isMember(groupId, readerId)) {
            ImServerLogger.log("[IM] GROUP_READ_ACK rejected non-member reader=%s groupId=%s", readerId, groupId);
            return;
        }
        boolean advanced = groupReadCursorStore.upsertIfGreater(groupId, readerId, readUpToServerSeq, readAt);
        if (!advanced) {
            ImServerLogger.log("[IM] GROUP_READ_ACK stale reader=%s groupId=%s seq=%d", readerId, groupId, readUpToServerSeq);
            return;
        }
        for (String memberId : groupService.membersForReadAck(groupId)) {
            OutboundClient client = registry.find(memberId).orElse(null);
            if (client == null) continue;
            client.send(packet(ImCommand.READ_ACK, ack));
        }
        ImServerLogger.log("[IM] GROUP_READ_ACK broadcast reader=%s groupId=%s seq=%d", readerId, groupId, readUpToServerSeq);
    }
```

- [ ] **Step 6: Add membersForReadAck to GroupService**

Open `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java` and add this public method (next to `recipientsForSend`):

```java
    public List<String> membersForReadAck(String groupId) {
        GroupRecord group = groupStore.findById(groupId).orElse(null);
        if (group == null) {
            return List.of();
        }
        return List.copyOf(group.memberUserIds());
    }
```

- [ ] **Step 7: Run the test - expect green**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=MessageRouterGroupReadAckTest`

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
cd /home/buyansong/IM
git add mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java \
        mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java \
        mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterGroupReadAckTest.java
git commit -m "feat(server): route GROUP READ_ACK to all members with monotonic cursor"
```

---

## Task 5: Replay GROUP READ_ACK cursors on auth (mock-server)

**Files:**
- Modify: `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` (add `replayGroupReadCursorsFor`; wire into `handleAuth`)
- Modify: `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java` (add `findGroupsByMember`)
- Modify: `mock-server/src/main/java/com/buyansong/imserver/MockImServer.java` (instantiate the new SQLite store, pass to ctor)
- Create: `mock-server/src/test/java/com/buyansong/imserver/session/SessionRegistryReplayTest.java`

- [ ] **Step 1: Write the failing test**

Create `mock-server/src/test/java/com/buyansong/imserver/session/SessionRegistryReplayTest.java`:

```java
package com.buyansong.imserver.session;

import com.buyansong.imserver.auth.TokenService;
import com.buyansong.imserver.group.GroupService;
import com.buyansong.imserver.group.InMemoryGroupStore;
import com.buyansong.imserver.groupread.InMemoryGroupReadCursorStore;
import com.buyansong.imserver.protocol.ImCommand;
import com.buyansong.imserver.protocol.ImPacket;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionRegistryReplayTest {

    private static class CapturingClient implements OutboundClient {
        final List<ImPacket> sent = new ArrayList<>();
        @Override public void send(ImPacket packet) { sent.add(packet); }
    }

    private static class FakeRegistry implements ClientSessionRegistry {
        final Map<String, OutboundClient> map = new HashMap<>();
        @Override public void register(String userId, OutboundClient client) { map.put(userId, client); }
        @Override public Optional<OutboundClient> find(String userId) { return Optional.ofNullable(map.get(userId)); }
        @Override public Optional<String> userIdOf(OutboundClient client) {
            return map.entrySet().stream().filter(e -> e.getValue() == client).map(Map.Entry::getKey).findFirst();
        }
        @Override public void remove(OutboundClient client) { map.values().removeIf(c -> c == client); }
    }

    @Test
    public void replayGroupReadCursorsFor_emitsAllCursors() {
        FakeRegistry registry = new FakeRegistry();
        AtomicLong clock = new AtomicLong(1_000L);
        GroupService groupService = new GroupService(new InMemoryGroupStore(), clock::get);
        groupService.createGroup("u_a", "G1", List.of("u_b", "u_c"));
        groupService.createGroup("u_a", "G2", List.of("u_b"));

        var cursorStore = new InMemoryGroupReadCursorStore();
        cursorStore.upsertIfGreater("g_1", "u_b", 10L, 1_000L);
        cursorStore.upsertIfGreater("g_2", "u_b", 99L, 2_000L);

        MessageRouter router = new MessageRouter(
                registry, TokenService.defaultService(),
                new MessageRouter.InMemoryServerSeqStore(),
                new MessageRouter.InMemoryAcceptedMessageStore(),
                groupService, cursorStore, clock::get
        );

        CapturingClient uBReconnect = new CapturingClient();
        router.replayGroupReadCursorsFor("u_b", uBReconnect);

        long readAcks = uBReconnect.sent.stream()
                .filter(p -> p.cmd() == ImCommand.READ_ACK.value())
                .count();
        assertEquals(2L, readAcks);
        for (ImPacket p : uBReconnect.sent) {
            String body = new String(p.body(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("expected conversationType=GROUP in " + body,
                    body.contains("\"conversationType\":\"GROUP\""));
        }
    }
}
```

- [ ] **Step 2: Run the test - expect compile failure**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=SessionRegistryReplayTest`

Expected: `BUILD FAILURE` with "cannot find symbol: method replayGroupReadCursorsFor".

- [ ] **Step 3: Add replayGroupReadCursorsFor to MessageRouter**

In `MessageRouter.java`, add this method (place it next to `deliverPendingRecallNotifies`):

```java
    void replayGroupReadCursorsFor(String userId, OutboundClient client) {
        java.util.List<com.buyansong.imserver.group.GroupService.GroupRecord> joined = groupService.findGroupsByMember(userId);
        if (joined.isEmpty()) return;
        java.util.List<String> groupIds = new java.util.ArrayList<>();
        for (com.buyansong.imserver.group.GroupService.GroupRecord g : joined) {
            groupIds.add(g.groupId());
        }
        for (com.buyansong.imserver.groupread.GroupReadCursor cursor : groupReadCursorStore.findByMemberOf(groupIds)) {
            JsonObject body = new JsonObject();
            body.addProperty("conversationId", "group:" + cursor.groupId());
            body.addProperty("conversationType", "GROUP");
            body.addProperty("readerId", cursor.readerId());
            body.addProperty("readUpToServerSeq", cursor.readUpToServerSeq());
            body.addProperty("readAt", cursor.readAt());
            client.send(packet(ImCommand.READ_ACK, body));
        }
    }
```

- [ ] **Step 4: Expose findGroupsByMember on GroupService**

In `GroupService.java`, add:

```java
    public List<GroupRecord> findGroupsByMember(String userId) {
        return groupStore.findByMember(userId);
    }
```

- [ ] **Step 5: Wire the replay into handleAuth**

In `MessageRouter.handleAuth` (near `deliverQueuedMessages` and `deliverPendingRecallNotifies` calls), add a new line right after the two existing replay calls:

```java
        replayGroupReadCursorsFor(userId, client);
```

- [ ] **Step 6: Run the test - expect green**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test -Dtest=SessionRegistryReplayTest`

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 7: Wire the SQLiteGroupReadCursorStore in MockImServer**

Open `mock-server/src/main/java/com/buyansong/imserver/MockImServer.java` and find where the existing `SQLiteAcceptedMessageStore` is constructed (search for `data/mock-im-messages.sqlite`). Just after that line, add:

```java
        GroupReadCursorStore groupReadCursorStore = new SQLiteGroupReadCursorStore(
                java.nio.file.Paths.get("data/mock-im-messages.sqlite"));
```

Pass it into the `MessageRouter` constructor. If the existing call uses the largest ctor (the one that takes `GroupService`), change it to the new ctor that also takes `GroupReadCursorStore`. If it uses a smaller ctor, the existing delegating ctor (updated in Task 4 Step 4 to default to `InMemoryGroupReadCursorStore`) will compile without change, but for production we want the SQLite one - replace it explicitly with the new ctor.

- [ ] **Step 8: Compile the whole module to catch any wiring breaks**

Run: `cd /home/buyansong/IM/mock-server && mvn -q -DskipTests test-compile`

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
cd /home/buyansong/IM
git add mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java \
        mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java \
        mock-server/src/main/java/com/buyansong/imserver/MockImServer.java \
        mock-server/src/test/java/com/buyansong/imserver/session/SessionRegistryReplayTest.java
git commit -m "feat(server): replay group read cursors on auth"
```

---

## Task 6: Android `GroupReadCursor` model + `InMemoryGroupReadCursorDao` (TDD)

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/StorageModels.kt`
- Create: `app/src/main/java/com/buyansong/im/storage/GroupReadCursorDao.kt`
- Create: `app/src/main/java/com/buyansong/im/storage/InMemoryGroupReadCursorDao.kt`
- Create: `app/src/test/java/com/buyansong/im/storage/InMemoryGroupReadCursorDaoTest.kt`

- [ ] **Step 1: Add the test source directory**

Run: `mkdir -p /home/buyansong/IM/app/src/test/java/com/buyansong/im/storage`

- [ ] **Step 2: Verify JUnit is on the test classpath**

Run: `grep -n "junit" /home/buyansong/IM/app/build.gradle`

Expected: at least one line containing `junit:junit`. If absent, add under `dependencies {}`:

```groovy
    testImplementation "junit:junit:4.13.2"
```

- [ ] **Step 3: Write the failing test**

Create `app/src/test/java/com/buyansong/im/storage/InMemoryGroupReadCursorDaoTest.kt`:

```kotlin
package com.buyansong.im.storage

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryGroupReadCursorDaoTest {
    @Test
    fun upsertIfGreater_persistsAndIgnoresStale() {
        val dao = InMemoryGroupReadCursorDao()
        assertTrue(dao.upsertIfGreater("g_1", "u_a", 100L, 1_000L))
        assertFalse(dao.upsertIfGreater("g_1", "u_a", 100L, 1_500L)) // equal
        assertFalse(dao.upsertIfGreater("g_1", "u_a",  50L, 2_000L)) // smaller
        assertTrue(dao.upsertIfGreater("g_1", "u_a", 101L, 3_000L))  // advances
    }

    @Test
    fun findByGroup_returnsAllRowsForThatGroup() {
        val dao = InMemoryGroupReadCursorDao()
        dao.upsertIfGreater("g_1", "u_a", 1L, 1L)
        dao.upsertIfGreater("g_1", "u_b", 2L, 2L)
        dao.upsertIfGreater("g_2", "u_a", 3L, 3L)
        val rows = dao.findByGroup("g_1")
        assertEquals(2, rows.size)
        assertEquals(setOf("u_a", "u_b"), rows.map { it.readerId }.toSet())
    }

    @Test
    fun observeByGroup_emitsAfterEveryChange() = runBlocking {
        val dao = InMemoryGroupReadCursorDao()
        val emitted = mutableListOf<List<GroupReadCursor>>()
        val job: Job = GlobalScope.launch(Dispatchers.Unconfined) {
            dao.observeByGroup("g_1").collect { emitted += it }
        }
        dao.upsertIfGreater("g_1", "u_a", 1L, 1L)
        dao.upsertIfGreater("g_1", "u_b", 2L, 2L)
        dao.upsertIfGreater("g_2", "u_x", 3L, 3L) // different group, no emit
        job.cancel()
        // 3 emissions: initial empty (replay=1), after u_a, after u_b
        assertEquals(3, emitted.size)
        assertEquals(0, emitted[0].size)
        assertEquals(1, emitted[1].size)
        assertEquals(2, emitted[2].size)
    }
}
```

- [ ] **Step 4: Run - expect compile failure**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.storage.InMemoryGroupReadCursorDaoTest"`

Expected: `BUILD FAILED` with "Unresolved reference: GroupReadCursor" / "InMemoryGroupReadCursorDao".

- [ ] **Step 5: Add the data class to StorageModels.kt**

Open `app/src/main/java/com/buyansong/im/storage/StorageModels.kt`. Find the end of the file (after the last existing data class). Add:

```kotlin
data class GroupReadCursor(
    val groupId: String,
    val readerId: String,
    val readUpToServerSeq: Long,
    val readAt: Long
)
```

- [ ] **Step 6: Create the DAO interface and InMemory impl**

Create `app/src/main/java/com/buyansong/im/storage/GroupReadCursorDao.kt`:

```kotlin
package com.buyansong.im.storage

import kotlinx.coroutines.flow.Flow

interface GroupReadCursorDao {
    fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long): Boolean
    fun findByGroup(groupId: String): List<GroupReadCursor>
    fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>>
}
```

Create `app/src/main/java/com/buyansong/im/storage/InMemoryGroupReadCursorDao.kt`:

```kotlin
package com.buyansong.im.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class InMemoryGroupReadCursorDao : GroupReadCursorDao {
    private data class Key(val groupId: String, val readerId: String)
    private val rows = linkedMapOf<Key, GroupReadCursor>()
    private val groupFlows = mutableMapOf<String, MutableSharedFlow<List<GroupReadCursor>>>()

    @Synchronized
    override fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long): Boolean {
        val key = Key(groupId, readerId)
        val existing = rows[key]
        if (existing != null && existing.readUpToServerSeq >= readUpToServerSeq) {
            return false
        }
        rows[key] = GroupReadCursor(groupId, readerId, readUpToServerSeq, readAt)
        emit(groupId)
        return true
    }

    @Synchronized
    override fun findByGroup(groupId: String): List<GroupReadCursor> {
        return rows.entries
            .filter { it.key.groupId == groupId }
            .map { it.value }
    }

    override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> {
        val flow = groupFlows.getOrPut(groupId) { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }
        return flow.asSharedFlow()
            .onStart { emit(snapshotFor(groupId)) }
            .map { snapshotFor(groupId) }
            .distinctUntilChanged()
    }

    private fun snapshotFor(groupId: String): List<GroupReadCursor> = findByGroup(groupId)

    private fun emit(groupId: String) {
        val flow = groupFlows[groupId] ?: return
        flow.tryEmit(snapshotFor(groupId))
    }
}
```

- [ ] **Step 7: Run - expect green**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.storage.InMemoryGroupReadCursorDaoTest"`

Expected: `BUILD SUCCESSFUL` and `InMemoryGroupReadCursorDaoTest PASSED`.

- [ ] **Step 8: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/storage/StorageModels.kt \
        app/src/main/java/com/buyansong/im/storage/GroupReadCursorDao.kt \
        app/src/main/java/com/buyansong/im/storage/InMemoryGroupReadCursorDao.kt \
        app/src/test/java/com/buyansong/im/storage/InMemoryGroupReadCursorDaoTest.kt \
        app/build.gradle
git commit -m "feat(android): add GroupReadCursor model and in-memory dao"
```

---

## Task 7: ImDatabaseHelper - add group_read_cursors table

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`

- [ ] **Step 1: Read the current DATABASE_VERSION**

Run: `grep -n "DATABASE_VERSION" /home/buyansong/IM/app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`

Expected: one line. Capture the integer. (It is currently `7` per current code; treat the captured value as `OLD_VERSION` and add `1`.)

- [ ] **Step 2: Bump DATABASE_VERSION and add the create method**

In `ImDatabaseHelper.kt`:

1. Bump the `DATABASE_VERSION` constant by 1 (e.g. `7` -> `8`).
2. Add this method next to the other `create*Table` helpers (after `createGroupMembersTable`):

```kotlin
    private fun createGroupReadCursorsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_read_cursors (
              group_id              TEXT    NOT NULL,
              reader_id             TEXT    NOT NULL,
              read_up_to_server_seq INTEGER NOT NULL,
              read_at               INTEGER NOT NULL,
              PRIMARY KEY(group_id, reader_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_read_cursors_group ON group_read_cursors(group_id)"
        )
    }
```

3. In `onCreate(db)`, add at the end of the chain:

```kotlin
        createGroupReadCursorsTable(db)
```

- [ ] **Step 3: Add additive onUpgrade step**

In `onUpgrade`, insert at the very top of the method body (before the existing `DROP TABLE` chain):

```kotlin
        if (oldVersion < DATABASE_VERSION) {
            createGroupReadCursorsTable(db)
        }
```

Important: this MUST run before the existing `DROP TABLE` block, because the drop-then-recreate path will rebuild from `onCreate` and thus also picks up the new table. But for users on `oldVersion = NEW_VERSION - 1` who haven't already been migrated by the drop path, the additive `CREATE TABLE IF NOT EXISTS` is the actual mechanism.

- [ ] **Step 4: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt
git commit -m "feat(android): add group_read_cursors table to local DB"
```

---

## Task 8: AndroidGroupReadCursorDao (SQLite impl)

**Files:**
- Create: `app/src/main/java/com/buyansong/im/storage/AndroidGroupReadCursorDao.kt`
- Create: `app/src/test/java/com/buyansong/im/storage/AndroidGroupReadCursorDaoContractTest.kt`

- [ ] **Step 1: Define a contract the Android impl must satisfy**

The unit test runner cannot construct a real `SQLiteDatabase`, so this contract is run against `InMemoryGroupReadCursorDao` which the spec says the Android impl mirrors. The Android impl is verified in the manual smoke flow in Task 19.

Create `app/src/test/java/com/buyansong/im/storage/AndroidGroupReadCursorDaoContractTest.kt`:

```kotlin
package com.buyansong.im.storage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGroupReadCursorDaoContractTest {
    private fun newDao(): GroupReadCursorDao = InMemoryGroupReadCursorDao()

    @Test
    fun upsertIfGreater_persistsAndIgnoresStale() {
        val dao = newDao()
        assertTrue(dao.upsertIfGreater("g_1", "u_a", 100L, 1_000L))
        assertFalse(dao.upsertIfGreater("g_1", "u_a", 100L, 1_500L))
        assertFalse(dao.upsertIfGreater("g_1", "u_a",  50L, 2_000L))
        assertTrue(dao.upsertIfGreater("g_1", "u_a", 101L, 3_000L))
    }

    @Test
    fun findByGroup_returnsAllRowsForThatGroup() {
        val dao = newDao()
        dao.upsertIfGreater("g_1", "u_a", 1L, 1L)
        dao.upsertIfGreater("g_1", "u_b", 2L, 2L)
        dao.upsertIfGreater("g_2", "u_a", 3L, 3L)
        val rows = dao.findByGroup("g_1")
        assertEquals(2, rows.size)
        assertEquals(setOf("u_a", "u_b"), rows.map { it.readerId }.toSet())
    }

    @Test
    fun observeByGroup_emitsInitialThenChanges() = runBlocking {
        val dao = newDao()
        val emitted = mutableListOf<List<GroupReadCursor>>()
        val job: Job = GlobalScope.launch(Dispatchers.Unconfined) {
            dao.observeByGroup("g_1").collect { emitted += it }
        }
        dao.upsertIfGreater("g_1", "u_a", 1L, 1L)
        dao.upsertIfGreater("g_1", "u_b", 2L, 2L)
        job.cancel()
        assertEquals(3, emitted.size)
        assertEquals(0, emitted[0].size)
        assertEquals(1, emitted[1].size)
        assertEquals(2, emitted[2].size)
    }
}
```

- [ ] **Step 2: Run - expect green (the InMemory impl already works)**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.storage.AndroidGroupReadCursorDaoContractTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Implement AndroidGroupReadCursorDao**

Create `app/src/main/java/com/buyansong/im/storage/AndroidGroupReadCursorDao.kt`:

```kotlin
package com.buyansong.im.storage

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidGroupReadCursorDao(private val database: SQLiteDatabase) : GroupReadCursorDao {

    override fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long): Boolean {
        val existing = readUpToServerSeqOf(groupId, readerId)
        if (existing != null && existing >= readUpToServerSeq) {
            return false
        }
        database.execSQL(
            """
            INSERT INTO group_read_cursors(group_id, reader_id, read_up_to_server_seq, read_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(group_id, reader_id) DO UPDATE SET
              read_up_to_server_seq = excluded.read_up_to_server_seq,
              read_at = excluded.read_at
            """.trimIndent(),
            arrayOf(groupId, readerId, readUpToServerSeq, readAt)
        )
        return true
    }

    override fun findByGroup(groupId: String): List<GroupReadCursor> {
        return database.rawQuery(
            "SELECT group_id, reader_id, read_up_to_server_seq, read_at FROM group_read_cursors WHERE group_id = ?",
            arrayOf(groupId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GroupReadCursor(
                            groupId = cursor.getString(0),
                            readerId = cursor.getString(1),
                            readUpToServerSeq = cursor.getLong(2),
                            readAt = cursor.getLong(3)
                        )
                    )
                }
            }
        }
    }

    override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> = callbackFlow {
        trySend(findByGroup(groupId))
        val listener = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(findByGroup(groupId))
            }
        }
        database.registerContentObserver(
            android.net.Uri.parse("content://im/group_read_cursors/$groupId"),
            true,
            listener
        )
        awaitClose { database.unregisterContentObserver(listener) }
    }

    private fun readUpToServerSeqOf(groupId: String, readerId: String): Long? {
        return database.rawQuery(
            "SELECT read_up_to_server_seq FROM group_read_cursors WHERE group_id = ? AND reader_id = ?",
            arrayOf(groupId, readerId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }
}
```

Note: `ContentObserver` requires writes to go through a `ContentResolver` (e.g. `database.insert(...)`) to trigger. We use `execSQL` here, which does NOT notify the observer. This is acceptable for the manual smoke flow in Task 19 because `ChatViewModel` also re-reads cursors when members/messages are refreshed. If a future task needs live updates, switch to `ContentResolver.insert` or to a polling flow. Document this as a known limitation in the commit message.

- [ ] **Step 4: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/storage/AndroidGroupReadCursorDao.kt \
        app/src/test/java/com/buyansong/im/storage/AndroidGroupReadCursorDaoContractTest.kt
git commit -m "feat(android): add Android SQLite dao for group read cursors"
```

---

## Task 9: GroupReadReceiptPolicy pure functions (TDD)

**Files:**
- Create: `app/src/main/java/com/buyansong/im/chat/GroupReadReceiptPolicy.kt`
- Create: `app/src/test/java/com/buyansong/im/chat/GroupReadReceiptPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/buyansong/im/chat/GroupReadReceiptPolicyTest.kt`:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupReadReceiptPolicyTest {

    private fun msg(
        id: String,
        senderId: String = "u_a",
        status: MessageStatus = MessageStatus.SENT,
        serverSeq: Long? = 1L,
        isRecalled: Boolean = false,
        direction: MessageDirection = MessageDirection.OUTGOING
    ) = ChatMessage(
        messageId = id,
        conversationId = "group:g_1",
        senderId = senderId,
        receiverId = "g_1",
        clientSeq = 0L,
        serverSeq = serverSeq,
        content = "x",
        status = status,
        direction = direction,
        createdAt = 0L,
        updatedAt = 0L,
        type = MessageType.TEXT,
        conversationType = ConversationType.GROUP
    ).let { if (isRecalled) it.copy(isRecalled = true) else it }

    private fun member(userId: String, name: String = userId) = GroupMember(
        groupId = "g_1", userId = userId, displayName = name, avatarUrl = null,
        role = GroupMemberRole.MEMBER, joinedAt = 0L, updatedAt = 0L
    )

    private fun cursor(readerId: String, readUpTo: Long, readAt: Long) =
        GroupReadCursor("g_1", readerId, readUpTo, readAt)

    @Test
    fun latestEligible_picksFirstSentOutgoingOwnMessageNewestFirst() {
        val messages = listOf(
            msg("m1", status = MessageStatus.SENDING, serverSeq = null),
            msg("m2", status = MessageStatus.SENT, serverSeq = 1L),
            msg("m3", status = MessageStatus.SENT, serverSeq = 2L, isRecalled = true),
            msg("m4", senderId = "u_b")
        )
        assertEquals("m2", GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a"))
    }

    @Test
    fun latestEligible_skipsRecalledAndNullServerSeq() {
        val messages = listOf(
            msg("m1", status = MessageStatus.SENT, serverSeq = 1L, isRecalled = true),
            msg("m2", status = MessageStatus.SENT, serverSeq = null),
            msg("m3", status = MessageStatus.SENDING),
            msg("m4", status = MessageStatus.FAILED)
        )
        assertNull(GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a"))
    }

    @Test
    fun latestEligible_returnsNullForEmptyList() {
        assertNull(GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(emptyList(), "u_a"))
    }

    @Test
    fun latestEligible_onlyCountsOutgoingOwnSender() {
        val messages = listOf(
            msg("m1", senderId = "u_b", direction = MessageDirection.INCOMING)
        )
        assertNull(GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a"))
    }

    @Test
    fun readersOf_excludesSenderEvenIfCursorExists() {
        val members = listOf(member("u_a"), member("u_b"), member("u_c"))
        val cursors = listOf(
            cursor("u_a", 100L, 5_000L), // sender self-read - must be excluded
            cursor("u_b", 100L, 1_000L),
            cursor("u_c",  50L, 2_000L)  // has not reached serverSeq
        )
        val readers = GroupReadReceiptPolicy.readersOf("u_a", 100L, cursors, members)
        assertEquals(listOf("u_b"), readers.map { it.userId })
    }

    @Test
    fun readersOf_sortsByReadAtDescending() {
        val members = listOf(member("u_b"), member("u_c"), member("u_d"))
        val cursors = listOf(
            cursor("u_b", 100L, 1_000L),
            cursor("u_c", 100L, 3_000L),
            cursor("u_d", 100L, 2_000L)
        )
        val readers = GroupReadReceiptPolicy.readersOf("u_a", 100L, cursors, members)
        assertEquals(listOf("u_c", "u_d", "u_b"), readers.map { it.userId })
    }

    @Test
    fun readersOf_returnsEmptyWhenServerSeqNull() {
        val members = listOf(member("u_b"))
        val cursors = listOf(cursor("u_b", 100L, 1L))
        assertEquals(emptyList<GroupMember>(), GroupReadReceiptPolicy.readersOf("u_a", null, cursors, members))
    }

    @Test
    fun readersOf_memberWithoutCursorIsOmitted() {
        val members = listOf(member("u_b"), member("u_c"))
        val cursors = listOf(cursor("u_b", 100L, 1L))
        val readers = GroupReadReceiptPolicy.readersOf("u_a", 100L, cursors, members)
        assertEquals(listOf("u_b"), readers.map { it.userId })
    }
}
```

- [ ] **Step 2: Run - expect compile failure**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.chat.GroupReadReceiptPolicyTest"`

Expected: `BUILD FAILED` with "Unresolved reference: GroupReadReceiptPolicy".

- [ ] **Step 3: Implement the policy**

Create `app/src/main/java/com/buyansong/im/chat/GroupReadReceiptPolicy.kt`:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus

object GroupReadReceiptPolicy {

    fun latestEligibleOwnSentMessageId(
        messages: List<ChatMessage>,
        currentUserId: String
    ): String? = messages.firstOrNull { m ->
        m.senderId == currentUserId &&
            m.direction == MessageDirection.OUTGOING &&
            m.status == MessageStatus.SENT &&
            m.serverSeq != null &&
            !m.isRecalled
    }?.messageId

    fun readersOf(
        messageSenderId: String,
        messageServerSeq: Long?,
        cursors: List<GroupReadCursor>,
        members: List<GroupMember>
    ): List<GroupMember> {
        if (messageServerSeq == null) return emptyList()
        val cursorByUser = cursors.associateBy { it.readerId }
        return members
            .filter { it.userId != messageSenderId }
            .mapNotNull { member -> cursorByUser[member.userId]?.let { member to it } }
            .filter { (_, cursor) -> cursor.readUpToServerSeq >= messageServerSeq }
            .sortedByDescending { (_, cursor) -> cursor.readAt }
            .map { (member, _) -> member }
    }
}
```

- [ ] **Step 4: Run - expect green**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.chat.GroupReadReceiptPolicyTest"`

Expected: `BUILD SUCCESSFUL` and 8 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/chat/GroupReadReceiptPolicy.kt \
        app/src/test/java/com/buyansong/im/chat/GroupReadReceiptPolicyTest.kt
git commit -m "feat(android): add GroupReadReceiptPolicy pure functions"
```

---

## Task 10: GroupReadCursorRepository (query + notify facade)

**Files:**
- Create: `app/src/main/java/com/buyansong/im/group/GroupReadCursorRepository.kt`

- [ ] **Step 1: Create the repository**

Create `app/src/main/java/com/buyansong/im/group/GroupReadCursorRepository.kt`:

```kotlin
package com.buyansong.im.group

import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.GroupReadCursorDao
import kotlinx.coroutines.flow.Flow

interface GroupReadCursorRepository {
    suspend fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long)
    fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>>
}

class DefaultGroupReadCursorRepository(
    private val dao: GroupReadCursorDao
) : GroupReadCursorRepository {
    override suspend fun upsertIfGreater(groupId: String, readerId: String, readUpToServerSeq: Long, readAt: Long) {
        dao.upsertIfGreater(groupId, readerId, readUpToServerSeq, readAt)
    }

    override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> =
        dao.observeByGroup(groupId)
}
```

- [ ] **Step 2: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/group/GroupReadCursorRepository.kt
git commit -m "feat(android): add GroupReadCursorRepository facade"
```

---

## Task 11: MessageRepository - `sendGroupReadAck`, `applyIncomingGroupReadAck`, `observeGroupReadCursors`, route READ_ACK (TDD)

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Create: `app/src/test/java/com/buyansong/im/message/MessageRepositoryGroupReadAckTest.kt`

- [ ] **Step 1: Read the existing handleReadAck and ctor**

Run: `grep -n "handleReadAck\|class MessageRepository\|lastSentReadCursorByConversation\|private val scope" /home/buyansong/IM/app/src/main/java/com/buyansong/im/message/MessageRepository.kt | head -20`

Expected: `handleReadAck` is at line 793; `lastSentReadCursorByConversation` is a `ConcurrentHashMap<String, Long>` near `sendReadAckIfAdvanced`; the class uses val ctor parameters.

- [ ] **Step 2: Add a per-group last-sent map and a ctor parameter**

In `MessageRepository.kt`:

1. Add a new private field near the existing `lastSentReadCursorByConversation`:

```kotlin
    private val lastSentGroupReadCursorByGroup = java.util.concurrent.ConcurrentHashMap<String, Long>()
```

2. Add a constructor parameter at the end of the primary ctor parameter list (after `thumbnailDownloadScheduler`):

```kotlin
    private val groupReadCursorRepository: com.buyansong.im.group.GroupReadCursorRepository? = null,
```

Default `null` preserves all existing callers. Group read receipt is opt-in until Task 12 wires it.

- [ ] **Step 3: Add the new public methods + send-on-open hook**

After the existing `sendReadAckIfAdvanced` method, add:

```kotlin
    fun sendGroupReadAck(groupId: String, readerId: String, now: Long = System.currentTimeMillis()) {
        if (groupReadCursorRepository == null) return
        val conversationId = "group:$groupId"
        val readUpTo = messageDao.maxIncomingServerSeq(conversationId) ?: return
        val previous = lastSentGroupReadCursorByGroup[groupId]
        if (previous != null && readUpTo <= previous) {
            return
        }
        lastSentGroupReadCursorByGroup[groupId] = readUpTo
        connection.send(
            ImPacket(
                cmd = ImCommand.READ_ACK.value,
                body = """
                    {
                      "conversationId":"${conversationId.escapeJson()}",
                      "conversationType":"GROUP",
                      "readerId":"${readerId.escapeJson()}",
                      "readUpToServerSeq":$readUpTo,
                      "readAt":$now
                    }
                """.trimIndent().replace(Regex("\\s+"), "").toByteArray()
            )
        )
    }

    suspend fun applyIncomingGroupReadAck(
        groupId: String,
        readerId: String,
        readUpToServerSeq: Long,
        readAt: Long
    ) {
        groupReadCursorRepository?.upsertIfGreater(groupId, readerId, readUpToServerSeq, readAt)
    }

    fun observeGroupReadCursors(groupId: String): kotlinx.coroutines.flow.Flow<List<com.buyansong.im.storage.GroupReadCursor>> {
        val repository = groupReadCursorRepository
            ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return repository.observeByGroup(groupId)
    }
```

Then in `openConversationById` (currently at line ~586), add a sibling branch right after the existing `sendReadAckIfAdvanced(...)` block:

```kotlin
        if (conversationId.startsWith("group:")) {
            val groupId = conversationId.removePrefix("group:")
            sendGroupReadAck(groupId = groupId, readerId = currentUserId, now = now)
        }
```

(Leave the existing `if (conversationId.startsWith("single:") && peerId != null) { sendReadAckIfAdvanced(...) }` untouched - it covers the SINGLE path.)

- [ ] **Step 4: Refactor handleReadAck to route by conversationType**

The current `handleReadAck` (at `MessageRepository.kt:793`) is:

```kotlin
    private fun handleReadAck(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        val conversationId = body.requiredString("conversationId")
        val readUpToServerSeq = body.requiredLong("readUpToServerSeq")
        val readAt = body.optionalLong("readAt") ?: System.currentTimeMillis()
        val changed = conversationDao.updatePeerReadCursor(conversationId, readUpToServerSeq, readAt)
        if (changed) {
            notifyConversationChanged()
        }
    }
```

Replace it with this routed version (preserves the SINGLE path exactly, adds a GROUP branch):

```kotlin
    private fun handleReadAck(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        val conversationType = body.optionalString("conversationType")
            ?.takeIf { it.isNotBlank() }
        if (conversationType == "GROUP") {
            val conversationId = body.requiredString("conversationId")
            val groupId = conversationId.removePrefix("group:")
            val readerId = body.requiredString("readerId")
            val readUpToServerSeq = body.requiredLong("readUpToServerSeq")
            val readAt = body.optionalLong("readAt") ?: System.currentTimeMillis()
            if (scope != null) {
                scope.launch {
                    applyIncomingGroupReadAck(groupId, readerId, readUpToServerSeq, readAt)
                }
            } else {
                kotlinx.coroutines.runBlocking {
                    applyIncomingGroupReadAck(groupId, readerId, readUpToServerSeq, readAt)
                }
            }
            return
        }
        val conversationId = body.requiredString("conversationId")
        val readUpToServerSeq = body.requiredLong("readUpToServerSeq")
        val readAt = body.optionalLong("readAt") ?: System.currentTimeMillis()
        val changed = conversationDao.updatePeerReadCursor(conversationId, readUpToServerSeq, readAt)
        if (changed) {
            notifyConversationChanged()
        }
    }
```

Note: `scope` may or may not exist on `MessageRepository`. Verify by running:

```bash
grep -n "private val scope\|private val scope:" /home/buyansong/IM/app/src/main/java/com/buyansong/im/message/MessageRepository.kt
```

If a `scope: CoroutineScope` field is found, the `if (scope != null) scope.launch {...} else runBlocking {...}` block above is what to use. If no `scope` field exists, simplify to just `runBlocking { ... }` (single UPSERT, fast, safe under the single-consumer `MessagePacketProcessor`).

- [ ] **Step 5: Write the failing test**

Create `app/src/test/java/com/buyansong/im/message/MessageRepositoryGroupReadAckTest.kt`:

```kotlin
package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.group.DefaultGroupReadCursorRepository
import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryGroupReadCursorDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryGroupReadAckTest {

    private class FakeConnection : ImConnection {
        val sent = mutableListOf<ImPacket>()
        override fun start() {}
        override fun stop() {}
        override fun disconnect() {}
        override fun send(packet: ImPacket) { sent += packet }
        override val incomingPackets: Flow<ImPacket> = emptyFlow()
        override val state: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
    }

    private fun repo(): Triple<MessageRepository, FakeConnection, InMemoryGroupReadCursorDao> {
        val conn = FakeConnection()
        val cursorDao = InMemoryGroupReadCursorDao()
        val repo = MessageRepository(
            messageDao = InMemoryMessageDao(),
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = conn,
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator(),
            transactionRunner = object : TransactionRunner {
                override fun <T> runInTransaction(block: () -> T): T = block()
            },
            groupReadCursorRepository = DefaultGroupReadCursorRepository(cursorDao)
        )
        return Triple(repo, conn, cursorDao)
    }

    @Test
    fun applyIncomingGroupReadAck_isMonotonic() = runBlocking {
        val (r, _, dao) = repo()
        r.applyIncomingGroupReadAck("g_1", "u_b", 100L, 1_000L)
        r.applyIncomingGroupReadAck("g_1", "u_b",  50L, 2_000L) // stale
        r.applyIncomingGroupReadAck("g_1", "u_b", 100L, 3_000L) // equal
        r.applyIncomingGroupReadAck("g_1", "u_b", 200L, 4_000L) // advances
        val rows = dao.findByGroup("g_1")
        assertEquals(1, rows.size)
        assertEquals(200L, rows.single().readUpToServerSeq)
        assertEquals(4_000L, rows.single().readAt)
    }

    @Test
    fun sendGroupReadAck_isMonotonicAndCarriesGroupMarker() {
        val (r, conn, _) = repo()
        r.sendGroupReadAck("g_1", "u_a", now = 1L)
        r.sendGroupReadAck("g_1", "u_a", now = 2L)
        assertEquals(1, conn.sent.size)
        val p = conn.sent.single()
        assertEquals(ImCommand.READ_ACK.value, p.cmd)
        val body = p.body.decodeToString()
        assertTrue("missing GROUP marker: $body", body.contains("\"conversationType\":\"GROUP\""))
    }
}
```

- [ ] **Step 6: Run - expect green (after fixing any stub gaps)**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.message.MessageRepositoryGroupReadAckTest"`

Expected: `BUILD SUCCESSFUL` and 2 tests pass.

If `InMemoryMessageDao`, `InMemoryPendingMessageDao`, or `InMemoryConversationDao` cannot be found in the constructor signature (Kotlin will complain about argument type mismatch because the DAOs may have specific generics), verify their declarations:

```bash
grep -n "^class InMemoryMessageDao\|^class InMemoryPendingMessageDao\|^class InMemoryConversationDao" \
  /home/buyansong/IM/app/src/main/java/com/buyansong/im/storage/MessageDao.kt \
  /home/buyansong/IM/app/src/main/java/com/buyansong/im/storage/PendingMessageDao.kt \
  /home/buyansong/IM/app/src/main/java/com/buyansong/im/storage/ConversationDao.kt
```

All three are declared as `class InMemoryXxxDao : XxxDao` in their respective files. They should construct with no arguments.

- [ ] **Step 7: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/message/MessageRepository.kt \
        app/src/test/java/com/buyansong/im/message/MessageRepositoryGroupReadAckTest.kt
git commit -m "feat(android): route READ_ACK by conversationType and add group ack"
```

---

## Task 12: MessagePacketProcessor routing is already done (no-op)

**Files:** none (verified by Task 11 + spec)

- [ ] **Step 1: Verify the routing is centralized in MessageRepository**

Run: `grep -n "handleReadAck\|handlePacket" /home/buyansong/IM/app/src/main/java/com/buyansong/im/message/MessageRepository.kt`

Expected: `handlePacket` at line 383 calls `handleReadAck`. All `READ_ACK` handling flows through `MessageRepository.handleReadAck`, which is the only consumer of incoming WebSocket packets (per the spec's "single consumer" rule). No code change needed.

- [ ] **Step 2: Run repo tests to confirm**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.message.MessageRepositoryGroupReadAckTest"`

Expected: `BUILD SUCCESSFUL`.

---

## Task 13: Wire `GroupReadCursorDao` into `MessageRepository` via `AccountScopedRepositories`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt` (inside `AccountScopedRepositories.create`)

- [ ] **Step 1: Find the existing `messageRepository` construction**

Run: `grep -n "messageRepository = MessageRepository\|AndroidMessageDao\|AndroidGroupDao" /home/buyansong/IM/app/src/main/java/com/buyansong/im/MainActivity.kt`

Expected: a clear anchor around the `MessageRepository(...)` ctor.

- [ ] **Step 2: Construct the dao and pass it in**

Just before the `messageRepository = MessageRepository(...)` call, add:

```kotlin
            val groupReadCursorRepository = DefaultGroupReadCursorRepository(
                dao = AndroidGroupReadCursorDao(database)
            )
```

Then in the `MessageRepository(...)` ctor call, add the named argument at the end:

```kotlin
                groupReadCursorRepository = groupReadCursorRepository
```

- [ ] **Step 3: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/MainActivity.kt
git commit -m "feat(android): wire GroupReadCursorDao into MessageRepository"
```

---

## Task 14: ChatViewModel - observe cursor flow + compute indicator (TDD)

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`
- Create: `app/src/test/java/com/buyansong/im/chat/ChatViewModelGroupReadReceiptTest.kt`

- [ ] **Step 1: Read the existing `ChatUiState` and the launch blocks in `start()`**

Run: `grep -n "ChatUiState\|scope.launch\|repository.conversationUpdates\|refreshKeepingHistory\|refreshInitialPage\|groupRepository\|localMembers" /home/buyansong/IM/app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt | head -25`

Expected: `data class ChatUiState(...)` near the top; `start()` calls `scope.launch` for `conversationUpdates` and `refreshInitialPage`; the VM uses a `MutableStateFlow<ChatUiState>` called `mutableState`. The `groupRepository: GroupRepository` constructor field is referenced via `groupRepository.localMembers(...)` somewhere.

- [ ] **Step 2: Add three new fields to ChatUiState**

Open `ChatViewModel.kt` and add at the end of the `ChatUiState` data class:

```kotlin
    val latestOwnSentMessageId: String? = null,
    val groupReadCountForLatest: Int = 0,
    val groupReadersForLatest: List<com.buyansong.im.storage.GroupMember> = emptyList()
```

- [ ] **Step 3: Add a new `scope.launch` that observes the cursor flow and recomputes the indicator**

In `start()` (or a new `private fun startGroupReadObservation()` called from `start()`), add a new launch block that runs while the user is on a group chat. The launch subscribes to `observeGroupReadCursors(groupId)`; on every emission it reads the current messages and members from `mutableState.value` (re-reading from DB if you want freshness) and writes back the three new fields via `mutableState.update { ... }`. A minimal correct version:

```kotlin
    private fun startGroupReadObservation() {
        val targetId = mutableState.value.peerId
        if (!targetId.isGroupConversationId()) return
        val groupId = targetId.removePrefix("group:")
        jobs += scope.launch(dispatcher) {
            messageRepository.observeGroupReadCursors(groupId).collect { cursors ->
                val current = mutableState.value
                if (current.peerId != targetId) return@collect
                val members = current.groupMembers // assume ChatUiState.groupMembers is a List<GroupMember>; if not, fetch via groupRepository.localMembers(groupId)
                val latestOwnId = GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(current.messages, current.currentUserId)
                val latestOwn = current.messages.firstOrNull { it.messageId == latestOwnId }
                val readers = latestOwn?.let {
                    GroupReadReceiptPolicy.readersOf(
                        messageSenderId = it.senderId,
                        messageServerSeq = it.serverSeq,
                        cursors = cursors,
                        members = members
                    )
                } ?: emptyList()
                mutableState.value = current.copy(
                    latestOwnSentMessageId = latestOwnId,
                    groupReadCountForLatest = readers.size,
                    groupReadersForLatest = readers
                )
            }
        }
    }
```

`startGroupReadObservation` MUST be called from `start()`. Insert the call inside `start()` right after the existing `if (mutableState.value.peerId.isNotBlank()) { openCurrentConversation() }` block.

The above snippet assumes `ChatUiState` exposes `messages: List<ChatMessage>`, `currentUserId: String`, and `groupMembers: List<GroupMember>`. If any of these fields do not exist on `ChatUiState` (the current code may not have `groupMembers`), add them as part of this task:

- `val messages: List<ChatMessage> = emptyList()` - populated by the existing page-load path.
- `val currentUserId: String = ""` - set to `session.userId` in the VM's init / `start`.
- `val groupMembers: List<GroupMember> = emptyList()` - populated by an existing `groupRepository.localMembers(groupId)` call (find where members are loaded and add it; if not loaded, add a `groupRepository.syncMembers(...)` call from the new launch block on first emission).

If pulling members from the repo requires a suspend call, do that in a `withContext` block and refresh the state, but keep the cursor flow's hot path non-blocking.

- [ ] **Step 4: Send READ_ACK on new incoming message**

The spec says "停留群聊页期间收到新 incoming 消息后" - re-send READ_ACK after receiving new incoming messages while the chat is open. The simplest place is inside the existing `scope.launch { repository.conversationUpdates.collect { ... } }` block in `start()`. After the existing `refreshKeepingHistory()` call, add:

```kotlin
                if (mutableState.value.peerId.isGroupConversationId()) {
                    val gid = mutableState.value.peerId.removePrefix("group:")
                    messageRepository.sendGroupReadAck(groupId = gid, readerId = session.userId)
                }
```

`sendGroupReadAck` has its own monotonic de-dup, so over-firing on every conversation update is safe.

- [ ] **Step 5: Write the failing test**

The test asserts that the policy functions produce the right output and that `MessageRepository.sendGroupReadAck` is called from the right place. Keep this test small and focused on the policy wiring - exhaustive behavioral coverage is in Task 19 (manual smoke flow).

Create `app/src/test/java/com/buyansong/im/chat/ChatViewModelGroupReadReceiptTest.kt`:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import com.buyansong.im.storage.GroupReadCursor
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelGroupReadReceiptTest {

    private fun member(userId: String) = GroupMember(
        groupId = "g_1", userId = userId, displayName = userId, avatarUrl = null,
        role = GroupMemberRole.MEMBER, joinedAt = 0L, updatedAt = 0L
    )

    private fun msg(id: String, senderId: String, serverSeq: Long?) = ChatMessage(
        messageId = id, conversationId = "group:g_1", senderId = senderId, receiverId = "g_1",
        clientSeq = 0L, serverSeq = serverSeq, content = "x", status = MessageStatus.SENT,
        direction = MessageDirection.OUTGOING, createdAt = 0L, updatedAt = 0L,
        type = MessageType.TEXT, conversationType = ConversationType.GROUP
    )

    @Test
    fun policy_drivesIndicatorStateForCurrentUser() {
        val messages = listOf(
            msg("m1", "u_a", 1L),
            msg("m2", "u_a", 2L)
        )
        val cursors = listOf(
            GroupReadCursor("g_1", "u_b", 2L, 100L),
            GroupReadCursor("g_1", "u_c", 1L, 200L)
        )
        val members = listOf(member("u_a"), member("u_b"), member("u_c"))

        val latestOwnId = GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(messages, "u_a")
        val latestOwn = messages.firstOrNull { it.messageId == latestOwnId }
        val readers = GroupReadReceiptPolicy.readersOf(
            messageSenderId = latestOwn!!.senderId,
            messageServerSeq = latestOwn.serverSeq,
            cursors = cursors,
            members = members
        )
        assertEquals("m2", latestOwnId)
        assertEquals(2, readers.size)
        // u_c readAt=200 comes before u_b readAt=100
        assertEquals(listOf("u_c", "u_b"), readers.map { it.userId })
    }
}
```

- [ ] **Step 6: Run - expect green**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.chat.ChatViewModelGroupReadReceiptTest"`

Expected: `BUILD SUCCESSFUL` and 1 test passes.

- [ ] **Step 7: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt \
        app/src/test/java/com/buyansong/im/chat/ChatViewModelGroupReadReceiptTest.kt
git commit -m "feat(android): observe group read cursors and drive indicator in ChatViewModel"
```

---

## Task 15: GroupReadIndicator composable

**Files:**
- Create: `app/src/main/java/com/buyansong/im/chat/GroupReadIndicator.kt`

- [ ] **Step 1: Read existing color tokens**

Run: `grep -n "PrimaryGreen\|TextSecondary\|TextTertiary" /home/buyansong/IM/app/src/main/java/com/buyansong/im/ui/ByteImUi.kt`

Expected: `PrimaryGreen` and `TextSecondary` are defined. `TextTertiary` does NOT exist - we will use `TextSecondary`.

- [ ] **Step 2: Create the composable**

Create `app/src/main/java/com/buyansong/im/chat/GroupReadIndicator.kt`:

```kotlin
package com.buyansong.im.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buyansong.im.ui.ByteImColors

@Composable
fun GroupReadIndicator(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .clickable(onClick = onClick)
            .padding(top = 4.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$count 人已读",
                style = TextStyle(fontSize = 12.sp, color = ByteImColors.PrimaryGreen, fontWeight = FontWeight.Normal)
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = ByteImColors.TextSecondary,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/chat/GroupReadIndicator.kt
git commit -m "feat(android): add GroupReadIndicator composable"
```

---

## Task 16: GroupReadDetailSheet composable

**Files:**
- Create: `app/src/main/java/com/buyansong/im/chat/GroupReadDetailSheet.kt`

- [ ] **Step 1: Find an existing AvatarImage usage to mirror**

Run: `grep -rn "AvatarImage(" /home/buyansong/IM/app/src/main/java/com/buyansong/im/chat/ | head -5`

Expected: at least one usage in `ChatInfoScreen` or `GroupInfoScreen`.

- [ ] **Step 2: Create the composable**

Create `app/src/main/java/com/buyansong/im/chat/GroupReadDetailSheet.kt`:

```kotlin
package com.buyansong.im.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupReadDetailSheet(
    readers: List<GroupMember>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "已读 ${readers.size} 人",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ByteImColors.TextPrimary)
                )
            }
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(readers, key = { it.userId }) { reader ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarImage(
                            url = reader.avatarUrl,
                            fallbackText = reader.userId.take(1),
                            sizeDp = 40
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = reader.displayName.ifBlank { reader.userId },
                            style = TextStyle(fontSize = 16.sp, color = ByteImColors.TextPrimary)
                        )
                    }
                }
            }
        }
    }
}
```

Verify the `AvatarImage` API signature matches: `grep -n "fun AvatarImage" /home/buyansong/IM/app/src/main/java/com/buyansong/im/ui/AvatarImage.kt`. If its parameter names differ (e.g. `name` vs `fallbackText`), adapt the call.

- [ ] **Step 3: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/chat/GroupReadDetailSheet.kt
git commit -m "feat(android): add GroupReadDetailSheet composable"
```

---

## Task 17: ChatScreen integration

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`

- [ ] **Step 1: Read the outgoing bubble rendering section**

Run: `grep -n "isOutgoing\|MessageDirection.OUTGOING\|outgoing bubble\|state.messages\|ChatBubbleRow\|isGroup\|state.isGroup" /home/buyansong/IM/app/src/main/java/com/buyansong/im/chat/ChatScreen.kt | head -20`

Expected: a row-level composable that renders the bubble. Identify the `LaunchedEffect`/`remember` blocks for sheet state.

- [ ] **Step 2: Add state and import**

Add imports at the top of `ChatScreen.kt`:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

Inside the screen's top-level composable, add:

```kotlin
    var showGroupReadSheet by remember { mutableStateOf(false) }
```

- [ ] **Step 3: Render the indicator under the latest-own-sent bubble**

Find the spot where the outgoing bubble row is rendered. After that row, add:

```kotlin
        if (state.isGroup && message.messageId == state.latestOwnSentMessageId && state.groupReadCountForLatest > 0) {
            GroupReadIndicator(
                count = state.groupReadCountForLatest,
                onClick = { showGroupReadSheet = true }
            )
        }
```

`state.isGroup` may already exist on `ChatUiState`; verify with `grep -n "isGroup" /home/buyansong/IM/app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`. If it does not, derive it from `state.peerId.startsWith("group:")` inline, or add `val isGroup: Boolean = false` to `ChatUiState` and populate it in the new launch block (Task 14).

- [ ] **Step 4: Render the bottom sheet at the screen root**

At the screen's root, after the existing `Box { ... }` content, add:

```kotlin
    if (showGroupReadSheet) {
        GroupReadDetailSheet(
            readers = state.groupReadersForLatest,
            onDismiss = { showGroupReadSheet = false }
        )
    }
```

- [ ] **Step 5: Compile**

Run: `cd /home/buyansong/IM && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
cd /home/buyansong/IM
git add app/src/main/java/com/buyansong/im/chat/ChatScreen.kt
git commit -m "feat(android): render GroupReadIndicator and detail sheet in ChatScreen"
```

---

## Task 18: Documentation updates

**Files:**
- Modify: `docs/status/B12-message-recall-and-read-receipts.md`
- Modify: `docs/bg/DEVELOPMENT_STATUS.md`

- [ ] **Step 1: Add "Group Read Receipts" subsection to B12 status doc**

Run: `tail -20 /home/buyansong/IM/docs/status/B12-message-recall-and-read-receipts.md`

Append:

```markdown

## Group Read Receipts (B12-G)

Implemented 2026-06-09. The send-only "X 人已读 >" indicator now appears below the latest
SENT message in a group chat, with a `ModalBottomSheet` listing the readers.

Protocol: `READ_ACK` (cmd=13) now carries an optional `conversationType` field; `GROUP` routes
through a new `group_read_cursors` table on both the mock-server and the Android client.
Server replays the user's cursors on auth. See
`docs/superpowers/specs/2026-06-09-group-read-receipts-design.md` for full design notes.

Group read-member state (per-member read times) remains deferred unless requested - we show
"X 人已读" counts and a member list, but no per-member timestamps.
```

- [ ] **Step 2: Flip the "out of scope" line**

Run: `grep -n "Group read-member state remains out of scope" /home/buyansong/IM/docs/status/B12-message-recall-and-read-receipts.md`

Replace that line with:

```markdown
Group read-member state (per-member read timestamps) is still deferred. Group read indicator
implemented; see "Group Read Receipts" subsection above.
```

- [ ] **Step 3: Update DEVELOPMENT_STATUS.md**

Run: `grep -n "B12\|单聊已读\|群聊已读" /home/buyansong/IM/docs/bg/DEVELOPMENT_STATUS.md`

Change the row's text to include both single and group read receipts.

- [ ] **Step 4: Verify all doc updates**

Run: `grep -n "Group Read Receipts\|群聊已读" /home/buyansong/IM/docs/bg/DEVELOPMENT_STATUS.md /home/buyansong/IM/docs/status/B12-message-recall-and-read-receipts.md`

Expected: at least one match in each file.

- [ ] **Step 5: Commit**

```bash
cd /home/buyansong/IM
git add docs/status/B12-message-recall-and-read-receipts.md docs/bg/DEVELOPMENT_STATUS.md
git commit -m "docs: record group read receipts as implemented (B12-G)"
```

---

## Task 19: Build the whole app + mock-server, then run all unit tests

**Files:** none

- [ ] **Step 1: Build the Android app**

Run: `cd /home/buyansong/IM && ./gradlew :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run all Android unit tests**

Run: `cd /home/buyansong/IM && ./gradlew :app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 3: Run all mock-server tests**

Run: `cd /home/buyansong/IM/mock-server && mvn -q test`

Expected: `BUILD SUCCESS`, all tests pass (the new InMemoryGroupReadCursorStoreTest, SQLiteGroupReadCursorStoreTest, MessageRouterGroupReadAckTest, SessionRegistryReplayTest, plus the existing FixedSaltGenerator test).

- [ ] **Step 4: Report pass/fail**

If any test fails, fix the failure before proceeding. Do not declare the feature done with red tests.

---

## Task 20: Manual smoke verification (per spec section on manual verification)

**Files:** none

- [ ] **Step 1: Start the mock-server**

Run: `cd /home/buyansong/IM/mock-server && mvn -q exec:java`

Expected: server starts on the configured port.

- [ ] **Step 2: Install the Android app on three devices (or emulators)**

Use the spec's exact 3-device script (T0..T10) from
`docs/superpowers/specs/2026-06-09-group-read-receipts-design.md` (manual verification section). For each step, record the observed state.

- [ ] **Step 3: Capture screenshots of the indicator and the sheet**

For the PR description / status doc, capture two screenshots:
1. A group chat with the "2 人已读 >" indicator visible.
2. The bottom sheet open, showing the two readers.

- [ ] **Step 4: Update the status doc with a "Verified" annotation**

Open `docs/status/B12-message-recall-and-read-receipts.md` and add a one-line timestamped note under the new "Group Read Receipts" subsection:

```markdown
Verified manually with 3 devices on 2026-06-09.
```

- [ ] **Step 5: Commit (if any doc changes)**

```bash
cd /home/buyansong/IM
git add docs/status/B12-message-recall-and-read-receipts.md
git commit -m "docs: record manual verification of group read receipts"
```

---

## Self-Review (informational, not a task)

After all tasks complete, the engineer should re-read the spec and check:

1. **Spec coverage:**
   - US-1 (sender sees count) -> Tasks 14, 17.
   - US-2 (no self-count) -> Task 9 (`readersOf_excludesSenderEvenIfCursorExists`).
   - US-3 (only latest) -> Task 9 (`latestEligible_picksFirst...`).
   - US-4 (SENDING/FAILED) -> Task 9 (`latestEligible_skipsRecalledAndNullServerSeq`).
   - US-5 (recall retreat) -> Task 9 (recalled messages are skipped); Tasks 14, 17.
   - US-6 (sender-only) -> Tasks 14, 17 (state-driven by `state.latestOwnSentMessageId` which is per-currentUser).
   - US-7 (offline replay) -> Task 5 (server replays cursors on auth).
   - US-8 (sheet) -> Task 16.
   - Acceptance 1-9 (display rules) -> Tasks 9, 14, 17.
   - Acceptance 10-17 (sheet) -> Tasks 15, 16, 17.
   - Acceptance 18 (restart) -> Task 6 (in-memory dao + Task 7 DB migration), Task 11 (apply on receive), Task 5 (server replays).
   - Acceptance 19 (offline B reads while A offline) -> Task 5 (server persists cursor + replays on A's reconnect).

2. **Placeholder scan:** No "TODO", no "implement later", no "fill in details". The "If compilation fails" notes in Task 11 Step 6 and Task 14 Step 3 are escape hatches for stub mismatches, not placeholder content.

3. **Type consistency:**
   - `GroupReadCursor` field names match across mock-server (`groupId`, `readerId`, `readUpToServerSeq`, `readAt`) and Android (`groupId`, `readerId`, `readUpToServerSeq`, `readAt`).
   - `GroupReadCursorDao.upsertIfGreater` and `GroupReadCursorStore.upsertIfGreater` have identical signatures.
   - `MessageRouter.replayGroupReadCursorsFor` writes a `conversationType=GROUP` body, matching what the Android client expects in `MessageRepository.handleReadAck`.
   - `GroupReadReceiptPolicy.readersOf` parameter `messageSenderId` is the message's sender (not the reader), and the impl excludes `it.userId != messageSenderId`. Confirmed.

---

**Plan complete and saved to `docs/superpowers/plans/2026-06-09-group-read-receipts.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints.
