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

Group chat is implemented for the current B10 pass. @ mention first-pass support is now implemented; richer mention editing can be refined later.

The Messages top-bar work already added the first local entry point for group creation:

- `ConversationListScreen` has a plus menu with `发起群聊`.
- `SelfHostedImRoute.GroupCreate` opens the local group-create contact picker.
- `GroupCreateViewModel` and `GroupCreateScreen` let the user select Contacts.
- The initial `MessageRepository.createLocalGroupConversation(...)` local-only path has been replaced for the UI flow by server-backed group creation.
- `ConversationListViewModel` marks `conversationId.startsWith("group:")` rows as groups.

Implemented in this B10 slice:

- Android storage models now include `ConversationType`, group id metadata, and `mentionedUserIds`.
- Android conversations now include group-aware fields and `mentionUnreadCount`.
- Android SQLite schema now stores conversation type, group id, and mentions JSON for messages.
- Android repository can send group text and image packets through `SEND_MESSAGE`.
- Android repository can receive group `RECEIVE_MESSAGE` packets using packet `conversationId` instead of recomputing a single-chat id.
- Incoming group messages increment `mention_unread_count` only when the current receiver is mentioned and the group is not active.
- Opening a group conversation clears normal unread and mention unread counts.
- Conversation list navigation can target `chat/{conversationId}` for both `single:` and `group:` conversations.
- Mock-server `MessageRouter` can fan out group messages to online group members, queue offline members, reject non-members, and keep duplicate group `messageId` sends idempotent.
- Mock-server now exposes authenticated group HTTP endpoints backed by SQLite metadata: `POST /groups`, `GET /groups`, `GET /groups/{groupId}`, `PATCH /groups/{groupId}`, and `GET /groups/{groupId}/members`.
- Mock-server persists group metadata and group members in `data/mock-im-groups.sqlite`.
- Android now has `GroupApi`, `OkHttpGroupApi`, `GroupJsonParser`, `GroupRepository`, `GroupDao`, and `AndroidGroupDao`.
- Android SQLite schema now includes `groups` and `group_members`.
- `GroupCreateViewModel` now obtains a fresh valid token, calls `POST /groups`, persists returned group metadata/members, and inserts a real `GROUP` conversation row with `conversationId = group:<groupId>`.
- Group chat message rows now render incoming bubble identity from the actual `senderId` profile instead of the group title/avatar.
- Group chat top bar can rename a group through `PATCH /groups/{groupId}`; other members pick up the latest group name when the conversation list refreshes through `GET /groups`.
- Fixed the group image send bug: group images now persist under `conversationId = group:<groupId>`, keep `conversationType = GROUP` and `groupId`, and send the image payload through the server group fanout path. The previous behavior incorrectly created a `single:<sender>:group:<groupId>` conversation and sent the image as a single-chat packet to a nonexistent `group:<groupId>` user.
- Group creation now navigates directly into the new group chat.
- Group conversation fallback avatars now show `群` instead of the first character of the group name.
- Group chat loads local/remote group members for the mention picker through `GET /groups/{groupId}/members`.
- Group chat composer shows a first-pass @ picker when a group draft ends with `@`.
- Group chat mention picker now renders as a scrollable bottom sheet that hides the soft keyboard while open and pulls the keyboard back up on the input after a member is selected, so large group member lists do not push the composer off-screen.
- Selecting a member inserts `@displayName ` and sends authoritative `mentionedUserIds`.
- Chat text bubbles highlight mentioned spans using persisted mention user ids plus group member display names.
- Conversation rows with unread mentions render a red `[有人@我]` prefix, and use the same structured mention display policy as chat bubbles.
- Group chat opening now shares the same initial-page optimization as single chat: `group:<groupId>` conversations can be synchronously pre-loaded before navigation, repository initial-page cache hits hydrate `ChatViewModel` before first composition, and group message send/receive/recall/delete paths invalidate the cached first page for the affected conversation.
- If token refresh or server group creation fails, the create screen stays put and does not create a local-only group row.

Still pending after this slice:

- Group list/member sync for groups created while another member is offline or on another device.
- Full group member display.
- Rich @ editing: search, arbitrary cursor insertion, and deleting mention chips as a unit.
- Group read/unread read-receipt semantics are intentionally deferred and should not be implemented in this B10 pass.

## Agreed Scope

Implement B10 in a focused first pass:

- Server-backed group creation from the existing `发起群聊` flow.
- Local persistence of group metadata and group members.
- Conversation list support for real group conversations.
- Opening a group chat page from the conversation list.
- Sending and receiving group text and image messages through the existing `SEND_MESSAGE` command.
- Persisting group messages in the same `messages` table.
- Sender-side retry and `MESSAGE_ACK` behavior for group text messages.
- Receiver-side `DELIVERY_ACK` per group recipient.
- @ member selection from the group member list.
- Persisting mention metadata.
- Highlighting @ display text in chat bubbles.
- `mention_unread_count` for "@ me" reminders in conversation rows.

## Explicitly Deferred

- Server-backed group history query.
- Group recall notification fanout.
- Group read receipts and read-member list.
- Group read/unread state beyond local unread counters.
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
- `PATCH /groups/{groupId}`
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

Mock-server SQLite storage currently persists group metadata in `data/mock-im-groups.sqlite`:

- `groups(group_id, name, owner_id, created_at, updated_at)`
- `group_members(group_id, user_id, role, joined_at, updated_at)`

`GroupService` allocates the next `g_...` id from the current maximum persisted group id, so restart does not reuse an existing group id.

Accepted message and delivery state are persisted in the existing `accepted_messages` table. For group fanout it writes one row per concrete recipient using `PRIMARY KEY(message_id, receiver_id)`, so `DELIVERY_ACK` is tracked per group member and survives mock-server restart.

If the server storage is later normalized, accepted message metadata can be split from delivery rows with `accepted_message_deliveries(message_id, receiver_id, delivered)`. The current implementation deliberately keeps per-recipient rows in `accepted_messages` to preserve the existing replay path.

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

Current implementation uses a deliberately small first pass: the picker opens when the group draft ends with `@`; selecting a member appends `@displayName ` at the end of the draft. It does not yet support member search, arbitrary cursor insertion, or chip-style deletion.

The picker should only appear in group chats. Single chat keeps the current composer.

Mention metadata is authoritative by user id, not by parsing display text. The display text may change if a member updates their profile; the sent content remains what the sender typed.

`@` messages remain normal `MessageType.TEXT` messages. B10 does not add a separate `MENTION` message type. A mention message is represented as plain text plus `mentionedUserIds`.

The composer normalizes selected mentions by inserting `@displayName ` with a trailing space and moving the cursor after that space. This lets the user continue typing the body text after the mention token.

## @ Highlight Rendering

Use a small display policy/helper to build an `AnnotatedString` for text bubbles:

- Highlight `@displayName` spans for user ids in `mentionedUserIds`.
- Use a distinct color from normal text.
- Keep copied text as the original plain `content`.

If the local member display name cannot be resolved, fall back to highlighting any exact `@<userId>` token or leave the text unhighlighted while preserving the stored mention metadata.

Current implementation builds highlight ranges from `mentionedUserIds` and the local group member list. If the display name is unavailable, it falls back to the user id token.

Structured mention display rule:

- If a text message starts with a valid mentioned token, render it as `@displayName bodyText`.
- Valid tokens are resolved from `mentionedUserIds` and local member/profile names; plain text parsing alone is not authoritative.
- The stored `content` is not rewritten. For example, persisted content may remain `@13900113900 What`, while UI renders `@ByteDance2 What`.
- If older/local content contains `@displayName: bodyText` or `@displayName：bodyText`, receivers normalize the display separator to a single space.
- If the mention token is not at the start, keep the original sentence shape and only replace/highlight the token.
- If the token has no boundary, such as `@13900113900aaaaaa`, do not treat the suffix as body text and do not merge it into a false mention reminder.

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

- If `mentionUnreadCount > 0`, show `[有人@我]` before the preview and render only that label in red.
- For the preview body, use the last message's persisted `mentionedUserIds` to resolve local/remote profile nicknames and run the same structured mention display policy as chat bubbles. For example, stored `@13900113900 What` displays as `@ByteDance2 What`.
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
- Group image messages are now in scope.
- Existing single-chat image send and receive must keep using the current image payload path.

Group image messages use the same uploaded `image` payload as single chat, plus `conversationType = GROUP` and `groupId`. They must be persisted locally under `conversationId = group:<groupId>`, not under a derived single-chat conversation id.

Bug note:

- Broken behavior: `ChatViewModel.sendImage()` called the single-chat `createLocalImageMessage(...)` path while `peerId` was `group:g_1001`, producing `single:<sender>:group:g_1001`.
- Effect: the image row appeared as a separate fake single-chat conversation in Messages and the outgoing packet targeted receiver `group:g_1001` as if it were a user id.
- Fix: group chat image creation uses `createLocalGroupImageMessage(...)`; upload completion keeps the message as `GROUP` and serializes both group metadata and the `image` payload.

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
- Add `createLocalGroupImageMessage(...)`.
- Build group message JSON with `mentionedUserIds`.
- Include `image` payload when `conversationType = GROUP` and `type = IMAGE`.
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
- Update `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md` with group message JSON.
- Add tests for Android storage, repository, ViewModels, and mock-server router.

## Expected Files To Change

Android:

- `app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt`
- `app/src/main/java/com/buyansong/im/MainActivity.kt`
- `app/src/main/java/com/buyansong/im/storage/StorageModels.kt`
- `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`
- `app/src/main/java/com/buyansong/im/storage/MessageDao.kt`
- `app/src/main/java/com/buyansong/im/storage/AndroidMessageDao.kt`
- `app/src/main/java/com/buyansong/im/storage/ConversationDao.kt`
- `app/src/main/java/com/buyansong/im/storage/AndroidConversationDao.kt`
- `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- `app/src/main/java/com/buyansong/im/chat/ChatDisplayPolicy.kt`
- `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`
- `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
- `app/src/main/java/com/buyansong/im/group/GroupCreateViewModel.kt`
- `app/src/main/java/com/buyansong/im/group/GroupCreateScreen.kt`

Likely new Android files:

- `app/src/main/java/com/buyansong/im/group/GroupApi.kt`
- `app/src/main/java/com/buyansong/im/group/OkHttpGroupApi.kt`
- `app/src/main/java/com/buyansong/im/group/GroupJsonParser.kt`
- `app/src/main/java/com/buyansong/im/group/GroupRepository.kt`
- `app/src/main/java/com/buyansong/im/storage/GroupDao.kt`
- `app/src/main/java/com/buyansong/im/storage/AndroidGroupDao.kt`
- `app/src/main/java/com/buyansong/im/chat/MentionDisplayPolicy.kt`

Mock-server:

- `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java`
- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
- `mock-server/src/main/java/com/buyansong/imserver/protocol/ImCommand.java`

Likely new mock-server files:

- `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java`
- `mock-server/src/main/java/com/buyansong/imserver/group/GroupStore.java`

## Test Plan

Android unit tests:

- `ConversationType` and route parsing for `single:` and `group:`.
- Group creation persists `groups`, `group_members`, and a `GROUP` conversation row.
- Group create ViewModel calls the server-backed repository path and does not create a local-only row on failure.
- `sendGroupText` stores a pending outgoing group message and packet body with mentions.
- Group image send stores an uploading image under `group:<groupId>` and sends a `GROUP` packet with an `image` payload after upload.
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
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.group.* --tests com.buyansong.im.message.*Group* --console=plain
```

```powershell
cd mock-server
mvn -q test
```

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-06-10 | Group Mention Bottom Sheet | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Passed: 48 Android unit tests, including 7 new tests for mention picker policy/data assumptions; debug APK assembled successfully. Manual smoke test on emulator/device still pending. |
| 2026-06-11 | Group Chat Initial Render Cache | `./gradlew testDebugUnitTest`; `./gradlew compileDebugKotlin` | Passed: 73 Android unit tests, including `ChatViewModelGroupReadReceiptTest`, repository initial-page cache/invalidation tests, and constructor hydration from cached messages. |

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
