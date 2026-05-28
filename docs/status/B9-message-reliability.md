# B9 Message Reliability Status

## Requirement

ACK mechanism, retry on failure, and deduplication by `messageId`.

## Status

Done for the B9 first-pass sender reliability scope.

## Completed Foundation

- Sending a message writes a pending record.
- `MESSAGE_ACK` deletes the pending record.
- Message insert uses `message_id` uniqueness for deduplication.
- Incoming duplicate messages are ignored by `insertOrIgnore`.
- Mock server sends `MESSAGE_ACK` to sender and `RECEIVE_MESSAGE` to online receiver.
- Mock server queues `RECEIVE_MESSAGE` packets for offline receivers in memory and delivers them after the receiver authenticates.

## B9 First-Pass Completion

- Android now has a login-session scoped `MessageOutboxWorker`, started beside `MessagePacketProcessor` from the authenticated app root.
- Sending now writes the local `messages` row, conversation preview, and `pending_messages` row inside one SQLite transaction before any network send attempt.
- The outbox worker listens for `ConnectionState.Authenticated` and scans due `pending_messages` on a short loop while the session remains authenticated.
- Pending retries resend the original `packetCmd` and `packetBody`, preserving the same `messageId`, `conversationId`, `clientSeq`, content, and timestamp.
- `MessageRetryPolicy` uses 5s, 10s, 20s, 40s, and 60s capped backoff delays.
- Retry exhaustion at 5 recorded retry attempts marks the local message `FAILED` and removes its pending record.
- `MESSAGE_ACK` still marks the local message `SENT`, stores `serverSeq`, removes pending, and emits repository updates so Chat refreshes.
- Chat UI shows outgoing `SENDING` messages with a small spinner and `FAILED` outgoing messages with a failure indicator. Manual tap-to-retry remains out of scope.
- Failed outgoing messages without `serverSeq` remain in the local outstanding-message ordering bucket, next to pending local sends.
- Mock server keeps an in-memory accepted-message map by `messageId`. The first send allocates `serverSeq`, forwards/queues receiver delivery, and ACKs the sender; duplicate sends return the original ACK only.

## Reliability Semantics

If a user sends messages while offline or before the WebSocket session is authenticated:

- Android stores each message locally as `SENDING` and writes each original `SEND_MESSAGE` packet into `pending_messages` in one local SQLite transaction.
- The initial `connection.send()` may fail, but the pending record remains.
- After reconnect and `ConnectionState.Authenticated`, the outbox worker scans due pending rows and resends them with the same original body.

The sender local write path is:

```text
SQLite transaction:
  insert messages(SENDING)
  upsert conversation preview
  insert pending_messages(original SEND_MESSAGE body)
commit

after commit:
  connection.send(SEND_MESSAGE)
```

This prevents a local half-write where Chat shows `SENDING` but the outbox has no pending row.

If a sent packet does not receive `MESSAGE_ACK` before its next retry time:

- Android retries only after the pending row is due and the session is authenticated.
- Each retry increments `retryCount` and schedules the next deadline from the retry policy.
- The worker never generates a new `messageId` for retry.
- After 5 retry attempts, Android marks the message `FAILED` and deletes pending.

If the mock server receives the same `messageId` more than once in the same process:

- It does not allocate another `serverSeq`.
- It does not forward or offline-queue another `RECEIVE_MESSAGE`.
- It returns a `MESSAGE_ACK` containing the original `serverSeq`.

## Explicitly Deferred

- Receiver-side delivery ACK is deferred to [B9.5-delivery-ack.md](B9.5-delivery-ack.md). B9 first pass should treat sender `MESSAGE_ACK` as server acceptance only, not receiver delivery proof.
- Durable mock-server message persistence is now implemented in [B5.5-mock-server-message-persistence.md](B5.5-mock-server-message-persistence.md). B9 sender-side semantics remain unchanged; the new persistence layer simply removes the previous restart-only gap in server-side `messageId` idempotency.

## Remaining Risks

- Android outbox retry is process-local. The pending table survives app process death, but retry resumes only after the app starts, logs in/restores the session, and reaches `Authenticated`.
- This pass does not add receiver delivery proof. A sender `MESSAGE_ACK` means server acceptance, not receiver persistence.
- Manual emulator verification for airplane-mode sends and visual spinner/failure states is still recommended.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: message deduplication is covered. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: send-text persistence, pending insertion, ACK status update, pending deletion, incoming persistence, and unread increment are covered. |
| 2026-05-25 | Offline delivery regression | `mvn -q -Dtest=MessageRouterTest test`; `mvn -q test` in `mock-server`; `.\gradlew.bat :app:testDebugUnitTest --console=plain`; `.\gradlew.bat :app:assembleDebug --console=plain` | Passed: offline receiver messages are queued on the mock server, delivered on receiver auth, and Android starts conversation-list packet collection before connecting so immediate delivery updates `Messages`. |
| 2026-05-26 | B9 RED Android | `.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.message.MessageRetryPolicyTest --tests com.codex.im.message.MessageRepositoryTest --tests com.codex.im.message.MessageOutboxWorkerTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Failed before implementation because `MessageRetryPolicy`, `MessageOutboxWorker`, and repository retry APIs were missing. |
| 2026-05-26 | B9 RED mock-server | `mvn -q -Dtest=MessageRouterTest test` in `mock-server` | Failed before implementation because duplicate `messageId` sends allocated a second `serverSeq` and forwarded a second `RECEIVE_MESSAGE`. |
| 2026-05-26 | B9 targeted GREEN Android | `.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.message.MessageRetryPolicyTest --tests com.codex.im.message.MessageRepositoryTest --tests com.codex.im.message.MessageOutboxWorkerTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Passed: retry backoff, pending resend body reuse, failure after exhaustion, Authenticated-triggered outbox send, and Chat status refresh are covered. |
| 2026-05-26 | B9 targeted GREEN mock-server | `mvn -q -Dtest=MessageRouterTest test` in `mock-server` | Passed: duplicate `messageId` returns the original ACK and does not duplicate receiver forwarding. |
| 2026-05-26 | Full Android JVM regression | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | Passed: 141 tests. |
| 2026-05-26 | Full mock-server regression | `mvn -q test` in `mock-server` | Passed. |
| 2026-05-26 | Android debug build | `.\gradlew.bat :app:assembleDebug --console=plain` | Passed. |
| 2026-05-26 | Sender outbox atomicity | `.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.message.MessageRepositoryTest --console=plain` | Passed: `connection.send()` occurs only after the local transaction commits. |
| 2026-05-26 | Android transaction compile check | `.\gradlew.bat :app:assembleDebugAndroidTest --console=plain` | Passed after allowing Gradle to fetch missing wrapper/cache data: instrumented SQLite rollback coverage compiles. |
