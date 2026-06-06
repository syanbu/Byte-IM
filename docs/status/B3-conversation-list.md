# B3 Conversation List Status

## Requirement

Show recent conversations, unread counts, and last-message preview.

## Status

Done for the current local single-chat scope.

## Completed Foundation

- `conversations` table exists.
- `ConversationDao.upsertFromMessage(message, incrementUnread)` exists.
- `ConversationDao.listConversations(limit)` exists.
- `ConversationDao.listConversationsPage(beforeLastMessageTime, beforeConversationId, limit)` exists for cursor-based conversation pagination.
- `ConversationDao.clearUnread(conversationId)` exists.
- Android SQLite implementation orders conversations by `last_message_time DESC`.
- Sending a message updates conversation preview without incrementing unread count.
- Receiving a message updates conversation preview and increments unread count.
- DAO tests cover latest-message ordering and unread clearing.

## Completed User-Visible Work

- Added `ConversationListViewModel`.
- Added `ConversationListScreen`.
- Successful login/session restore now routes to the conversation list instead of directly to `ChatScreen`.
- Tapping a conversation opens `ChatScreen`.
- Entering a conversation clears that conversation's unread count.
- Incoming messages for the currently open conversation do not increment unread.
- Conversation list rows refresh from `MessageRepository.conversationUpdates`, so messages handled by the chat screen update the list preview without requiring the user to re-enter the list.
- Long-pressing a conversation row opens a floating action menu with a delete action.
- Deleting a conversation removes that row from the local conversation list and deletes local message history for that conversation.
- Conversation deletion is local-only and does not send any network packet, so it does not affect the other participant's client.
- Conversation list now loads the first page with `CONVERSATION_PAGE_SIZE = 50` and fetches older pages as the user scrolls near the bottom.
- Conversation list refreshes merge already-loaded pages with refreshed recent rows, so incoming updates do not shrink the visible list back to the first page.
- Connection/auth status and logout are only displayed on the conversation list; chat detail keeps only back navigation.
- Empty conversation lists no longer show a fixed mock peer; demo contacts live in the separate `Contacts` tab and do not create conversation rows until a real message exists.
- The demo Contacts tab now uses four mutual demo accounts:
  - `13267100423 / 123456`
  - `13800113800 / 123456`
  - `13900113900 / 123456`
  - `17724734511 / 123456`

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: tests include conversation ordering and unread clearing. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: incoming message persistence and unread increment are covered. |
| 2026-05-23 | B3 UI/navigation | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: conversation list ViewModel, fixed mock contact, navigation target, unread clearing, and active-conversation unread suppression were covered for the previous demo-entry behavior. |
| 2026-05-25 | B3 empty-list correction | `.\gradlew.bat :app:testDebugUnitTest --console=plain`; `.\gradlew.bat :app:assembleDebug --console=plain` | Passed: empty `Messages` lists stay empty, Contacts owns demo friend entry, and debug APK assembles. |
| 2026-05-23 | B3 Android build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembles with the conversation list route and chat navigation flow. |
| 2026-05-23 | B3 refresh fix | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain`; `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: conversation list refreshes from Repository conversation update events, including messages processed outside the list VM. |
| 2026-05-23 | B3 manual acceptance | User two-client manual test | Passed: conversation list preview refreshes correctly, chat detail hides connection status, and logout is only available from the conversation list. |
| 2026-06-04 | B3 local conversation deletion | `./gradlew :app:testDebugUnitTest --tests com.codex.im.storage.ConversationDaoContractTest --tests com.codex.im.storage.MessageDaoContractTest --tests com.codex.im.conversation.ConversationListViewModelTest --console=plain` | Passed: local-only conversation deletion removes the list row and target chat history while preserving other conversations. |
| 2026-06-06 | B3 conversation pagination | `.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.storage.ConversationDaoContractTest --tests com.codex.im.message.MessageRepositoryTest --tests com.codex.im.conversation.ConversationListViewModelTest --tests com.codex.im.conversation.ConversationRowLayoutTest` | Passed: cursor page ordering, repository page access, first-page load, load-more append, refresh-without-dropping-loaded-pages, and UI scroll trigger are covered. |

## Next Implementation Slice

Move to B4 history message pagination. B3 can later be enhanced with search, richer display names, or a deletion confirmation affordance, but the required conversation list flow is in place.

## Conversation List Pagination Plan

### Background

The conversation list previously read a bounded recent set through `ConversationDao.listConversations(limit)`. A temporary `ConversationListViewModel.CONVERSATION_LIST_LIMIT = 500` avoided truncating the local mock data generated by `mock-test/seed_local_messages.py`, whose default range creates 499 friend conversations.

That fixed high limit has been replaced by cursor-based pagination. The production conversation list now keeps a bounded first load and fetches older conversations as the user scrolls, so startup cost stays stable even when the local database contains thousands of conversations.

### Target Behavior

- Initial Messages tab load fetches the newest page of conversations.
- Scrolling near the bottom fetches the next older page.
- Pages are ordered by `last_message_time DESC, conversation_id ASC`.
- Refresh events keep already-loaded rows fresh without shrinking the visible list back to the first page.
- Duplicate rows are removed by `conversation_id`.
- Empty, loading, and end-of-list states are represented in `ConversationListUiState`.
- The implementation remains local-first; no server API is required for this slice.

### Proposed Data Contract

Add cursor-based pagination instead of offset pagination. Offset pagination is simpler, but it can skip or duplicate rows when new messages arrive and reorder conversations while the user is scrolling.

Cursor fields:

- `beforeLastMessageTime: Long?`
- `beforeConversationId: String?`
- `limit: Int`

Query rule:

```sql
SELECT *
FROM conversations
WHERE
  (last_message_time < ?)
  OR (last_message_time = ? AND conversation_id > ?)
ORDER BY last_message_time DESC, conversation_id ASC
LIMIT ?
```

When the cursor is null, the first page uses the same ordering without a `WHERE` clause.

The cursor for the next page is the last row currently loaded: `(lastMessageTime, conversationId)`.

### Implementation Tasks

### Task 1: Add DAO pagination API

**Files:**

- Modify: `app/src/main/java/com/codex/im/storage/ConversationDao.kt`
- Modify: `app/src/main/java/com/codex/im/storage/AndroidConversationDao.kt`
- Test: `app/src/test/java/com/codex/im/storage/ConversationDaoContractTest.kt`

Steps:

- Add `fun listConversationsPage(beforeLastMessageTime: Long?, beforeConversationId: String?, limit: Int): List<Conversation>` to `ConversationDao`.
- Keep `listConversations(limit)` as a compatibility wrapper that calls the page API with a null cursor.
- Implement the same API in `InMemoryConversationDao`.
- Implement the SQLite query in `AndroidConversationDao`.
- Add contract tests:
  - first page returns newest rows.
  - second page starts after the last row of page one.
  - equal `last_message_time` values are ordered by `conversation_id ASC`.
  - no duplicate `conversation_id` appears across pages.

### Task 2: Expose pagination through repository

**Files:**

- Modify: `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- Test: `app/src/test/java/com/codex/im/message/MessageRepositoryTest.kt`

Steps:

- Add `conversationPage(beforeLastMessageTime: Long?, beforeConversationId: String?, limit: Int)`.
- Keep `conversations(limit)` as the first-page helper for existing callers.
- Add a repository test proving the page method delegates to DAO ordering and cursor behavior.

### Task 3: Add ViewModel paging state

**Files:**

- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListViewModel.kt`
- Test: `app/src/test/java/com/codex/im/conversation/ConversationListViewModelTest.kt`

Steps:

- Extend `ConversationListUiState` with:
  - `isLoadingMore: Boolean = false`
  - `hasMoreConversations: Boolean = true`
- Add `loadMoreConversations()` to `ConversationListViewModel`.
- Use a small page size such as `CONVERSATION_PAGE_SIZE = 50`.
- Initial `refresh()` loads the first page.
- `loadMoreConversations()` uses the last loaded item as the cursor.
- Merge loaded pages with refreshed rows by `conversationId`, then sort by `lastMessageTime DESC, conversationId ASC`.
- Guard against concurrent page loads.
- Add tests:
  - initial load returns 50 when 60 exist.
  - `loadMoreConversations()` appends the remaining 10.
  - calling `loadMoreConversations()` after the final page keeps the list unchanged and sets `hasMoreConversations = false`.
  - a repository update refreshes visible rows without dropping already-loaded older rows.

### Task 4: Trigger pagination from the UI

**Files:**

- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- Test: `app/src/test/java/com/codex/im/conversation/ConversationListViewModelTest.kt`
- Optional layout test: `app/src/test/java/com/codex/im/conversation/ConversationRowLayoutTest.kt`

Steps:

- Create and remember a `LazyListState`.
- Observe the last visible item with `snapshotFlow`.
- When the last visible row is within 5 rows of `state.items.lastIndex`, call `viewModel.loadMoreConversations()`.
- Do not show instructional copy in the UI.
- Optionally show a small bottom loading indicator while `state.isLoadingMore` is true.
- Keep row keys as `conversationId`.

### Task 5: Remove the temporary high fixed limit

**Files:**

- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListViewModel.kt`
- Test: `app/src/test/java/com/codex/im/conversation/ConversationListViewModelTest.kt`

Steps:

- Replace `CONVERSATION_LIST_LIMIT = 500` with `CONVERSATION_PAGE_SIZE = 50`.
- Update the existing "more than fifty seeded conversations" test so it verifies paging instead of a single 500-row load.
- Ensure local mock data can still be browsed by repeatedly paging.

### Verification Plan

Run these commands after implementation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.storage.ConversationDaoContractTest
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.conversation.ConversationListViewModelTest
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.message.MessageRepositoryTest
```

Manual acceptance:

- Seed 499 local conversations with `mock-test/seed_local_messages.py`.
- Open the Messages tab.
- Confirm the first screen renders quickly.
- Scroll to the bottom repeatedly.
- Confirm rows continue past the first 50 and eventually reach the oldest seeded conversation.
- Send or receive a message while several pages are loaded.
- Confirm the updated conversation moves to the top without dropping already-loaded rows.
