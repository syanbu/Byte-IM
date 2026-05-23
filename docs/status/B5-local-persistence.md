# B5 Local Persistence Status

## Requirement

Persist messages directly with SQLite. Room is not allowed.

## Status

Done for the current message, conversation, and pending-message storage foundation.

## Completed

- Added `ImDatabaseHelper`.
- Creates `messages`, `conversations`, and `pending_messages`.
- Added indexes for conversation time, conversation server sequence, conversation last-message time, and pending retry time.
- Added DAO contracts for messages, conversations, and pending messages.
- Added in-memory DAO implementations for JVM contract tests.
- Added Android SQLite DAO implementations for runtime.
- `MessageDao.insertOrIgnore` deduplicates by `message_id`.
- `MessageDao.queryPage` supports local history pagination foundation.
- `MessageDao.markAcked` stores ACK status and `serverSeq`.
- `ConversationDao.upsertFromMessage` keeps recent-conversation state.
- `PendingMessageDao` supports pending record upsert, delete, and due-message query.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 14 unit tests including message deduplication, pagination, ACK update, conversation ordering, and unread clearing. |
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with SQLiteOpenHelper and Android DAO implementations. |

## External Verification Gap

- SQLiteOpenHelper runtime behavior has compile and JVM contract coverage, but older status notes recorded that device verification was blocked when no emulator or physical device was connected.

