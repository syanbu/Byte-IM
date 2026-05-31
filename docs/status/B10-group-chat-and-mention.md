# B10 Group Chat and Mention Development Design

## Requirement

B10 adds group chat and @ mention support:

- group member list
- group text message send and receive
- @ specific members in the composer
- highlight @ text in chat messages
- track "@ me" unread count separately from normal unread count

The feature must reuse the current self-hosted IM stack: custom binary WebSocket packets, server-assigned `serverSeq`, sender ACK and retry, receiver `DELIVERY_ACK`, local SQLite persistence through `SQLiteOpenHelper`, and Compose UI.

## Current Status

Design ready. Full B10 protocol and storage work is not implemented yet.

The Messages top-bar work already added the first local entry point for group creation:

- `ConversationListScreen` has a plus menu with `发起群聊`.
- `SelfHostedImRoute.GroupCreate` opens the local group-create contact picker.
- `GroupCreateViewModel` and `GroupCreateScreen` let the user select Contacts.
- `MessageRepository.createLocalGroupConversation(...)` creates a local `group:` conversation row.
- `ConversationListViewModel` marks `conversationId.startsWith("group:")` rows as groups.
- Group rows are intentionally not opened as real chat targets yet.

This B10 design starts from that local foundation and turns it into real group chat.

## Agreed Scope

Implement B10 in a focused first pass:

- Server-backed group creation from the existing `发起群聊` flow.
- Local persistence of group metadata and group members.
- Conversation list support for real group conversations.
- Opening a group chat page from the conversation list.
- Sending and receiving group text messages through the existing `SEND_MESSAGE` command.
- Persisting group messages in the same `messages` table.
- Sender-side retry and `MESSAGE_ACK` behavior for group text messages.
- Receiver-side `DELIVERY_ACK` per group recipient.
- @ member selection from the group member list.
- Persisting mention metadata.
- Highlighting @ display text in chat bubbles.
- `mention_unread_count` for "@ me" reminders in conversation rows.

## Explicitly Deferred

- Server-backed group history query.
- Group image messages.
- Group recall notification fanout.
- Group read receipts and read-member list.
- Group ownership transfer, admin roles, mute, quit group, remove member.
- User search or real friend adding.
- Production-safe database migrations for existing local demo data.
- Push notification for @ reminders.

Existing single-chat image, recall, read receipt, retry, and delivery semantics should keep working.

## Design Direction

Use one conversation abstraction for both single chat and group chat.

Current single chat uses:

```text
single:<lowerUserId>:<higherUserId>
```

Group chat should use:

```text
group:<groupId>
```

Do not model a group as a fake `peerId` target long term. The current local `group:<creatorUserId>:<createdAt>:<memberHash>` id is acceptable as a temporary local row, but the B10 server-backed path should replace it with a stable server group id such as `g_...`.

## Android Data Model

### Conversation Model

Extend `Conversation` with group-aware fields:

```kotlin
enum class ConversationType {
    SINGLE,
    GROUP
}

data class Conversation(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    ...
    val type: ConversationType = ConversationType.SINGLE,
    val title: String = peerName,
    val avatarUrl: String? = null,
    val mentionUnreadCount: Int = 0
)
```

Compatibility rules:

- Existing `single:` rows default to `SINGLE`.
- Existing local `group:` rows default to `GROUP`.
- For `SINGLE`, `peerId` remains the other user id.
- For `GROUP`, `peerId` may remain `conversationId` for compatibility, but UI should prefer `title`.

### Chat Message Model

Extend `ChatMessage` with group and mention metadata:

```kotlin
data class ChatMessage(
    ...
    val conversationType: ConversationType = ConversationType.SINGLE,
    val groupId: String? = null,
    val mentionedUserIds: List<String> = emptyList()
)
```

For group messages:

- `conversationId = "group:<groupId>"`
- `receiverId = groupId` or empty compatibility value
- `groupId = <groupId>`
- `senderId` is the real user who sent the message

The repository should stop deriving incoming conversation id only from `senderId/receiverId`. It must trust `conversationId` when present, then fall back to `conversationIdFor(senderId, receiverId)` only for legacy single-chat packets.

## SQLite Schema

### `conversations`

Add:

```sql
conversation_type TEXT NOT NULL DEFAULT 'SINGLE';
title TEXT;
avatar_url TEXT;
mention_unread_count INTEGER NOT NULL DEFAULT 0;
```

Rules:

- `unread_count` remains total unread messages for the conversation.
- `mention_unread_count` counts unread messages that mention the current user.
- Opening the group conversation clears both counts.

### `messages`

Add:

```sql
conversation_type TEXT NOT NULL DEFAULT 'SINGLE';
group_id TEXT;
mentions_json TEXT;
```

`mentions_json` stores a compact JSON array of mentioned user ids:

```json
["13900113900","13700113700"]
```

This keeps first-pass storage simple while avoiding a join for every chat row render.

### `groups`

Add a local group metadata table:

```sql
CREATE TABLE groups (
  group_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  avatar_url TEXT,
  owner_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
```

### `group_members`

Add:

```sql
CREATE TABLE group_members (
  group_id TEXT NOT NULL,
  user_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  avatar_url TEXT,
  role TEXT NOT NULL DEFAULT 'MEMBER',
  joined_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(group_id, user_id)
);
```

Indexes:

```sql
CREATE INDEX idx_group_members_user ON group_members(user_id);
```

## Android Repository API

Introduce a target model so chat code no longer depends only on `peerId`:

```kotlin
sealed class ChatTarget {
    data class Single(val peerUserId: String) : ChatTarget()
    data class Group(val groupId: String, val conversationId: String) : ChatTarget()
}
```

Recommended `MessageRepository` additions:

- `createGroupOnServerAndPersist(...)`
- `syncGroup(groupId: String)`
- `openConversation(currentUserId: String, target: ChatTarget, now: Long)`
- `sendGroupText(senderId: String, groupId: String, content: String, mentionedUserIds: List<String>, now: Long)`
- `historyPage(target: ChatTarget, beforeTime: Long?, limit: Int)`
- `groupMembers(groupId: String)`
- `clearConversationUnread(conversationId: String)`

Existing single-chat APIs can remain as wrappers during migration.

## Protocol Design

No new message command is required for group text messages. Reuse:

- `SEND_MESSAGE`
- `MESSAGE_ACK`
- `RECEIVE_MESSAGE`
- `DELIVERY_ACK`

Extend the JSON body.

### Group Text Send

Client to server:

```json
{
  "messageId": "m_...",
  "conversationId": "group:g_1001",
  "conversationType": "GROUP",
  "groupId": "g_1001",
  "senderId": "13800113800",
  "receiverId": "g_1001",
  "clientSeq": 12,
  "type": "TEXT",
  "content": "@张三 看一下",
  "mentionedUserIds": ["13900113900"],
  "timestamp": 1717000000000
}
```

Server ACK:

```json
{
  "messageId": "m_...",
  "conversationId": "group:g_1001",
  "clientSeq": 12,
  "serverSeq": 1008,
  "serverTime": 1717000000100
}
```

Server to group members:

```json
{
  "messageId": "m_...",
  "conversationId": "group:g_1001",
  "conversationType": "GROUP",
  "groupId": "g_1001",
  "senderId": "13800113800",
  "receiverId": "13900113900",
  "clientSeq": 12,
  "serverSeq": 1008,
  "serverTime": 1717000000100,
  "type": "TEXT",
  "content": "@张三 看一下",
  "mentionedUserIds": ["13900113900"],
  "timestamp": 1717000000000
}
```

The forwarded `receiverId` should be the concrete recipient user id so the existing `DELIVERY_ACK.receiverId` semantics remain clear.

### Delivery ACK

Receiver to server:

```json
{
  "messageId": "m_...",
  "conversationId": "group:g_1001",
  "serverSeq": 1008,
  "receiverId": "13900113900"
}
```

For group messages the server must mark delivery per `(messageId, receiverId)`, not only per message.

## Mock Server Design

### HTTP Endpoints

Add authenticated endpoints:

- `POST /groups`
- `GET /groups`
- `GET /groups/{groupId}`
- `GET /groups/{groupId}/members`

`POST /groups` request:

```json
{
  "name": "群聊(3)",
  "memberUserIds": ["13900113900", "13700113700"]
}
```

Response:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "groupId": "g_1001",
    "name": "群聊(3)",
    "ownerId": "13800113800",
    "memberUserIds": ["13800113800", "13900113900", "13700113700"],
    "createdAt": 1717000000000,
    "updatedAt": 1717000000000
  }
}
```

### Server Storage

Extend mock-server SQLite storage with:

- `groups`
- `group_members`
- accepted group messages
- group delivery state per recipient

The existing `accepted_messages` table stores one `receiver_id`; group delivery requires one of these options:

1. Add a separate `accepted_message_deliveries(message_id, receiver_id, delivered)`.
2. Keep `accepted_messages` as message-level metadata and move receiver delivery state entirely into the new table.

The second option is cleaner for B10 and still preserves single-chat behavior by writing one delivery row for the single receiver.

### Router Behavior

`MessageRouter.handleSendMessage(...)` should branch by `conversationType`:

- `SINGLE`: keep current path.
- `GROUP`: validate sender is a group member, allocate `serverSeq` by `conversationId`, persist accepted message once, create undelivered delivery rows for every member except sender, ACK sender, and forward `RECEIVE_MESSAGE` to online members.

Duplicate `messageId` handling remains idempotent:

- Duplicate from sender returns the original `MESSAGE_ACK`.
- It must not allocate a new `serverSeq`.
- It must not duplicate per-recipient delivery rows.

Auth/reconnect delivery replay must scan undelivered rows for the authenticated user and resend all pending group messages.

## Group Creation Flow

Replace the current local-only creation result with server-backed creation:

1. User taps Messages top-bar plus.
2. User taps `发起群聊`.
3. `GroupCreateScreen` lists contacts.
4. User selects one or more contacts.
5. ViewModel calls `POST /groups` with a fresh valid access token.
6. Repository persists `groups`, `group_members`, and a `GROUP` conversation row.
7. App navigates back to Messages.
8. The new group row opens a real group chat target.

Failure behavior:

- If session refresh fails, show a concise error and do not create a local group.
- If server creation fails, keep the user on the create page with an error message.
- Do not insert a local-only group row after the server-backed path is introduced.

## Chat Navigation

Change the route from peer-only to target-aware.

Recommended route:

```text
chat/{conversationId}
```

Examples:

```text
chat/single:13800113800:13900113900
chat/group:g_1001
```

`ChatViewModel` should resolve the target from the conversation id:

- `single:` -> peer user id from the two participants and current user id.
- `group:` -> group id and group metadata from local SQLite.

This avoids adding separate single-chat and group-chat screens.

## @ Mention Composer

First-pass behavior:

1. User types `@` in a group chat.
2. UI shows a group member picker.
3. User selects one member.
4. Composer inserts `@displayName `.
5. ViewModel records the selected member id in `mentionedUserIds`.
6. Sending the message persists and transmits both `content` and `mentionedUserIds`.

The picker should only appear in group chats. Single chat keeps the current composer.

Mention metadata is authoritative by user id, not by parsing display text. The display text may change if a member updates their profile; the sent content remains what the sender typed.

## @ Highlight Rendering

Use a small display policy/helper to build an `AnnotatedString` for text bubbles:

- Highlight `@displayName` spans for user ids in `mentionedUserIds`.
- Use a distinct color from normal text.
- Keep copied text as the original plain `content`.

If the local member display name cannot be resolved, fall back to highlighting any exact `@<userId>` token or leave the text unhighlighted while preserving the stored mention metadata.

## @ Me Unread Semantics

Incoming group message handling:

```text
isMentionMe = currentUserId in mentionedUserIds
isActiveConversation = message.conversationId == activeConversationId
```

If the conversation is not active:

- increment `unread_count`
- increment `mention_unread_count` only when `isMentionMe`

If the conversation is active:

- do not increment either count
- still persist the message and render the highlight

Opening the group conversation clears both counts.

Conversation row display:

- If `mentionUnreadCount > 0`, show an `@我` indicator before the preview.
- Normal unread badge still uses `unreadCount`.
- Bottom Messages tab total unread remains based on `unread_count`, not `mention_unread_count`, because mentions are a subset of unread messages.

## Read Receipts and Recall Interaction

For this B10 pass:

- Keep single-chat read receipt behavior unchanged.
- Do not send group `READ_ACK`.
- Do not show group read markers.
- Keep single-chat recall behavior unchanged.
- Group recall fanout can be added later by broadcasting `RECALL_NOTIFY` to group members.

This avoids mixing B10 delivery semantics with a larger group read-cursor design.

## Image Message Interaction

For this B10 pass:

- Group text messages are in scope.
- Group image messages are deferred.
- Existing single-chat image send and receive must keep using the current image payload path.

The protocol shape leaves room for group image messages later by combining `conversationType = GROUP` with `type = IMAGE`.

## Implementation Plan

### Task 1: Add Conversation Target Model

- Add `ConversationType`.
- Add target-aware route helpers.
- Update conversation list items to open by `conversationId`, not by fake group `peerId`.
- Keep existing single-chat navigation behavior through wrappers.

### Task 2: Add Group Storage

- Extend `conversations`.
- Extend `messages`.
- Add `groups` and `group_members`.
- Add DAO interfaces and Android/InMemory implementations.
- Add mention count update methods.

### Task 3: Add Mock Server Group HTTP

- Add group creation and group member query endpoints.
- Persist groups and members in SQLite.
- Return stable `groupId`.

### Task 4: Convert Group Create To Server-Backed

- Replace local-only `createLocalGroupConversation` in the UI path.
- Use a fresh valid access token before group HTTP requests.
- Persist returned group metadata.
- Navigate back to Messages after success.

### Task 5: Add Group Message Router

- Branch `SEND_MESSAGE` by `conversationType`.
- Persist one accepted group message.
- Allocate `serverSeq` per `group:<groupId>`.
- Create per-recipient delivery state.
- Forward online messages and replay offline undelivered messages after auth.
- Preserve duplicate `messageId` idempotency.

### Task 6: Add Android Group Send/Receive

- Add `sendGroupText(...)`.
- Build group message JSON with `mentionedUserIds`.
- Parse incoming `conversationId`, `conversationType`, `groupId`, and mentions.
- Persist group messages without recomputing `conversationId` from sender/receiver.
- Send `DELIVERY_ACK` per received group packet.

### Task 7: Add Group Chat UI

- Resolve `ChatTarget.Group` in `ChatViewModel`.
- Show group title in the chat top bar.
- Render incoming group sender identity in message rows.
- Disable group read markers for this pass.

### Task 8: Add @ Composer And Highlight

- Add group member picker triggered by `@`.
- Track selected mentioned user ids.
- Send mention metadata.
- Highlight @ spans in `ChatTextBubble`.

### Task 9: Add @ Me Conversation Reminder

- Add `mentionUnreadCount` to conversation list state.
- Update incoming group message unread logic.
- Clear mention unread on open.
- Render `@我` before the preview when needed.

### Task 10: Documentation And Verification

- Update `docs/DEVELOPMENT_STATUS.md`.
- Update `docs/WEBSOCKET_PROTOCOL_AND_STATES.md` with group message JSON.
- Add tests for Android storage, repository, ViewModels, and mock-server router.

## Expected Files To Change

Android:

- `app/src/main/java/com/codex/im/SelfHostedImRoute.kt`
- `app/src/main/java/com/codex/im/MainActivity.kt`
- `app/src/main/java/com/codex/im/storage/StorageModels.kt`
- `app/src/main/java/com/codex/im/storage/ImDatabaseHelper.kt`
- `app/src/main/java/com/codex/im/storage/MessageDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidMessageDao.kt`
- `app/src/main/java/com/codex/im/storage/ConversationDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidConversationDao.kt`
- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatDisplayPolicy.kt`
- `app/src/main/java/com/codex/im/conversation/ConversationListViewModel.kt`
- `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- `app/src/main/java/com/codex/im/group/GroupCreateViewModel.kt`
- `app/src/main/java/com/codex/im/group/GroupCreateScreen.kt`

Likely new Android files:

- `app/src/main/java/com/codex/im/group/GroupApi.kt`
- `app/src/main/java/com/codex/im/group/OkHttpGroupApi.kt`
- `app/src/main/java/com/codex/im/group/GroupJsonParser.kt`
- `app/src/main/java/com/codex/im/group/GroupRepository.kt`
- `app/src/main/java/com/codex/im/storage/GroupDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidGroupDao.kt`
- `app/src/main/java/com/codex/im/chat/MentionDisplayPolicy.kt`

Mock-server:

- `mock-server/src/main/java/com/codex/imserver/netty/HttpAuthHandler.java`
- `mock-server/src/main/java/com/codex/imserver/session/MessageRouter.java`
- `mock-server/src/main/java/com/codex/imserver/protocol/ImCommand.java`

Likely new mock-server files:

- `mock-server/src/main/java/com/codex/imserver/group/GroupService.java`
- `mock-server/src/main/java/com/codex/imserver/group/GroupStore.java`

## Test Plan

Android unit tests:

- `ConversationType` and route parsing for `single:` and `group:`.
- Group creation persists `groups`, `group_members`, and a `GROUP` conversation row.
- Group create ViewModel calls the server-backed repository path and does not create a local-only row on failure.
- `sendGroupText` stores a pending outgoing group message and packet body with mentions.
- Incoming group message uses packet `conversationId` and does not recompute single-chat id.
- Incoming `@ me` increments both unread and mention unread when inactive.
- Incoming mention for another user increments only unread.
- Opening a group clears unread and mention unread.
- Chat text bubble highlights mention spans.
- Single-chat send/receive tests continue to pass.

Mock-server tests:

- Authenticated user can create a group with selected members.
- Non-member cannot send to a group.
- Group `SEND_MESSAGE` ACKs sender once and forwards to all other members.
- Offline group members receive queued messages after auth.
- `DELIVERY_ACK` clears only that receiver's delivery row.
- Duplicate group `messageId` returns original ACK and does not duplicate fanout.

Suggested verification commands:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.group.* --tests com.codex.im.message.*Group* --console=plain
```

```powershell
cd mock-server
mvn -q test
```

## Manual Verification

Use three accounts on emulator/device sessions:

1. Account A creates a group with B and C.
2. A sees the group row in Messages.
3. B and C can sync or receive the group row after server group support is wired.
4. A opens the group and sends a text message.
5. B and C receive the message in the same `group:<groupId>` conversation.
6. B goes offline, A sends another message, B reconnects and receives it once.
7. A sends `@B` in the group.
8. B sees `@我` in the conversation row and the normal unread badge.
9. C sees normal unread only.
10. B opens the group, both unread and `@我` reminder clear.

## Risks

- The largest backend change is delivery state: group messages require per-recipient delivery, while the current mock server stores one `receiverId` per accepted message.
- The largest Android change is replacing peer-only chat assumptions with `conversationId` or `ChatTarget`.
- Mention display should not rely on parsing plain text alone; it must persist mentioned user ids.
- Group creation should stop producing local-only rows once server-backed creation is implemented, otherwise local and server groups can diverge.
- Existing single-chat flows are mature; B10 should preserve wrappers and tests while gradually introducing target-aware APIs.

