# Per-User syncSeq, Unified Inbox Stream, and Queue Convergence

> **Status (2026-08-20): PLAN ONLY. Not implemented.** Written after the clientSeq removal. Supersedes the "no gap detection / missing-message pull" non-scope notes in B8/B9. Revised the same day to include queue convergence (Phases 4-5) per owner decision.

**Goal:** Close the receive-side recovery hole, then converge recovery onto a single pull-based path. Today the sender side is fully covered (messageId idempotency + MESSAGE_ACK + Outbox retry + heartbeat reconciliation), but the receiver side has no integrity guarantee: if a push is lost while the connection looks alive, or the client crashes between receiving and persisting, nothing detects the loss. Meanwhile the server carries two parallel queue subsystems (undelivered messages, pending recall notifies) whose recovery roles overlap with what a sync stream provides.

**End state:** one per-user, cross-conversation `sync_inbox` ledger is the single source of truth for "everything this user must not lose" — messages and recall notifies alike. Clients recover exclusively by pulling with a persisted cursor. Both in-memory redrive queues are deleted.

## Requirements (owner-stated, 2026-08-20)

1. **Recall notifies join the sync stream.** A recall notify is an inbox entry like any other; `sync_inbox` is generalized with an entry `kind`.
2. **Converge to a single recovery path.** Once sync is live and verified on device, delete the message undelivered queue; once recall notifies are in the stream, delete the recall notify queue. No two overlapping recovery mechanisms.
3. **DELIVERY_ACK semantics are preserved.** What gets deleted is the redrive-until-acked *mechanism*, not the delivered-state record or the client's ack behavior. Sync-replayed duplicates are re-acked idempotently.

## Current State (verified 2026-08-20)

- Server keeps `undeliveredMessagesByReceiver` and `pendingRecallNotifiesByReceiver` (`MessageRouter.java:58-59`), backed by `accepted_messages.delivered` / recall flags, and replays both on `handleAuth`. Message delivery is confirmed by `DELIVERY_ACK`; recall notify delivery by `RecallNotifyAck` + `recall_notified` (see `docs/bug/Bug-RecallNotifyNotDurablyRedelivered.md` — a bug class caused exactly by per-event-type reliability machinery).
- These queues cover "receiver was offline" but not: (a) packet lost while online — no event ever fires; (b) client acked but crashed before the SQLite commit; (c) a future second device.
- Client already dedups incoming messages by `messageId` (`insertOrIgnore`); recall application is a naturally idempotent UPDATE. Replay-based recovery needs no new dedup logic.

## Design

Three orthogonal identifiers, one job each:

- `messageId` — idempotency and ACK correlation (exists)
- `serverSeq` — per-conversation authoritative display order (exists)
- `syncSeq` — per-user receive-stream position, used only for recovery and integrity (new)

`serverSeq` is scoped per conversation because display order is a per-conversation question asked by everyone. `syncSeq` is scoped per user across conversations because completeness is a per-receiver question asked by one device — a single cross-conversation stream gives one continuity invariant (`n+1` arithmetic) that active conversations check on behalf of quiet ones.

### Wire format (proto, additive; old clients unaffected)

```proto
// ImEnvelope.oneof payload additions:
SyncRequest sync_request = 50;    // client -> server
SyncComplete sync_complete = 51;  // server -> client, terminates a replay batch

message SyncRequest {
  uint64 last_sync_seq = 1;  // client's persisted maxSyncedSeq; 0 = "everything you retain"
  uint32 limit = 2;          // page size, server clamps to <= 200
}

message SyncComplete {
  uint64 up_to_sync_seq = 1;
  bool has_more = 2;         // client must page again before considering itself caught up
}

// ReceiveMessage gains:
message ReceiveMessage {
  ChatMessagePayload message = 1;
  optional uint64 sync_seq = 2;  // present on live push AND replay
}

// RecallNotify gains:
message RecallNotify {
  string message_id = 1;
  string conversation_id = 2;
  string recalled_by = 3;
  uint64 recalled_at = 4;
  optional uint64 sync_seq = 5;  // present on live push AND replay
}
```

`reserved` field numbers from the clientSeq removal (ChatMessagePayload 8, MessageAck 3) stay reserved.

Rules:

- Every pushed or replayed inbox item carries the recipient's `sync_seq`, strictly increasing per user, regardless of kind.
- Replay sends items in ascending `sync_seq` (messages as `RECEIVE_MESSAGE`, recalls as `RECALL_NOTIFY`), then one `SyncComplete`. `has_more = true` means the client must page again.
- Relative order across kinds is guaranteed by allocation: a message and its recall appear in the stream in real acceptance order (e.g. message at 105, its recall at 108).

### Server (mock-server)

- `user_sync_counters(user_id TEXT PRIMARY KEY, next_seq INTEGER NOT NULL)` — mock-scale seqsvr: one atomic increment per user.
- `sync_inbox(user_id TEXT NOT NULL, sync_seq INTEGER NOT NULL, kind TEXT NOT NULL, ref_id TEXT NOT NULL, payload_json TEXT NOT NULL, PRIMARY KEY(user_id, sync_seq))`.
  - `kind = 'message'`: `ref_id = message_id`, payload joinable to `accepted_messages.message_json`.
  - `kind = 'recall_notify'`: `ref_id = message_id`, payload carries `{message_id, conversation_id, recalled_by, recalled_at}`.
- Entries are written **in the same transaction** as the accepting mutation: message acceptance (single-chat and per-recipient group loops) writes `message` rows; recall acceptance writes one `recall_notify` row per receiver. Allocation, inbox insert, and the domain mutation share one critical section (stores are already `synchronized`).
- `handleSyncRequest(client, body)`: `SELECT ... WHERE user_id = ? AND sync_seq > ? ORDER BY sync_seq ASC LIMIT ?`; each row replays as its kind's packet with `sync_seq`; then `SyncComplete`. Rows whose `accepted_messages` join fails (purged) are skipped, not fatal.
- Live push path attaches the allocated `sync_seq` to the outbound packet for both kinds.
- Retention: no pruning in v1 (mock scale). Documented risk, first follow-up if data files grow.

### Client (Android)

- New table `sync_state(account_id TEXT PRIMARY KEY, max_sync_seq INTEGER NOT NULL DEFAULT 0)` (`ImDatabaseHelper`, bump `DATABASE_VERSION`; destructive onUpgrade is acceptable — a reset client re-pulls from 0 and dedups).
- `MessagePacketProcessor` continuity tracking, for both item kinds:
  - `sync_seq == maxSyncedSeq + 1`: persist in the existing path, advance the cursor **in the same transaction** as the item write. Cursor invariant: "everything ≤ maxSyncedSeq is durably persisted locally".
  - `sync_seq > maxSyncedSeq + 1`: persist the item anyway (never block UI on integrity), then fire one `SyncRequest(maxSyncedSeq)`.
  - No `sync_seq` (old server): persist, cursor untouched — legacy tolerance, same precedent as heartbeat reconciliation.
  - `SyncComplete`: advance to `up_to_sync_seq`; if `has_more`, immediately send the next `SyncRequest`.
- Recall item handling: idempotent UPDATE of the target message's recalled state. Must tolerate out-of-order arrival: a recall whose target message is not yet local (both may arrive in the same replay batch, or the target was lost and arrives later) records the recall so the message, when persisted, appears already recalled. This tolerance is a required test case, not an option.
- `SyncRequest` triggers: (a) entering `Authenticated`, (b) detected gap, (c) process start after login.
- `DELIVERY_ACK` / `RecallNotifyAck` behavior unchanged; replayed duplicates are re-acked idempotently.

### Queue convergence (Phases 4-5)

- **Phase 4 deletes the message queue.** After sync is verified on device: remove `undeliveredMessagesByReceiver`, the `handleAuth` message replay, and the redrive-until-acked logic. **Keep** `accepted_messages.delivered` and `markDelivered` — delivered-state semantics and `DELIVERY_ACK` survive; only the redrive mechanism dies. From here on, offline catch-up is exclusively client-pulled.
- **Phase 5 deletes the recall queue.** After recall notifies flow through `sync_inbox`: remove `pendingRecallNotifiesByReceiver`, its `handleAuth` replay, and the `recall_notified` redrive. `RecallNotifyAck` and the recalled-state record stay.
- Deletion order is a hard constraint: a queue may only be deleted after its item kind is covered by a client-verified sync stream. Clients older than the sync rollout lose their only recovery path at deletion time — acceptable for this project (single controlled client), must be documented.

### Why not the alternatives

- **Per-conversation cursors**: N cursors per user, and a gap in a quiet conversation is invisible until its next message — possibly never. One stream, one number, proves completeness.
- **Keep both queues as permanent belt-and-suspenders**: two overlapping recovery paths is a classic drift source ("which mechanism owns this loss?"). The owner chose convergence; the queues survive as scaffolding until sync is verified, not as permanent redundancy.
- **serverSeq-based gap detection**: suffers the last-message problem; per-user stream plus post-auth sync bounds staleness to reconnect time.

## Fragile assumption (premise check)

Phases 1-4 ship an intermediate state where the ledger covers messages but not recalls — during that window "full inbox sync" claims are false and the recall queue is load-bearing. Phase 5 exists precisely to close this; the fragile assumption is fully resolved only when Phase 5 lands. If Phase 5 is cancelled, the recall queue must stay and docs must keep naming the limitation.

## Attack angles

- **Dependency failure**: server without sync support → client sees no `sync_seq`, persists legacy-style. Client without sync → server behaves as today (until Phase 4/5 deletions, which assume client rollout is complete).
- **Scale explosion**: `sync_inbox` grows unboundedly. Acceptable at mock scale; retention (e.g. keep last 1000 entries per user) is the first follow-up.
- **Rollback**: Phases 1-3 are pure addition — stop sending `SyncRequest` and the system reverts to today's behavior with no migration. Phases 4-5 are **not** freely reversible: a deleted queue's in-flight guarantees are gone; rolling back means restoring code, and any losses during the sync-only window stay lost. This asymmetry is why Phase 3 device verification gates Phase 4.

## Phases (each independently mergeable)

**Phase 1 — server ledger + sync endpoint (messages).** Tables, transactional allocation, `handleSyncRequest`, `sync_seq` on live message push. Old clients unaffected. New `MessageRouterTest` cases: per-user monotonic allocation; replay returns exactly `(last, +∞)` ascending; `has_more` paging; idempotent resend allocates no second `sync_seq`.

**Phase 2 — client cursor + gap detection.** `sync_state` table, processor continuity check, post-auth `SyncRequest`, paging loop. Unit tests: in-order advance; gap fires exactly one request; replay dedup via `insertOrIgnore`; `SyncComplete` paging; legacy packet without `sync_seq`; cursor advances only in the item-write transaction.

**Phase 3 — integration + docs.** Device smoke: kill app mid-receive → reconnect → hole backfilled; online drop simulation → gap auto-heals. Update `B8-message-ordering.md` non-scope, `WEBSOCKET_PROTOCOL_AND_STATES.md`, PRD. **This phase's verification gates Phase 4.**

**Phase 4 — delete the message undelivered queue.** Preconditions: Phase 3 verified, no pre-sync client builds in use. Remove `undeliveredMessagesByReceiver`, `handleAuth` message replay, redrive logic. Keep `delivered`/`markDelivered`/`DELIVERY_ACK`. Tests: reconnect recovery happens via sync alone; delivered state still recorded on ack, including re-acked replays.

**Phase 5 — recall notifies join the stream; delete the recall queue.** Generalize `sync_inbox.kind`; recall acceptance writes per-receiver entries transactionally; live `RECALL_NOTIFY` carries `sync_seq`; client applies recalls idempotently with out-of-order tolerance. Then remove `pendingRecallNotifiesByReceiver`, its `handleAuth` replay, and the `recall_notified` redrive (keep `RecallNotifyAck` + recalled state). Tests: recall replay in order; recall-before-target and recall-and-target-same-batch cases; offline recall recovered purely via sync.

## Verification

- `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`
- `mvn -q test` in `mock-server`
- Manual smoke (Phase 3, repeated after Phases 4-5): offline receive → reconnect → messages appear via sync; simulated online drop → gap heals within one round-trip; offline recall → reconnect → message shown recalled without the recall queue.

## Explicit Non-Scope

- Read receipts joining the sync stream (same pattern applies when needed; not scheduled).
- `sync_inbox` retention/pruning.
- Multi-device per-device cursors (design supports it — the cursor is per local install — but no second client exists to test against).
- Any change to sender-side Outbox, heartbeat reconciliation, or display ordering.
