# B4 History Pagination Status

## Requirement

Pull/load more historical messages.

## Status

Not started as a user-visible feature.

## Existing Foundation

- `MessageDao.queryPage(conversationId, beforeTime, limit)` exists.
- Android SQLite implementation supports `beforeTime` cursor pagination.
- In-memory DAO tests cover page query before a cursor.
- `ImCommand.HISTORY_QUERY` and `ImCommand.HISTORY_RESULT` are reserved in the protocol enum.

## Missing Work

- Chat screen should initially load the latest 20 local messages.
- Pulling to the top should load 20 earlier local messages.
- If local history is insufficient, the client should request server history.
- Mock server should handle `HISTORY_QUERY`.
- Client should handle `HISTORY_RESULT`, batch insert messages, and merge without duplicates.
- UI should avoid flicker and duplicate rows when loading more.
- Offline mode should still show local history.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: local DAO pagination contract is covered. |

