# Messages Top Bar Unread Menu Design

## Background

The current Messages tab already renders conversation rows and keeps a total unread badge in the bottom navigation. The requested UI change is to make the Messages title centered, show the same unread count in the title, and add a WeChat-like plus entry on the right side of the top bar.

The first version of this change was UI-scoped. The next iteration makes `发起群聊` create a local group conversation from selected Contacts, then returns the user to Messages where the new group appears in the conversation list.

This still does not implement full B10 group-message protocol support. It creates the local conversation entry and navigation flow first, while leaving real group message send/receive, group membership sync, and @ mention semantics for a later B10 slice.

## Goals

- Center the Messages page title at the top of the Messages screen.
- Show total unread count in the title when there are unread messages, for example `Message(18)`.
- Keep the title unread count identical to the bottom Messages tab unread badge.
- Add a circular plus button on the right side of the top bar.
- Show a dropdown menu below the plus button with two text actions:
  - `发起群聊`
  - `添加朋友`
- Let `发起群聊` open a contact-selection page.
- Let the user select Contacts and create a local group conversation.
- Show the new group conversation in the Messages conversation list.
- Add a divider between the Messages top navigation bar and the conversation list.

## Non-Goals

- Implementing real group message send/receive over WebSocket.
- Implementing server-backed group creation or group membership sync.
- Implementing group chat screen behavior.
- Implementing @ mention behavior.
- Implementing actual friend adding.
- Changing per-conversation unread-count behavior.
- Changing bottom navigation tab order or tab routing.
- Changing message receive semantics or SQLite persistence.

## Data Source

The top title should reuse the same total unread count already used by the bottom Messages tab badge:

- `MessageRepository.totalUnreadCount()`
- `MessagesTabUnreadBadgeController.unreadCount`
- `MessagesTabUnreadBadgePolicy.badgeTextForCount(...)`

`MainActivity` already owns `unreadMessagesCount` inside `AuthenticatedImNavHost`. That value should be passed into `ConversationListScreen` as a parameter. This keeps the title count and bottom badge count synchronized from one source of truth.

## Title Display Rules

The title should use a small display policy so the formatting is testable outside Compose.

| Total unread count | Title |
|---:|---|
| `0` or less | `Message` |
| `1` through `99` | `Message(n)` |
| `100` or more | `Message(99+)` |

The `99+` cap should match the existing bottom badge rule.

## Layout

Replace the current left-aligned `Text("Messages")` header in `ConversationListScreen` with a top bar.

Suggested structure:

- Root top-bar container: `Box`, full width, height around `56.dp`.
- Center title: aligned to `Alignment.Center`.
- Right action area: aligned to `Alignment.CenterEnd`.
- Right button: circular visual shape, plus icon inside.
- Dropdown menu: anchored to the plus action area.

The centered title must remain visually centered even when the right-side plus button is present.

Add a `HorizontalDivider` immediately below the top bar. This creates a clear visual boundary between the navigation bar and the conversation list, matching the requested Messages screenshot.

## Interaction

The plus button toggles a dropdown menu.

- Tap plus: open menu.
- Tap plus again or outside menu: close menu.
- Tap `发起群聊`: close menu and navigate to the group-create contact picker.
- Tap `添加朋友`: close menu.

`添加朋友` remains a no-op callback for this pass.

## Group Creation Flow

The first group-creation pass is local-only:

1. User taps Messages top-bar plus.
2. User taps `发起群聊`.
3. App opens a group-create page that lists Contacts using the same demo contact source as the Contacts tab.
4. User selects one or more Contacts.
5. User taps a create action.
6. App inserts a local group conversation and navigates back to Messages.
7. Messages refreshes from the repository conversation-update flow and shows the group conversation.

The group conversation row should use:

- `conversationId`: `group:<creatorUserId>:<createdAt>:<memberHash>`
- `peerId`: same as `conversationId`
- `peerName`: `群聊(n)` where `n` is the current user plus selected members
- `lastMessagePreview`: `已创建群聊`
- `lastMessageTime`: creation time
- `unreadCount`: `0`

This is intentionally modeled as a local conversation row instead of a real chat target. Opening/sending group messages remains out of scope until B10 is implemented.

## Implementation Plan

### Task 1: Persist the Design Update

- Update this document to describe the local group creation flow and the top-bar divider.
- Keep the scope explicit: local group conversation creation now; real group chat protocol later.

### Task 2: Add Local Group Conversation Persistence

- Extend `ConversationDao` with `upsertConversation(conversation: Conversation)`.
- Implement it in `InMemoryConversationDao` and `AndroidConversationDao`.
- Add `MessageRepository.createLocalGroupConversation(...)`.
- Emit `conversationUpdates` after the group conversation is inserted.
- Test that creating a group conversation adds a `group:` conversation row with `unreadCount == 0`.

### Task 3: Add Group Create UI State

- Create `GroupCreateViewModel` in a new `com.codex.im.group` package.
- Load Contacts through `DemoContactResolver` and `ProfileRepository`, matching the Contacts tab.
- Track selected contact IDs.
- Enable creation only when at least one contact is selected.
- Expose a completion flag or created conversation id after successful creation.

### Task 4: Add Group Create Screen

- Create `GroupCreateScreen`.
- Show a title such as `发起群聊`.
- Render Contacts as selectable rows.
- Use text-only actions where possible, matching the current Compose style.
- On create, call the ViewModel and navigate back.

### Task 5: Wire Navigation and Messages Top Bar

- Add a route such as `group-create`.
- Pass `onStartGroupChat` into `ConversationListScreen`.
- From the top-bar menu, invoke `onStartGroupChat`.
- Add `HorizontalDivider` under the Messages top bar.
- After group creation, navigate back to Messages.

### Task 6: Verify

- Run targeted JVM tests for:
  - group conversation creation
  - group create ViewModel selection/create behavior
  - existing Messages title policy
- Run a related compile/test command to ensure Compose and resources compile.

## Files

Expected files to modify:

- `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
  - Add top bar composable.
  - Add `unreadCount` parameter.
  - Add title/menu policy if scoped to the conversation UI package.
  - Add divider below the top bar.
  - Invoke `onStartGroupChat` when `发起群聊` is tapped.
- `app/src/main/java/com/codex/im/MainActivity.kt`
  - Pass `unreadMessagesCount` into `ConversationListScreen`.
  - Add group-create navigation route and screen.
- `app/src/main/java/com/codex/im/SelfHostedImRoute.kt`
  - Add a group-create route.
- `app/src/main/java/com/codex/im/storage/ConversationDao.kt`
  - Add direct conversation upsert support.
- `app/src/main/java/com/codex/im/storage/AndroidConversationDao.kt`
  - Persist direct conversation upserts.
- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
  - Create local group conversations and notify conversation observers.
- `app/src/main/java/com/codex/im/group/GroupCreateViewModel.kt`
  - Load contacts, track selected contacts, and create a local group.
- `app/src/main/java/com/codex/im/group/GroupCreateScreen.kt`
  - Render the group contact picker.

Expected tests:

- `app/src/test/java/com/codex/im/conversation/MessageTopBarTitlePolicyTest.kt`
  - Covers `Message`, `Message(18)`, and `Message(99+)`.
- `app/src/test/java/com/codex/im/message/MessageRepositoryGroupConversationTest.kt`
  - Covers local group conversation creation.
- `app/src/test/java/com/codex/im/group/GroupCreateViewModelTest.kt`
  - Covers selection and local group creation from Contacts.

## Verification

Run the targeted JVM test for the title policy:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.conversation.MessageTopBarTitlePolicyTest --console=plain
```

Then run the broader related JVM tests if time allows:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.MessagesTabUnreadBadgePolicyTest --tests com.codex.im.conversation.MessageTopBarTitlePolicyTest --console=plain
```

For the group creation pass, run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.message.MessageRepositoryGroupConversationTest --tests com.codex.im.group.GroupCreateViewModelTest --tests com.codex.im.conversation.MessageTopBarTitlePolicyTest --console=plain
```
