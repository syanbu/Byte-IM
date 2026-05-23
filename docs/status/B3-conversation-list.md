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
- The fixed mock peer from `DefaultPeerResolver` remains visible when there are no conversations yet.
- The existing two demo accounts remain compatible:
  - `13800113800 / 123456`
  - `13900113900 / 123456`

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 2 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: tests include conversation ordering and unread clearing. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: incoming message persistence and unread increment are covered. |
| 2026-05-23 | B3 UI/navigation | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: conversation list ViewModel, fixed mock contact, navigation target, unread clearing, and active-conversation unread suppression are covered. |
| 2026-05-23 | B3 Android build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembles with the conversation list route and chat navigation flow. |
| 2026-05-23 | B3 refresh fix | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain`; `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: conversation list refreshes from Repository conversation update events, including messages processed outside the list VM. |

## Next Implementation Slice

Move to B4 history message pagination. B3 can later be enhanced with search or richer display names, but the required conversation list flow is in place.
