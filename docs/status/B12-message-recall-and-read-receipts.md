# B12 Message Recall and Read Receipts Status

## Requirement

B12 covers two single-chat message state synchronization features:

- Message recall: a sender can recall their own message within 2 minutes. Both local clients should keep the message row but render it as recalled.
- Read receipts: when the receiver opens a single-chat page, the receiver reports the latest read incoming `serverSeq`; the sender UI changes outgoing unread markers into read markers.

This feature must stay on the existing self-built WebSocket binary protocol, local SQLite persistence, and Jetpack Compose chat UI path. It must not introduce a third-party IM SDK or bypass the existing protocol/state architecture.

Design source: [`../B12-message-recall-and-read-receipts-design.md`](../B12-message-recall-and-read-receipts-design.md).

## Status

Not started.

B12 is currently a design-ready follow-up. No Android or mock-server implementation should be assumed complete until this document gains verification records.

In the top-level dashboard, B12 is still grouped with later optional experience work. This status file exists so future implementation can start from a stable local scope instead of re-deriving the feature from the design notes.

## Project Context

`docs/2026-engine.md` defines B12 as an advanced experience feature:

- message recall within 2 minutes
- read-state synchronization
- UI display for read/unread state

Current development priorities have already completed the core IM foundation that B12 depends on:

- B2 single-chat WebSocket messaging
- B3 conversation list, unread count, and last-message preview
- B5 local SQLite persistence using `SQLiteOpenHelper`, not Room
- B6 custom binary protocol
- B7 heartbeat and reconnect
- B8 server-authoritative message ordering through `serverSeq`
- B9 sender-side ACK/retry/deduplication
- B9.5 receiver `DELIVERY_ACK`
- B11 first-pass image messages

## Current Foundation To Reuse

Protocol commands currently documented in `docs/WEBSOCKET_PROTOCOL_AND_STATES.md`:

- `SEND_MESSAGE`: sender sends a normal chat message.
- `MESSAGE_ACK`: server accepted the message and assigned `serverSeq`.
- `RECEIVE_MESSAGE`: server forwards the message to the receiver.
- `READ_ACK`: reserved for read receipts, not implemented yet.
- `DELIVERY_ACK`: receiver confirms local persistence only. This is not a read receipt.

Important existing rules:

- `MESSAGE_ACK` means server acceptance, not receiver delivery or read.
- `DELIVERY_ACK` means the receiver device persisted the packet, not that the user saw it.
- Android message packet handling must stay owned by the login-session scoped `MessagePacketProcessor`, not page-specific collectors.
- Page ViewModels should refresh from repository/storage update signals.
- Authenticated HTTP and WebSocket flows must resolve fresh valid session tokens through the shared session-provider path.
- Local persistence must use hand-written SQLite through `SQLiteOpenHelper`.

## Agreed First Scope

Single chat only.

Read receipts:

- Use `READ_ACK` over the existing WebSocket binary protocol.
- Treat read state as a conversation-level peer read cursor.
- Calculate read position from the receiver's local maximum incoming `serverSeq`.
- Send read receipt when entering an open chat.
- Send another read receipt while staying in that chat if new incoming messages arrive.
- Deduplicate locally so equal or lower read cursors are not sent repeatedly.
- Render read state only for outgoing messages from the current user.

Message recall:

- Add recall as a state update, not as message deletion.
- Keep the original SQLite message row.
- Hide original text/image content in chat when `isRecalled=true`.
- Show recall system text in the original message position.
- Allow recall only for the original sender and only within 2 minutes.
- The client may hide invalid recall menu entries, but the mock server must make the final decision.
- Repeated successful recall should be idempotent.

Chat interaction foundation:

- Text messages should move from bare `Text(message.content)` rendering toward message bubbles.
- Long-press action menu should start with `Copy` and later expose `Recall` once storage/protocol support is present.
- Image bubbles should eventually reuse the same recall policy, but first implementation may focus on text bubbles and then generalize.

## Explicit Non-Scope

- Group read-member list.
- Group recall UI naming beyond future design notes.
- Push notifications for recall/read events.
- A new HTTP API for first-pass read receipts.
- Treating `DELIVERY_ACK` as read.
- Physically deleting recalled message rows from SQLite.
- Using Room or an external IM SDK.

## Local Data Model Plan

### `messages`

Add recall state fields:

```sql
is_recalled INTEGER NOT NULL DEFAULT 0
recalled_at INTEGER
recalled_by TEXT
```

Android storage model should expose equivalent fields on `ChatMessage`.

Required DAO capabilities:

- mark a message recalled by `messageId`
- preserve original row ordering
- prevent recalled text/image content from being rendered as normal content
- update conversation preview if the recalled message is still the conversation's last message

Preview text:

- current user recalled: `你撤回了一条消息`
- peer recalled: `对方撤回了一条消息`

### `conversations`

Add single-chat peer read cursor fields:

```sql
peer_read_up_to_server_seq INTEGER
peer_read_at INTEGER
```

Required DAO capabilities:

- query current conversation maximum incoming `serverSeq`
- update peer read cursor only when the new cursor is greater than the existing cursor
- expose the cursor to chat UI state so outgoing messages can derive read/unread display

Single chat can use a conversation-level cursor because there is exactly one peer. If group chat is later implemented, member read state should move to a dedicated cursor table.

## Protocol Plan

Keep read receipt on the reserved command:

```text
READ_ACK = 13
```

Use `READ_ACK` for both:

- receiver client to server: "I have read up to this conversation sequence"
- server to sender client: "the peer has read up to this conversation sequence"

Add recall commands to Android and mock-server `ImCommand` enums:

```text
RECALL_MESSAGE = 15
RECALL_ACK     = 16
RECALL_NOTIFY  = 17
```

Command meanings:

- `RECALL_MESSAGE`: client requests recall for a message.
- `RECALL_ACK`: server returns recall success or failure to the requester.
- `RECALL_NOTIFY`: server notifies the peer that the message was recalled.

`docs/WEBSOCKET_PROTOCOL_AND_STATES.md` must be updated in the same implementation pass so protocol documentation does not drift from code.

## Android Implementation Plan

Recommended order:

1. Add `ChatDisplayPolicy` tests for copy and recall eligibility.
2. Add or refactor chat bubble composables for text messages and long-press menu.
3. Add local storage fields for recall state and peer read cursor.
4. Add DAO methods for recall marking, max incoming `serverSeq`, and peer read cursor updates.
5. Extend repository APIs for sending `READ_ACK`, processing `READ_ACK`, sending recall request, and processing recall result/notification.
6. Extend `MessagePacketProcessor` so `READ_ACK`, `RECALL_ACK`, and `RECALL_NOTIFY` are handled in the login-session scoped receive path.
7. Extend `ChatViewModel` to send read receipts on chat open and on active-chat incoming updates.
8. Extend `ChatViewModel` to expose recall action and surface recall failure messages.
9. Render outgoing read/unread state from `message.serverSeq <= conversation.peerReadUpToServerSeq`.
10. Render recalled messages as recall prompts instead of normal text or image bubbles.

Important Android behavior:

- Only send read receipts for messages already persisted locally and carrying `serverSeq`.
- Do not send read receipts when the chat is not the currently open conversation.
- Do not duplicate WebSocket packet consumption from `ChatViewModel` or `ConversationListViewModel`.
- Existing B11 image-message states must keep working; recall should override normal image rendering when the message is recalled.

## Mock-Server Implementation Plan

Recommended order:

1. Add recall commands to mock-server `ImCommand`.
2. Route `READ_ACK` in `MessageRouter` or the equivalent WebSocket handler.
3. Validate that the sending socket user matches the read receipt `readerId`.
4. Forward single-chat `READ_ACK` to the peer if online.
5. Persist read cursor only if first-pass restart recovery is required.
6. Store enough accepted-message metadata to validate recall:
   - `messageId`
   - `conversationId`
   - `senderId`
   - receiver/peer id
   - server accepted time
   - recalled flag/time/by
7. On `RECALL_MESSAGE`, validate sender ownership, conversation match, 2-minute window, and idempotency.
8. Return `RECALL_ACK` to requester.
9. Send `RECALL_NOTIFY` to peer if online.
10. If offline recall consistency is required, persist recall state and replay/synchronize it on peer reconnect.

## UI Acceptance Criteria

- Text messages are displayed as left/right chat bubbles, not bare text rows.
- Long-pressing an unrecalled text message shows `复制`.
- Long-pressing the current user's sent message within 2 minutes shows `撤回`.
- Long-pressing a peer message does not show `撤回`.
- Long-pressing a recalled message does not show `复制`.
- Outgoing unread messages show the current unread marker.
- After the peer opens the chat and sends `READ_ACK`, eligible outgoing messages show the green read marker.
- Recalled own messages render as `你撤回了一条消息`.
- Recalled peer messages render as `对方撤回了一条消息`.
- Recalled messages remain in chronological position and are not filtered out of the message list.
- Conversation preview reflects recall text if the recalled message is still the latest message.

## Verification Needed

Android unit tests should cover:

- `ChatDisplayPolicy.canCopy`
- `ChatDisplayPolicy.canRecall`
- read receipt send on chat open
- no duplicate read receipt for same/lower cursor
- processing inbound `READ_ACK` updates peer read cursor
- outgoing read/unread marker derives from `serverSeq` and peer cursor
- processing successful `RECALL_ACK` marks the local requester message recalled
- processing `RECALL_NOTIFY` marks receiver message recalled
- recall failure surfaces a user-visible error
- recalled messages render prompt text instead of original text/image

Mock-server tests should cover:

- `READ_ACK` forwards to single-chat peer.
- invalid `readerId` is rejected or ignored.
- non-sender cannot recall.
- recall after 2 minutes fails.
- successful recall sends `RECALL_ACK`.
- successful recall sends `RECALL_NOTIFY` to peer.
- repeated recall is idempotent.
- offline peer recovery behavior is covered if durable recall sync is implemented.

Manual/emulator checks:

- A sends to B; before B opens the chat, A sees unread.
- B opens A's chat; A receives read state and sees read marker.
- A recalls within 2 minutes; A sees own recall prompt and B sees peer recall prompt.
- A cannot recall after 2 minutes.
- App restart/reconnect does not lose already persisted recall/read state if persistence is included in the implementation pass.

## Current Risks

- Confusing `DELIVERY_ACK` with read receipts would produce incorrect UX. B12 must keep these semantics separate.
- If read/recall packets are handled by page ViewModels instead of `MessagePacketProcessor`, packets can be missed or processed more than once.
- If recall state physically deletes rows, message ordering and conversation preview can become inconsistent.
- If read cursor updates are not monotonic, stale packets can move UI from read back to unread.
- If offline recall notifications are not persisted/replayed, the peer may keep seeing the original message after reconnect.
- B11 image messages add another rendering path; recall rendering must take priority over both text and image bubble rendering.

## Documentation Updates Required During Implementation

- Update [`../DEVELOPMENT_STATUS.md`](../DEVELOPMENT_STATUS.md) to split B12 from B13 and point to this file.
- Update [`../WEBSOCKET_PROTOCOL_AND_STATES.md`](../WEBSOCKET_PROTOCOL_AND_STATES.md) with final `READ_ACK`, `RECALL_MESSAGE`, `RECALL_ACK`, and `RECALL_NOTIFY` semantics.
- Add verification records to this file after tests/builds pass.
