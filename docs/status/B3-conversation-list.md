# B3 Conversation List Status

## Requirement

Show recent conversations, unread counts, and last-message preview.

## Status

Done for the current local single-chat scope.

## Completed Foundation

- `conversations` table exists.
- `ConversationDao.upsertFromMessage(message, incrementUnread)` exists.
- `ConversationDao.listConversations(limit)` exists.
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

## Next Implementation Slice

Move to B4 history message pagination. B3 can later be enhanced with search or richer display names, but the required conversation list flow is in place.
