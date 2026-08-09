# Heartbeat Reconciliation + Event-Driven Outbox Plan

> **Status (2026-08-09): IMPLEMENTED AND VERIFIED.** This document is the authoritative design record, written after the design discussion that voided the earlier `2026-07-26-event-driven-message-outbox` draft plan (since deleted: it optimized exact-deadline wake precision that has no business value, and its signal-without-backstop design could silently stall retries). Verification evidence is recorded in [`docs/status/B9-message-reliability.md`](../../status/B9-message-reliability.md).

**Goal:** Remove the authenticated-state 1-second SQLite polling loop from `MessageOutboxWorker`, and cover the "connection is alive but `MESSAGE_ACK` silently never arrives" hole, using two cooperating layers:

- **Layer A (fast path): event-driven Outbox.** Wake on committed pending-row changes (revision signal) or the earliest persisted `nextRetryAt` deadline. No fixed-interval scanning.
- **Layer B (backstop): heartbeat-piggybacked reconciliation.** Each `HEARTBEAT` carries the sender's unacked message ids; the server replies which of them it has durably accepted; the client accelerates server-missing rows to immediate retry.

## Why This Shape (Design Discussion Record)

Finding a missing ACK fundamentally has only two detectors: **a timeout (some clock)** or **a later event that implies the ACK should have arrived**. Every candidate design is a choice of which clock and which event to reuse:

| Candidate | Verdict |
|---|---|
| Fixed 1s polling (old behavior) | Works, but scans an empty outbox every second forever; the cost this plan removes |
| Reconnect-only trigger | Has a real hole: connection alive + server silently dropped the message/ACK means no event ever fires; the message shows `SENDING` until an unrelated reconnect |
| Exact-deadline scheduler (voided 2026-07-26 plan) | Correct but over-precise: retrying at 5.0s vs 5.9s is meaningless to users, server idempotency, and protocol correctness; the precision machinery (revision ordering invariants, virtual-time ms tests) buys nothing |
| Server resends lost ACKs | Impossible: the server does not know the ACK was lost |
| Downstream-traffic inference (serverSeq gaps) | Suffers the last-message problem: if both sides go quiet, no downstream packet exists to infer from; can only assist, never replace a timeout |
| Heartbeat piggyback reconciliation | Reuses the existing 15s/75s clock; zero extra timers when idle; is a true reconciliation (server-authoritative "what I have"), catching both "ACK lost" and "message never stored" |
| Per-message one-shot timer | The minimal correct fast path; realized here as "sleep until earliest persisted deadline" |

Layer A and Layer B are **not mutually exclusive**; they operate at different time scales. Layer A recovers in ~5s; Layer B bounds any missed in-memory signal to one heartbeat interval. Production IM systems conventionally stack timeout retransmission + periodic reconciliation + reconnect sync; this design keeps the first two (reconnect sync already existed).

## Wire Format (client and mock-server change in lockstep)

```
HEARTBEAT:     {"clientTime":123,"unackedMessageIds":["m_1","m_2"]}
HEARTBEAT_ACK: {"serverTime":456,"receivedMessageIds":["m_1"]}
```

Rules:

- `unackedMessageIds`: sender's pending outgoing message ids, capped at 100, oldest first.
- `receivedMessageIds`: the subset the server has durably persisted **with `senderId` equal to the authenticated connection's user** — unknown ids and other users' ids are excluded.
- The server returns `receivedMessageIds` (possibly empty) **iff** the request carried `unackedMessageIds`. Omitting the field on empty results would make "old server" indistinguishable from "server has nothing", killing the accelerate-retry path.
- Both sides ignore unknown fields; a missing `unackedMessageIds` field means "no reconciliation info" (legacy client).

## Client Design

- `HeartbeatPacketFactory.create(nowMillis, unackedMessageIds)` builds the extended body (hand-written JSON with escaping, matching project style).
- `ConnectionLifecycleManager` gains two `@Volatile` hooks with safe defaults so construction order (CLM before account-scoped repository) and existing tests are unaffected:
  - `heartbeatUnackedProvider: () -> List<String>` — consulted when building each heartbeat.
  - `heartbeatReconcileConsumer: (List<String>) -> Unit` — invoked with parsed `receivedMessageIds` (`runCatching`, missing field → empty list).
  - Hooks are bound/reset in `DisposableEffect(accountRepositories)` at the `setContent` layer; Compose disposal order guarantees stale bindings are cleared before new ones install.
- `PendingMessageDao` gains `pendingMessageIds(limit)`, `earliestNextRetryAt()` (indexed `MIN(next_retry_at)`), and `accelerateRetryAtExcluding(receivedMessageIds, now)` (single `UPDATE ... SET next_retry_at=now WHERE next_retry_at > now AND message_id NOT IN (...)`, returns affected row count).
- `OutboxChangeSignal`: process-local `MutableStateFlow<Long>` revision counter. A revision (not a `SharedFlow` poke) is used so a change landing between the database query and suspension cannot be lost. Emitted after: the four enqueue transaction commits, an actual pending delete in `handleAck`, a mutating `retryDuePendingMessages` batch, and a mutating reconcile.
- `MessageRepository` exposes `pendingOutgoingMessageIds(limit = 100)`, `earliestPendingRetryAt()`, and `reconcileUnackedWithServer(receivedMessageIds, now)` (accelerates only rows with `nextRetryAt > now`; signals only on actual change).
- `MessageOutboxWorker` loop per `Authenticated` collectLatest cycle:

```text
observed = revision
retryDuePendingMessages(now)            // immediate sweep on (re)auth covers process death
next = earliestPendingRetryAt()
if state != Authenticated        -> return
if revision != observed          -> continue
if next != null && next <= now   -> continue
sleep: next == null ? revisions.first { it != observed }
                    : withTimeoutOrNull(next - now) { revisions.first { it != observed } }
```

Known accepted trade-off: the worker's own retry batch emits a signal, causing one harmless extra empty sweep per batch. Retry timestamps use `nowProvider` (injectable for virtual-time tests).

## Mock-Server Design

- `WebSocketFrameHandler` forwards the heartbeat packet body to `MessageRouter.handleHeartbeat(client, body)` (single-arg overload retained).
- `AcceptedMessageStore` gains `existsForSender(messageId, userId)`; in-memory and SQLite implementations added minimally.
- `handleHeartbeat` filters the client's id list through `existsForSender` and includes `receivedMessageIds` in the ACK.
- Existing `messageId` idempotency absorbs at-least-once resends: a resent `SEND_MESSAGE` returns the original `MESSAGE_ACK` without a new `serverSeq` or duplicate forwarding.

## Why Outbox Rows Are Still Deleted on ACK (Not Status-Flagged)

A "keep the row, write a status column" variant was considered and rejected:

- SQLite `DELETE` and `UPDATE` on this tiny, indexed table are both single B-tree operations at microsecond scale; deletion is not a measured bottleneck, and `UPDATE` on indexed columns can cost more index maintenance, not less.
- Status-flagged rows grow the table unboundedly (every message ever sent leaves a tombstone), forcing status filters on every query and eventually a GC job — real complexity to avoid an unreal cost.
- The durable ACK state already exists: `messages.status` (`SENDING` -> `SENT`). `pending_messages` is a work queue, not a ledger; two persistent status stores would invite divergence ("which one is true?").

Revisit only with profiling data showing deletion cost.

## Preserved Semantics (Hard Constraints)

- Retry delays 5s/10s/20s/40s/60s, exhaustion at 5 recorded attempts -> `FAILED`, pending row removed.
- Transaction order: `messages` + `conversations` + `pending_messages` commit before any network send.
- Retried packets reuse original `packetCmd`/`packetBody`/`messageId`.
- Heartbeat intervals (15s foreground / 75s background), missed-ACK limit, reconnect backoff: unchanged.
- No WorkManager/AlarmManager/foreground service added.

## Files

Client:

- Modify `connection/HeartbeatPacketFactory.kt`, `connection/ConnectionLifecycleManager.kt`, `message/MessageRepository.kt`, `message/MessageOutboxWorker.kt`, `storage/PendingMessageDao.kt`, `storage/AndroidPendingMessageDao.kt`, `MainActivity.kt`
- Create `message/OutboxChangeSignal.kt`
- Tests: `message/OutboxChangeSignalTest.kt`, `message/MessageOutboxWorkerTest.kt` (virtual time: deadline wake, ACK-cancelled retry, no retry while disconnected, immediate sweep on re-auth, zero queries while idle via counting DAO decorator), `message/MessageRepositoryOutboxReconcileTest.kt`, `connection/HeartbeatPacketFactoryTest.kt`

Mock-server:

- Modify `netty/WebSocketFrameHandler.java`, `session/MessageRouter.java`, accepted-message store interface + both implementations
- Create `src/test/java/com/buyansong/imserver/session/MessageRouterTest.java` (known id echoed, unknown id excluded, other user's id excluded, legacy heartbeat without `unackedMessageIds` gets no `receivedMessageIds`)

## Verification Results (2026-08-09)

- `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` — BUILD SUCCESSFUL; 143 unit tests pass, including pre-existing `ConnectionLifecycleManagerTest` (hook defaults preserve old behavior).
- `mvn -q test` in `mock-server` — 68 tests pass.
- Manual device smoke (offline send -> process stays alive -> network restored -> immediate replay; plus observing no per-second scans in logs) remains pending and is tracked in B9.

## Remaining Risks

- Every future pending-row mutation path must emit the revision signal; a forgotten signal no longer stalls retries forever (Layer B catches it within one heartbeat interval) but delays fast-path recovery. This is the deliberate price for dropping the polling backstop.
- Reconciliation resends are blind retransmissions absorbed by server idempotency; if message payloads ever become large, consider having the server re-issue `MESSAGE_ACK` for `receivedMessageIds` instead of waiting for client retransmit.
