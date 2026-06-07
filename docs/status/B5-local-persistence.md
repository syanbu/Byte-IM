# B5 Local Persistence Status

## Requirement

Persist messages directly with SQLite. Room is not allowed.

## Status

Done for the current message, conversation, pending-message storage foundation, and active-account local storage isolation.

## Completed

- Added `ImDatabaseHelper`.
- Creates `messages`, `conversations`, and `pending_messages`.
- Added indexes for conversation time, conversation server sequence, conversation last-message time, and pending retry time.
- Added DAO contracts for messages, conversations, and pending messages.
- Added in-memory DAO implementations for JVM contract tests.
- Added Android SQLite DAO implementations for runtime.
- Added Android instrumentation DAO tests for the real SQLite implementations.
- Added account-scoped SQLite database naming for authenticated IM storage.
- Authenticated Android sessions now create message, conversation, pending-message, group, and profile repositories from the current `session.userId` database.
- Switching from account A to account B keeps A's local history on disk but prevents B from listing A's conversations, unread counts, or pending outbox rows.
- `MessageDao.insertOrIgnore` deduplicates by `message_id`.
- `MessageDao.queryPage` supports local history pagination foundation.
- B4 local history pagination now uses `MessageDao.queryPage(conversationId, beforeTime, limit)` as the SQLite-backed source for initial chat history and load-more pages.
- `MessageDao.markAcked` stores ACK status and `serverSeq`.
- `ConversationDao.upsertFromMessage` keeps recent-conversation state.
- `PendingMessageDao` supports pending record upsert, delete, and due-message query.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 14 unit tests including message deduplication, pagination, ACK update, conversation ordering, and unread clearing. |
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with SQLiteOpenHelper and Android DAO implementations. |
| 2026-05-23 | B4 local pagination integration | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain`; `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: chat history pagination consumes the existing SQLite DAO paging path without Room. |
| 2026-05-24 | Android SQLite DAO compile check | `gradle-9.0.0\bin\gradle.bat :app:compileDebugAndroidTestKotlin --console=plain` | Passed: instrumentation tests for Android MessageDao, ConversationDao, and PendingMessageDao compile. |
| 2026-06-04 | Account switch local isolation | `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.storage.AccountScopedDatabaseNameTest --console=plain` | Passed: account-scoped database names are stable, distinct per user, and sanitized for safe local SQLite filenames. |
| 2026-06-04 | Account-scoped storage build | `./gradlew :app:assembleDebug --console=plain` | Passed: debug APK assembles after session-scoped repository and SQLite helper updates. |

## External Verification Gap

- SQLiteOpenHelper runtime behavior now has JVM contract coverage and Android instrumentation test coverage. Instrumentation test execution still requires an emulator or physical device.
