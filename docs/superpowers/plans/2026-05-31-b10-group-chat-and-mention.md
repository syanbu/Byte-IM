# B10 Group Chat and Mention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first real B10 slice: target-aware chat routing, group conversation storage, group text send/receive, and @ me unread metadata while preserving existing single-chat behavior.

**Architecture:** Keep one `messages` and one `conversations` surface, but add `ConversationType`, group ids, and mention metadata. Android will trust packet `conversationId` for group messages and keep single-chat wrappers for compatibility. Mock-server group HTTP and group fanout follow after Android storage/repository tests are green.

**Tech Stack:** Kotlin, Jetpack Compose, SQLiteOpenHelper, Coroutines/Flow, OkHttp WebSocket, Java/Netty mock-server, JUnit.

---

### Task 1: Android Conversation Type And Route Foundation

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/StorageModels.kt`
- Modify: `app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt`
- Test: `app/src/test/java/com/buyansong/im/SelfHostedImRouteTest.kt`

- [ ] **Step 1: Write failing route tests**

Add tests asserting `Chat.createRoute("single:u1:u2") == "chat/single%3Au1%3Au2"` or equivalent encoded route, group ids are accepted, and blank ids are rejected.

- [ ] **Step 2: Run route tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.SelfHostedImRouteTest --console=plain
```

Expected: failure because `Chat` still uses `peerUserId` naming and does not expose conversation-id helpers.

- [ ] **Step 3: Implement minimal route/model changes**

Add `ConversationType`, add group-aware fields to `ChatMessage` and `Conversation`, and update `SelfHostedImRoute.Chat` to carry `conversationId` while preserving wrapper behavior for existing callers.

- [ ] **Step 4: Run route tests**

Run the same command. Expected: pass.

### Task 2: Conversation DAO Mention Count

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/ConversationDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/AndroidConversationDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`
- Test: `app/src/test/java/com/buyansong/im/storage/ConversationDaoContractTest.kt`

- [ ] **Step 1: Write failing DAO tests**

Add tests for inserting a `GROUP` conversation, incrementing unread plus mention unread from an incoming group message, and clearing both counts.

- [ ] **Step 2: Run DAO tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.ConversationDaoContractTest --console=plain
```

Expected: compile/test failure because `mentionUnreadCount` and clear behavior do not exist yet.

- [ ] **Step 3: Implement DAO/storage support**

Add `mention_unread_count`, `conversation_type`, `title`, and `avatar_url` schema columns; update model mapping, in-memory DAO, and Android DAO.

- [ ] **Step 4: Run DAO tests**

Run the same command. Expected: pass.

### Task 3: Message Repository Group Text Send/Receive

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/MessageDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/AndroidMessageDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`
- Test: `app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt`

- [ ] **Step 1: Write failing repository tests**

Add tests that `sendGroupText` stores a pending outgoing `GROUP` message with `mentionedUserIds`, incoming group packets persist under packet `conversationId`, `@ me` increments mention unread when inactive, and active group conversations clear/avoid unread increments.

- [ ] **Step 2: Run repository tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --console=plain
```

Expected: compile/test failure because group message APIs and metadata are not implemented.

- [ ] **Step 3: Implement minimal group repository support**

Add `sendGroupText`, group packet JSON generation, mentions JSON parse/build helpers, group incoming handling, and target-aware `openConversationById`.

- [ ] **Step 4: Run repository tests**

Run the same command. Expected: pass.

### Task 4: Conversation List Opens Group Rows

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`
- Modify: `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`
- Test: `app/src/test/java/com/buyansong/im/conversation/ConversationListViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Add a test that a `group:` row exposes a conversation navigation target and does not treat the group as a single-chat peer.

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.conversation.ConversationListViewModelTest --console=plain
```

Expected: failure because current navigation target is peer-only.

- [ ] **Step 3: Implement target-aware conversation opening**

Add `navigationTargetConversationId`, open rows by `conversationId`, and keep single-chat wrappers for existing Contacts navigation.

- [ ] **Step 4: Run tests**

Run the same command. Expected: pass.

### Task 5: Mock Server Group HTTP And Fanout

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
- Test: `mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterTest.java`

- [ ] **Step 1: Write failing mock-server tests**

Add tests for group creation, non-member send rejection, group fanout to online members, queued offline group delivery, and duplicate `messageId` idempotency.

- [ ] **Step 2: Run mock-server tests and verify failure**

Run:

```powershell
mvn -q test
```

from `mock-server`.

Expected: failure because group service and fanout are absent.

- [ ] **Step 3: Implement mock-server group support**

Add in-memory first-pass group metadata, HTTP creation/member endpoints, `MessageRouter` group branch, and per-recipient undelivered state.

- [ ] **Step 4: Run mock-server tests**

Run the same command. Expected: pass.

### Task 6: Documentation And Final Verification

**Files:**
- Modify: `docs/DEVELOPMENT_STATUS.md`
- Modify: `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`
- Modify: `docs/status/B10-group-chat-and-mention.md`

- [ ] **Step 1: Update docs**

Mark B10 first slice as in progress or partially implemented and document group message JSON.

- [ ] **Step 2: Run Android targeted tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.SelfHostedImRouteTest --tests com.buyansong.im.storage.ConversationDaoContractTest --tests com.buyansong.im.message.MessageRepositoryTest --tests com.buyansong.im.conversation.ConversationListViewModelTest --console=plain
```

Expected: pass.

- [ ] **Step 3: Run mock-server tests**

Run:

```powershell
mvn -q test
```

from `mock-server`.

Expected: pass.

