# Bug: Recall Notify Is Not Durably Redelivered

## Status

- Status: Fix implemented
- Analyzed on: 2026-06-05
- Fixed on: 2026-06-05
- Branch: current working branch
- Related status docs:
  - `docs/status/B12-message-recall-and-read-receipts.md`
  - `docs/status/B5.5-mock-server-message-persistence.md`

## Problem

Message recall is currently a best-effort online notification to the receiver.
If the sender recalls a message successfully, but the receiver does not receive
the `RECALL_NOTIFY` packet because of network jitter, reconnect, process close,
or mock-server restart timing, the receiver can keep showing the original
message forever.

The risky sequence is:

1. A sends message `m1`.
2. B receives `RECEIVE_MESSAGE`, persists it locally, and sends `DELIVERY_ACK`.
3. A recalls `m1`.
4. Server accepts the recall and sends A `RECALL_ACK(success=true)`.
5. Server tries to send B `RECALL_NOTIFY`.
6. B is offline, reconnecting, or misses the packet.
7. Because `m1` was already delivery-acked, server reconnect replay does not
   send anything to B. B's local SQLite row remains unrecalled.

Expected behavior: a successful server-side recall should eventually reach every
affected receiver, including receivers that already `DELIVERY_ACK`ed the
original message.

## Evidence From Documents

`docs/status/B12-message-recall-and-read-receipts.md` says the Android client
persists recall state from `RECALL_ACK` / `RECALL_NOTIFY`, and the mock server
sends `RECALL_NOTIFY` only to the online peer. The same status section
explicitly says durable recall replay for offline peers is not part of the first
pass.

`docs/status/B5.5-mock-server-message-persistence.md` says mock-server
persistence covers accepted messages, sender `messageId` idempotency, and
receiver-side undelivered `RECEIVE_MESSAGE` redelivery. It also preserves the
semantic that `DELIVERY_ACK` means receiver local persistence, not read.

The B12 design note already identified this gap: if the receiver is offline,
the server can persist recall state and resend `RECALL_NOTIFY` after receiver
auth; otherwise the peer can keep showing the original message.

## Evidence From Code

Server recall handling:

- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
  `handleRecallMessage(...)` validates ownership, conversation, and the
  two-minute window.
- On success it calls `acceptedMessageStore.markRecalled(messageId, requesterId,
  recalledAt)`, sends `RECALL_ACK` to the requester, then calls
  `registry.find(recalled.receiverUserId()).ifPresent(...)` to send
  `RECALL_NOTIFY`.
- There is no fallback branch that queues a recall notification when the
  receiver is offline.

Server reconnect replay:

- `handleAuth(...)` calls only `deliverQueuedMessages(userId, client)`.
- `deliverQueuedMessages(...)` only iterates
  `undeliveredMessagesByReceiver[userId]` and sends `RECEIVE_MESSAGE`.
- `restoreAcceptedMessages()` hydrates `acceptedMessagesById` and rebuilds
  `undeliveredMessagesByReceiver` only when `delivered = 0`.
- Therefore a message that was already `DELIVERY_ACK`ed before recall will not
  be replayed on auth, even if its persisted `recalled` column is `1`.

Server persistence:

- `SQLiteAcceptedMessageStore` has recall columns:
  `recalled`, `recalled_by`, `recalled_at`.
- `markRecalled(...)` updates every row for the `message_id`.
- `loadAll()` restores those fields into `AcceptedMessage`.
- The restored recall fields are currently used for idempotent repeat recall
  checks, but not for receiver-side durable notification replay.

Android receiving side:

- `MessagePacketProcessor` owns packet collection for the login session.
- `MessageRepository.handlePacket(...)` routes `RECALL_ACK` and
  `RECALL_NOTIFY`.
- `handleRecallAck(...)` and `handleRecallNotify(...)` both call
  `markMessageRecalled(...)`.
- `markMessageRecalled(...)` persists `is_recalled`, `recalled_by`, and
  `recalled_at` through `messageDao.markRecalled(...)` and updates the
  conversation preview.

This means Android local persistence is not the main failure point. The missing
piece is reliable server-to-receiver delivery of recall state after the original
message has already been delivery-acked.

## Root Cause

B5.5 persists message delivery state, but B12 recall is modeled as an in-place
state update on an accepted message without a separate per-receiver delivery
cursor for that state update.

`undeliveredMessagesByReceiver` tracks only original `RECEIVE_MESSAGE` packets.
Once a receiver sends `DELIVERY_ACK`, the original message leaves that replay
queue. Later recall changes are written to `accepted_messages.recalled`, but no
new durable pending item is created for the receiver. As a result, `RECALL_NOTIFY`
has at-most-once delivery semantics.

## Scope And Impact

Affected:

- Single-chat recall when the receiver is offline or misses `RECALL_NOTIFY`.
- Single-chat recall after server restart if the receiver did not receive the
  notification before reconnecting.
- Group-chat recall is likely affected more broadly because one message can have
  multiple receiver rows, but current `handleRecallMessage(...)` only sends
  `RECALL_NOTIFY` to `recalled.receiverUserId()` from one accepted row.

Not the primary cause:

- Android `messages.is_recalled` persistence exists.
- Android packet processing path handles `RECALL_NOTIFY`.
- Mock-server SQLite schema contains recall fields.
- B5.5 original-message redelivery works for `delivered = 0`.

## Recommended Fix Direction

Add durable per-receiver delivery tracking for recall state updates, using the
same reliable-delivery model as original message delivery:

- Add a protocol command:

  ```text
  RECALL_NOTIFY_ACK = 18
  ```

- Add an in-memory server index:

  ```java
  pendingRecallNotifiesByReceiver
  // receiverId -> (messageId -> RecallNotifyEvent)
  ```

- Add `recall_notified INTEGER NOT NULL DEFAULT 0` to each
  `(message_id, receiver_id)` row in `accepted_messages`.
- On successful recall, mark the accepted message recalled and mark recall
  notification pending for every affected receiver row.
- Send `RECALL_NOTIFY` immediately to online receivers.
- Have the Android client acknowledge recall notification persistence with
  `RECALL_NOTIFY_ACK` only after `messageDao.markRecalled(...)` has completed.
- On receiver auth/reconnect, replay pending recall notifications independently
  of original `delivered` state.
- Make repeat recall idempotent: return success to the requester and do not
  create duplicate pending recall events.

This keeps the two reliability paths parallel but semantically separate:

```text
RECEIVE_MESSAGE  -> DELIVERY_ACK       -> original message content persisted
RECALL_NOTIFY    -> RECALL_NOTIFY_ACK  -> message recall state persisted
```

Client-side persistent pending event storage is not required for the current
mock-server scope. If Android receives `RECALL_NOTIFY` but cannot persist the
recall state, it should not send `RECALL_NOTIFY_ACK`; the server will replay the
pending recall notification on the next auth/reconnect. Duplicate
`RECALL_NOTIFY` packets are safe because Android recall marking is idempotent.

For group chat, recall notification state must be tracked per receiver. A single
message-level `recall_notified` flag is not sufficient because one group member
can acknowledge the recall while another member is offline.

Sender-side `RECALL_MESSAGE` requests intentionally do not use a pending outbox
or background retry worker. If the sender taps recall while the client cannot
send the request, or the server rejects the recall request, the original message
keeps its normal state and the chat shows a transient bottom gray toast:

```text
撤回失败，请重试
```

The user can manually retry while the server-side two-minute recall window is
still valid. This keeps recall semantics different from normal message sending:
normal messages need sender-side pending retry, while recall is an immediate
operation that either succeeds through `RECALL_ACK` or asks the user to retry.

## Regression Tests To Add

Mock-server tests:

- Receiver delivery-acks original message, then goes offline; sender recalls;
  receiver auth later gets `RECALL_NOTIFY`.
- Same scenario across router restart using `SQLiteAcceptedMessageStore`.
- Replayed recall notification is idempotent after the receiver already marked
  the message recalled.
- Offline group recall creates/replays recall notifications for each receiver
  row, not only one member.
- A message that was never originally delivered should not lose its recall state:
  reconnect should either receive a recalled `RECEIVE_MESSAGE` representation or
  receive `RECEIVE_MESSAGE` followed by `RECALL_NOTIFY` in deterministic order.

Android tests:

- Receiving duplicate `RECALL_NOTIFY` keeps one recalled row and preserves
  conversation preview.
- `RECALL_NOTIFY` for an unknown local `messageId` should have a documented
  strategy: ignore, store as pending event, or trigger history/sync once B4
  exists.

## Verification Performed

Implemented the recommended `RECALL_NOTIFY_ACK` fix:

- Added `RECALL_NOTIFY_ACK = 18` to Android and mock-server protocol enums.
- Android sends `RECALL_NOTIFY_ACK` after processing `RECALL_NOTIFY` and
  persisting local recall state.
- Mock server tracks pending recall notifications in
  `pendingRecallNotifiesByReceiver`.
- Mock server persists per-receiver `recall_notified` in `accepted_messages`.
- Receiver auth/reconnect replays pending `RECALL_NOTIFY` independently of the
  original message `delivered` state.
- `RECALL_NOTIFY_ACK` clears the pending recall notification.

Reviewed and changed these implementation paths:

- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
- `mock-server/src/main/java/com/buyansong/imserver/netty/WebSocketFrameHandler.java`
- `mock-server/src/main/java/com/buyansong/imserver/protocol/ImCommand.java`
- `mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterTest.java`
- `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- `app/src/main/java/com/buyansong/im/protocol/ImCommand.kt`
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- `app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt`
- `app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt`
- `app/src/test/java/com/buyansong/im/chat/ChatMessageRowLayoutTest.kt`

Verification commands:

```text
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --console=plain
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --tests com.buyansong.im.chat.ChatViewModelTest --tests com.buyansong.im.chat.ChatMessageRowLayoutTest --console=plain
mvn -q -Dtest=MessageRouterTest test
```

Both commands passed on 2026-06-05.
