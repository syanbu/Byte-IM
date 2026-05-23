# B7 Heartbeat And Reconnect Status

## Requirement

Heartbeat and reconnect with exponential backoff and foreground/background awareness.

## Status

Not started on the Android client.

## Existing Support

- Protocol command ids include `HEARTBEAT` and `HEARTBEAT_ACK`.
- Mock server can respond to heartbeat packets.
- Connection state is exposed to UI as a `StateFlow`.

## Missing Work

- Add `HeartbeatManager`.
- Send heartbeat every 15 seconds after authenticated connection.
- Detect missed `HEARTBEAT_ACK` responses.
- Disconnect and trigger reconnect after timeout.
- Add `ReconnectPolicy` with 1s, 2s, 4s, 8s, 16s, and 30s cap.
- Add foreground/background awareness.
- Update UI status for connecting, connected/authenticated, reconnecting, and offline states.
- Prepare repeated disconnect/reconnect verification.

## Verification

No B7-specific Android verification yet.

