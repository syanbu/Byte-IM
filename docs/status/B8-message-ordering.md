# B8 Message Ordering Status

## Requirement

Client generates sequence values, server confirms, and receive side handles out-of-order messages.

## Status

Partial.

## Completed Foundation

- Added `SeqGenerator`.
- Outgoing messages include per-conversation `clientSeq`.
- `MESSAGE_ACK` stores `serverSeq` locally.
- Incoming messages persist optional `serverSeq`.
- SQLite schema has an index for `conversation_id, server_seq`.

## Missing Work

- Receive-side reorder behavior is not implemented.
- Chat display currently uses local message page order by `createdAt`, not full `serverSeq` ordering.
- No test or debug tool for 100-message ordering.
- No simulated out-of-order delivery verification.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: ACK updates local message status and stores `serverSeq`. |

