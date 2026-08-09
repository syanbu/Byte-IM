# WebSocket Protobuf Protocol (v2) Status

## Requirement

Replace the legacy `Magic + Version + Length + Command + JSON Body + CRC32` WebSocket wire format with one versioned Protobuf `ImEnvelope` whose `oneof payload` identifies every authentication, heartbeat, chat, ACK, read-receipt, and recall message.

## Status

Done. Client and mock server were cut over atomically in the same release; the legacy codec files are deleted.

## Wire Format

- One binary WebSocket message = one `ImEnvelope`.
- `protocol_version = 2`; both decoders reject any other version.
- `oneof payload` is the sole message discriminator; there is no numeric `cmd`.
- No application-layer magic, body-length field, or CRC32: WebSocket supplies message boundaries, and WSS/TLS supplies production transport integrity.
- Decoded/encoded envelope limit is 64 KiB (`ImEnvelopeCodec.MAX_ENVELOPE_BYTES`). Image bytes stay in OSS; envelopes carry URLs and metadata only. The mock server reassembles fragmented WebSocket messages with Netty `WebSocketFrameAggregator` before parsing.
- Envelopes with no payload, oversized payloads, malformed bytes, or wrong versions are protocol failures: the Android client surfaces `ConnectionState.Failed`, the mock server closes the channel.
- Client and mock server assume a lockstep cutover; an independently deployed production server would need a dual-protocol rollout before removing a legacy version.

## Schema

Single source of truth: `protocol-schema/src/main/proto/im/v2/im_protocol.proto`.

- Android generates Java-lite classes via the Protobuf Gradle Plugin (`app/build.gradle`).
- The mock server generates full Java classes from the same file via protobuf-maven-plugin (`mock-server/pom.xml`). Generated sources are build outputs and are not committed.
- Compatibility rule: never reuse removed field numbers; reserve deleted field numbers and enum numeric values in later schema revisions.

Payload families: `auth` / `auth_ack` / `auth_nack`, `heartbeat` / `heartbeat_ack`, `send_message` / `message_ack` / `receive_message` / `delivery_ack`, `read_ack`, and `recall_message` / `recall_ack` / `recall_notify` / `recall_notify_ack`.

## Heartbeat Reconciliation Fields

- `Heartbeat.unacked_message_ids`: the sender's locally pending (not yet `message_ack`-ed) message ids, supplied on Android by `ConnectionLifecycleManager.heartbeatUnackedProvider`.
- `HeartbeatAck.received_message_ids`: the subset the mock server has durably accepted (`AcceptedMessageStore.existsForSender`), consumed on Android by `heartbeatReconcileConsumer` to accelerate outbox retries for messages the server never received.

## Persistence Formats Are Not Wire Formats

- Android `pending_messages.packet_body` keeps the legacy JSON send snapshot as a durable outbox format; retries rebuild a v2 envelope via `MessageProtoMapper.sendEnvelopeFromPendingJson`. Rows queued before the cutover stay retryable.
- Mock-server `accepted_messages.ack_json` / `message_json` keep the legacy camelCase JSON columns; conversion to/from typed payloads happens only at the `SQLiteAcceptedMessageStore` boundary via `MessageProtoMapper`. Rows written before the cutover still restore.

## Cross-Language Parity

Both `ImEnvelopeCodec` implementations enforce identical validation (size, version, payload presence), and both codec test suites decode the same golden heartbeat bytes produced by the other platform (`goldenHeartbeatBytesDecode`), proving Android (javalite) and server (full Java) wire compatibility.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-08-09 | Android cutover + legacy removal | `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` | Passed: 167 unit tests, debug APK assembled; legacy `ImPacket`/`ImPacketCodec`/`ImCommand`/`Crc32` deleted with no remaining references. |
| 2026-08-09 | Mock server cutover + legacy removal | `mvn -q test` in `mock-server` | Passed: 48 tests including envelope codec golden-bytes parity, heartbeat reconciliation, group read ACK broadcast, offline push preview, and recall flows; legacy `ImPacket`/`ImPacketCodec`/`ImCommand` deleted with no remaining references. |
| 2026-08-09 | Legacy symbol scan | `grep -rn "ImPacket\|ImCommand\|Crc32\|0xCAFE" app/src mock-server/src` | Passed: no matches in main or test sources. |
