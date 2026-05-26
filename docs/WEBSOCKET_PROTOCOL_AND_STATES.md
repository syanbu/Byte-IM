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
| 13 | `READ_ACK` | Reserved | Read receipt. Not implemented yet. |
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
| `Reconnecting(delayMillis, reason)` | Client detected a disconnect, failure, or heartbeat timeout and is waiting for the next reconnect attempt. |
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

Foreground/background policy:

- Foreground: run heartbeat every 15s and reconnect automatically after disconnect, failure, or heartbeat timeout.
- Background: keep the WebSocket open, slow heartbeat to every 75s, and keep reconnect enabled.
- Return to foreground: authenticated sockets switch heartbeat back to 15s; disconnected or failed sockets schedule reconnect.

Reconnect in B7 only restores the WebSocket session. It does not implement B8 receive-side reordering or B9 pending-message retry/replay.

## Message Status

Defined in:

- `app/src/main/java/com/codex/im/storage/StorageModels.kt`

Current local message states:

| Status | Meaning |
|---|---|
| `SENDING` | Outgoing message is stored locally and `SEND_MESSAGE` has been attempted. Waiting for `MESSAGE_ACK`. |
| `SENT` | Server returned `MESSAGE_ACK`; local message has `serverSeq`. |
| `FAILED` | Reserved for timeout/retry failure handling. Full retry logic is planned for Phase 8. |
| `RECEIVED` | Incoming message was received through `RECEIVE_MESSAGE` and stored locally. |

Outgoing message flow:

```text
SENDING
  -> MESSAGE_ACK
  -> SENT
```

Incoming message flow:

```text
RECEIVE_MESSAGE
  -> RECEIVED
```

## Current Mock Server Logs

Successful WebSocket auth:

```text
[IM] STATUS AUTHENTICATED userId=13800113800 authAck=sent
```

Sending a message:

```text
[IM] SEND_MESSAGE sender=13800113800 receiver=13900113900 messageId=... serverSeq=1001 content=Hello
[IM] MESSAGE_ACK sent sender=13800113800 messageId=...
[IM] RECEIVE_MESSAGE forwarded receiver=13900113900 messageId=...
```

These logs are diagnostics. The actual network protocol is the `ImCommand` packet stream described above.
