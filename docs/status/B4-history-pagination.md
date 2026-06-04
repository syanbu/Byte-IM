# B4 History Pagination Status

## Requirement

Pull/load more historical messages.

## Status

Partial.

Done for the current local SQLite-backed chat history path. Server-backed history fetch is still deferred and must use `HISTORY_QUERY` / `HISTORY_RESULT` when implemented.

Design notes for review: [`../feature-notes/B4-history-pagination-design-notes.md`](../feature-notes/B4-history-pagination-design-notes.md).

## Existing Foundation

- `MessageDao.queryPage(conversationId, beforeTime, limit)` exists.
- Android SQLite implementation supports `beforeTime` cursor pagination.
- In-memory DAO tests cover page query before a cursor.
- `ImCommand.HISTORY_QUERY` and `ImCommand.HISTORY_RESULT` are reserved in the protocol enum.

## Completed Local Pagination Work

- `ChatUiState` now exposes `messages`, `isLoadingMore`, `hasMoreLocal`, and `errorMessage`.
- `ChatViewModel.start()` loads only the latest 20 local messages on first entry.
- `ChatViewModel.loadMoreHistory()` uses the earliest visible message `createdAt` as the `beforeTime` cursor and loads 20 earlier local messages.
- Loaded pages are merged by `messageId`, keeping the newest-first order expected by the current `LazyColumn(reverseLayout = true)` display.
- Repeated load-more calls do not duplicate visible messages.
- When local history is exhausted, `hasMoreLocal` becomes false and the UI stops offering another local page.
- Realtime incoming messages refresh the visible list without dropping history that the user already loaded.
- `ChatScreen` automatically triggers `loadMoreHistory()` when the user scrolls within 6 items of the older-message end of the chat list.
- `isLoadingMore` prevents duplicate automatic loads, and `hasMoreLocal=false` stops further local loads when SQLite history is exhausted.
- The current chat ViewModel uses a grow-only in-memory cache for loaded messages, capped at 2,000 messages. Reaching the cap stops automatic local paging until the user leaves and reopens the chat.
- Returning from chat to the conversation list disposes `ChatScreen` and calls `ChatViewModel.stop()`, cancelling chat-page collectors so this in-memory message list is no longer retained by the chat UI; SQLite remains the durable source of history.
- Offline local history remains readable because the pagination path is DAO-backed and does not require WebSocket connectivity.

## Remaining Work

- If remote history is required after local exhaustion, add a clear repository/sync entry that emits `HISTORY_QUERY`.
- Mock server should handle `HISTORY_QUERY`.
- Client should handle `HISTORY_RESULT`, batch insert messages, and merge without duplicates.
- Server-backed history must stay on the existing custom protocol path and must not bypass `ImCommand.HISTORY_QUERY` / `ImCommand.HISTORY_RESULT`.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: local DAO pagination contract is covered. |
| 2026-05-23 | B4 ViewModel TDD red check | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Failed as expected before implementation: `loadMoreHistory` and `hasMoreLocal` were missing. |
| 2026-05-23 | B4 local history pagination | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Passed: initial 20-message load, `beforeTime` load-more merge, duplicate prevention, local exhaustion, and realtime preservation are covered. |
| 2026-05-23 | App unit tests | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: app unit test suite stayed green after B4 local pagination. |
| 2026-05-23 | Android build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembles with the B4 chat pagination UI. |
| 2026-05-23 | B4 automatic older-history load | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatAutoScrollPolicyTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Passed: old-message-end trigger, loading debounce, local-end stop, latest-message auto-scroll, and ViewModel pagination behavior are covered. |
| 2026-05-23 | B4 memory guard | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatAutoScrollPolicyTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Passed: 6-item auto-load threshold and 2,000-message in-memory cap are covered. |
| 2026-05-23 | B4 chat lifecycle cleanup | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatAutoScrollPolicyTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Passed: `ChatViewModel.stop()` cancels incoming-packet collection after chat disposal. |
