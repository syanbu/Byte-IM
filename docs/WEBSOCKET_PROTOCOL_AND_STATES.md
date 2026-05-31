# WebSocket Protocol Messages and Client States

This document explains the difference between WebSocket protocol message types and local Android client states.

## Two Different Layers

WebSocket protocol messages are packets sent over the network between the Android client and mock server.

`ConnectionState` is local Android runtime state. It describes what stage the client connection is in.

In short:

- Protocol messages answer: what packet is being sent?
- `ConnectionState` answers: what state is this client connection in?

## WebSocket Protocol Message Types

Defined in:

- Android: `app/src/main/java/com/codex/im/protocol/ImCommand.kt`
- Mock server: `mock-server/src/main/java/com/codex/imserver/protocol/ImCommand.java`

| cmd | Name | Direction | Meaning |
|---:|---|---|---|
| 1 | `AUTH` | Client -> Server | Sent after WebSocket opens. Body carries the access token. |
| 2 | `AUTH_ACK` | Server -> Client | Sent after the server validates the access token and registers the client as online. |
| 3 | `HEARTBEAT` | Client -> Server | Heartbeat request. Defined for Phase 7 heartbeat/reconnect work. |
| 4 | `HEARTBEAT_ACK` | Server -> Client | Heartbeat response. The mock server can respond with `serverTime`. |
| 10 | `SEND_MESSAGE` | Sender Client -> Server | Sends one chat message to the server. |
| 11 | `MESSAGE_ACK` | Server -> Sender Client | Confirms the server accepted a sent message and assigned `serverSeq`. |
| 12 | `RECEIVE_MESSAGE` | Server -> Receiver Client | Forwards an online message to the receiver. |
| 13 | `READ_ACK` | Receiver Client -> Server -> Sender Client | Read receipt cursor for single chat. The reader reports the greatest incoming `serverSeq` they have viewed; the server forwards it to the peer. |
| 14 | `DELIVERY_ACK` | Receiver Client -> Server | Confirms the receiver persisted a `RECEIVE_MESSAGE` for later redelivery suppression. |
| 15 | `RECALL_MESSAGE` | Sender Client -> Server | Requests recall of an already accepted message. |
| 16 | `RECALL_ACK` | Server -> Requester Client | Returns recall success or failure. Success means the local row should be marked recalled. |
| 17 | `RECALL_NOTIFY` | Server -> Peer Client | Notifies the peer that an existing message was recalled. |
| 20 | `HISTORY_QUERY` | Reserved | History query. Not implemented yet. |
| 21 | `HISTORY_RESULT` | Reserved | History query result. Not implemented yet. |

## Client ConnectionState

Defined in:

- `app/src/main/java/com/codex/im/connection/ConnectionState.kt`

Current states:

| State | Meaning |
|---|---|
| `Disconnected` | No active WebSocket connection. |
| `Connecting` | Client is opening the WebSocket connection. |
| `Connected` | WebSocket `onOpen` fired. The socket is open, but IM auth is not confirmed yet. |
| `Authenticated` | Client received protocol `AUTH_ACK`. The IM session is authenticated and usable. |
| `Reconnecting(delayMillis, reason)` | Client detected a disconnect, failure, heartbeat timeout, or failed WebSocket write and is waiting for the next reconnect attempt. |
| `Failed(reason)` | WebSocket or protocol handling failed. |

## Runtime Flow

Normal startup flow:

```text
Disconnected
  -> Connecting
  -> Connected
  -> Authenticated
```

Detailed flow:

```text
ChatViewModel.start()
  -> connection.connect(accessToken)
  -> ConnectionState.Connecting

OkHttp WebSocket onOpen
  -> ConnectionState.Connected
  -> client sends AUTH packet

Mock server validates access token
  -> server sends AUTH_ACK packet
  -> server logs: STATUS AUTHENTICATED userId=... authAck=sent

Android receives AUTH_ACK
  -> ConnectionState.Authenticated
  -> chat screen shows: Connection: Authenticated
  -> ConnectionLifecycleManager starts foreground heartbeat every 15s
```

`AUTH_ACK` is a protocol message. `Authenticated` is the local Android state derived from receiving that protocol message.

## Heartbeat and Reconnect Flow

B7 adds an Android-side `ConnectionLifecycleManager` that wraps the raw OkHttp connection.

```text
Authenticated
  -> send HEARTBEAT every 15s while foreground, or every 75s while background
  -> HEARTBEAT_ACK refreshes liveness
  -> 2 missed heartbeat intervals
  -> disconnect raw WebSocket
  -> Reconnecting(delayMillis, "heartbeat timeout")
  -> reconnect with delays 1s, 2s, 4s, 8s, 16s, then 30s capped
  -> Authenticated resets reconnect delay to 1s
```

Failed writes use the same reconnect policy:

```text
Authenticated
  -> connection.send(packet) returns false for a message or heartbeat
  -> disconnect raw WebSocket
  -> Reconnecting(delayMillis, "send failed")
  -> reconnect with the normal backoff/token-provider path
  -> Authenticated lets the B9 outbox worker replay due pending messages
```

Network restore can wake an existing reconnect delay:

```text
Reconnecting(delayMillis, reason)
  -> Android ConnectivityManager reports an internet-capable network
  -> cancel the pending delay
  -> reconnect immediately with the normal token-provider path
```

Foreground/background policy:

- Foreground: run heartbeat every 15s and reconnect automatically after disconnect, failure, heartbeat timeout, or failed WebSocket write.
- Background: keep the WebSocket open, slow heartbeat to every 75s, and keep reconnect enabled. Failed writes still trigger immediate reconnect scheduling.
- Return to foreground: authenticated sockets switch heartbeat back to 15s; disconnected or failed sockets schedule reconnect.
- Network restore: internet-capable network availability cancels any pending reconnect backoff delay and starts a reconnect immediately.

Reconnect in B7 restores the WebSocket session. B9 layers a login-session scoped outbox worker on top of `ConnectionState.Authenticated` to retry due pending sender messages after reconnect.

## Message Ordering

B8 defines two sequence fields with different trust boundaries.

`clientSeq` is generated by the sender client per conversation. It represents that client instance's local send order and is useful for sender-side ACK correlation and local send-order diagnostics. It must not be used as the receiver's final display order, because two different senders can both send `clientSeq = 1`, local counters and clocks are not authoritative, and concurrent WebSocket sends cannot express the server's final acceptance order.

`serverSeq` is generated by the server per conversation after accepting `SEND_MESSAGE`. It represents the authoritative order in which messages entered that conversation. The mock server assigns the next `serverSeq` for the packet's `conversationId`, returns it in `MESSAGE_ACK`, and forwards it in `RECEIVE_MESSAGE`. Different conversations can have the same numeric `serverSeq`; within one conversation, `serverSeq` must increase.

The local mock server stores the last assigned sequence per conversation in `mock-server/data/mock-im-sequences.sqlite` when run through `MockImServer`. This prevents a server restart from assigning lower `serverSeq` values than messages already stored on Android, which would otherwise make new messages appear older than the local first page.

Accepted chat messages are now stored separately in `mock-server/data/mock-im-messages.sqlite`. That store preserves sender `messageId` idempotency and receiver undelivered replay state across mock-server restart, without changing the meaning of `MESSAGE_ACK` or `DELIVERY_ACK`.

Android display ordering follows this policy:

- Confirmed outgoing and received messages with `serverSeq` are ordered by conversation-local `serverSeq`.
- Local `SENDING` messages without `serverSeq` stay visible using temporary local `createdAt`, `clientSeq`, and `messageId` ordering.
- Once `MESSAGE_ACK` stores a `serverSeq`, the local outgoing message moves into the server-authoritative order.
- Out-of-order `RECEIVE_MESSAGE` arrival is tolerated by persisting each message and re-querying/re-merging with the ordering policy.
- Android consumes message packets through a login-session scoped packet processor, not only through the currently visible page, so messages are still stored when the receiver is on `Contacts`, `Me`, or another screen.
- B8 does not implement gap buffering, wait windows, or missing-message pulls; those remain later sync work. B9 first pass adds sender-side ACK timeout retry and reconnect pending replay for due outbox rows.

## Message Status

Defined in:

- `app/src/main/java/com/codex/im/storage/StorageModels.kt`

Current local message states:

| Status | Meaning |
|---|---|
| `SENDING` | Outgoing message is stored locally and `SEND_MESSAGE` has been attempted. Waiting for `MESSAGE_ACK`. |
| `SENT` | Server returned `MESSAGE_ACK`; local message has `serverSeq`. |
| `FAILED` | Outgoing message exhausted sender-side retry and no longer has an active pending outbox row. |
| `RECEIVED` | Incoming message was received through `RECEIVE_MESSAGE` and stored locally. |

Outgoing message flow:

```text
SENDING
  -> MESSAGE_ACK
  -> SENT

SENDING
  -> retry exhaustion
  -> FAILED
```

Incoming message flow:

```text
RECEIVE_MESSAGE
  -> RECEIVED
  -> DELIVERY_ACK
```

`DELIVERY_ACK` is receiver-side transport proof only:

- Android sends it only after `RECEIVE_MESSAGE` has been decoded and passed through local `insertOrIgnore`.
- It does not change the local message row from `RECEIVED` to another status.
- It is not a read receipt and must not be shown as B12 "read" UI.
- If `DELIVERY_ACK` is lost, server redelivery remains safe because Android deduplicates by `messageId`.
- The mock server persists accepted messages plus the receiver `delivered` flag, so auth-triggered redelivery survives server restart instead of only surviving within the current process.
- Android should have only one login-session scoped consumer of WebSocket incoming packets for this path. UI ViewModels must refresh from repository/storage updates instead of each collecting and reprocessing the same `RECEIVE_MESSAGE`, otherwise duplicate `DELIVERY_ACK` sends can occur even when local message persistence remains deduplicated.

## Read Receipts

B12 uses `READ_ACK` for read receipts and keeps it separate from `DELIVERY_ACK`.

Receiver-to-server body:

```json
{
  "conversationId": "single:13800113800:13900113900",
  "readerId": "13900113900",
  "peerId": "13800113800",
  "readUpToServerSeq": 1008,
  "readAt": 1717000000000
}
```

Server-to-sender body keeps the same cursor fields. Android stores the sender-side peer read cursor on the conversation and only moves it forward. The chat UI derives the outgoing read marker from:

```text
message.direction == OUTGOING
message.serverSeq != null
message.serverSeq <= conversation.peerReadUpToServerSeq
```

## Message Recall

B12 recall is a state update, not a deletion. Android keeps the original `messages` row and marks it recalled; UI renders the recall prompt instead of the original text or image.

Recall request:

```json
{
  "messageId": "m_001",
  "conversationId": "single:13800113800:13900113900",
  "requesterId": "13800113800",
  "requestAt": 1717000000000
}
```

Successful `RECALL_ACK` and `RECALL_NOTIFY` include:

```json
{
  "messageId": "m_001",
  "conversationId": "single:13800113800:13900113900",
  "recalledBy": "13800113800",
  "recalledAt": 1717000001000
}
```

`RECALL_ACK` also carries `success: true` on success or `success: false` with a `reason` such as `NOT_FOUND`, `NOT_SENDER`, `CONVERSATION_MISMATCH`, or `EXPIRED`. The mock server validates sender ownership, conversation match, and the 2-minute server-time recall window. Repeated successful recall is idempotent.

## Group Text Messages

B10 group text and image messages reuse the existing message commands:

- `SEND_MESSAGE`
- `MESSAGE_ACK`
- `RECEIVE_MESSAGE`
- `DELIVERY_ACK`

No new command id is needed for the first group message slice. The message body carries `conversationType = "GROUP"` and `groupId`.

Group send body:

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

Server ACK keeps the same shape as single chat:

```json
{
  "messageId": "m_...",
  "conversationId": "group:g_1001",
  "clientSeq": 12,
  "serverSeq": 1008,
  "serverTime": 1717000000100
}
```

Forwarded group receive packets keep the group `conversationId`, but set `receiverId` to the concrete recipient user id so `DELIVERY_ACK` remains per receiver:

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

Group image messages use the same `image` payload as single chat, while keeping the group envelope:

```json
{
  "messageId": "m_...",
  "conversationId": "group:g_1001",
  "conversationType": "GROUP",
  "groupId": "g_1001",
  "senderId": "13800113800",
  "receiverId": "g_1001",
  "clientSeq": 13,
  "type": "IMAGE",
  "content": "[图片]",
  "image": {
    "imageUrl": "https://oss.example.com/origin.jpg",
    "thumbnailUrl": "https://oss.example.com/thumb.jpg",
    "width": 1440,
    "height": 960,
    "mimeType": "image/jpeg",
    "sizeBytes": 345678
  },
  "mentionedUserIds": [],
  "timestamp": 1717000000000
}
```

Android stores group messages under the packet `conversationId`. For single chat, Android still canonicalizes `single:<a>:<b>` locally from sender and receiver ids for backward compatibility.

The mock-server group slice persists group metadata in `data/mock-im-groups.sqlite` and fans out accepted group messages to online members except the sender. Offline members are queued in the existing receiver undelivered index. Accepted group message delivery is persisted per concrete recipient in `accepted_messages` with `(message_id, receiver_id)`, so `DELIVERY_ACK` clears only that receiver and undelivered replay survives restart.

Group creation currently uses authenticated HTTP endpoints rather than a WebSocket command:

- `POST /groups`
- `GET /groups`
- `GET /groups/{groupId}`
- `PATCH /groups/{groupId}`
- `GET /groups/{groupId}/members`

`POST /groups` accepts `name` and `memberUserIds`, adds the authenticated requester as owner/member, and returns a stable `groupId` such as `g_1001`. Android persists that response into local `groups`, `group_members`, and a `GROUP` conversation row before showing it in Messages.

`PATCH /groups/{groupId}` accepts `{"name":"..."}` from any current group member and returns the updated group metadata. Android persists the response locally, and conversation list refresh uses `GET /groups` to pick up names changed by other members.

## Current Mock Server Logs

Successful WebSocket auth:

```text
[IM] STATUS AUTHENTICATED userId=13800113800 authAck=sent
```

Sending a message:

```text
[IM] SEND_MESSAGE sender=13800113800 receiver=13900113900 conversationId=single:13800113800:13900113900 messageId=... clientSeq=3 serverSeq=1001 content=Hello
[IM] MESSAGE_ACK sent sender=13800113800 messageId=... clientSeq=3 serverSeq=1001
[IM] RECEIVE_MESSAGE forwarded receiver=13900113900 messageId=... serverSeq=1001
```

These logs are diagnostics. The actual network protocol is the `ImCommand` packet stream described above.
