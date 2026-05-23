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

## Missing Work

- No retry scheduler or outbox loop yet.
- No exponential retry policy for pending messages.
- No transition to `FAILED` after retry exhaustion.
- No resend after reconnect.
- No ACK-loss simulation test.
- No duplicate downlink stress verification.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: message deduplication is covered. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: send-text persistence, pending insertion, ACK status update, pending deletion, incoming persistence, and unread increment are covered. |

