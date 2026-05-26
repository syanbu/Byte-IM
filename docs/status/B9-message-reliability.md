# B9 Message Reliability Status

## Requirement

ACK mechanism, retry on failure, and deduplication by `messageId`.

## Status

Partial.

## Completed Foundation

- Sending a message writes a pending record.
- `MESSAGE_ACK` deletes the pending record.
- Message insert uses `message_id` uniqueness for deduplication.
- Incoming duplicate messages are ignored by `insertOrIgnore`.
- Mock server sends `MESSAGE_ACK` to sender and `RECEIVE_MESSAGE` to online receiver.
- Mock server queues `RECEIVE_MESSAGE` packets for offline receivers in memory and delivers them after the receiver authenticates.

## Missing Work

- No retry scheduler or outbox loop yet.
- No exponential retry policy for pending messages.
- No transition to `FAILED` after retry exhaustion.
- No resend after reconnect.
- No ACK-loss simulation test.
- No duplicate downlink stress verification.

## Explicitly Deferred

- Receiver-side delivery ACK is deferred to [B9.5-delivery-ack.md](B9.5-delivery-ack.md). B9 first pass should treat sender `MESSAGE_ACK` as server acceptance only, not receiver delivery proof.
- Durable mock-server message persistence is deferred to [B5.5-mock-server-message-persistence.md](B5.5-mock-server-message-persistence.md). B9 first pass may still use server-side `messageId` idempotency, but full restart-proof offline delivery belongs to B5.5/B9.5 follow-up work.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: message deduplication is covered. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: send-text persistence, pending insertion, ACK status update, pending deletion, incoming persistence, and unread increment are covered. |
| 2026-05-25 | Offline delivery regression | `mvn -q -Dtest=MessageRouterTest test`; `mvn -q test` in `mock-server`; `.\gradlew.bat :app:testDebugUnitTest --console=plain`; `.\gradlew.bat :app:assembleDebug --console=plain` | Passed: offline receiver messages are queued on the mock server, delivered on receiver auth, and Android starts conversation-list packet collection before connecting so immediate delivery updates `Messages`. |

