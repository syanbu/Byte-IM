# self-design Chat Back Navigation Development Plan

## Scope

Migrate the chat return flow to Navigation Compose while keeping the existing authentication gate and screen ViewModel responsibilities intact.

This plan is independent from B1-B5 and must not change the semantics of B3 conversation list or B4 chat history pagination.

## Files To Read First

- `app/src/main/java/com/codex/im/MainActivity.kt`
- `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- `app/src/main/java/com/codex/im/conversation/ConversationListViewModel.kt`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- `app/build.gradle`

## Current Implementation Notes

- `MainActivity` now uses Navigation Compose for authenticated screens.
- `SelfHostedImApp` keeps authentication outside the navigation graph.
- The authenticated graph starts at `conversations`.
- `ConversationListScreen` opens a conversation by navigating to `chat/{peerUserId}`.
- `ChatScreen` receives an `onBack` callback and the top Back button calls it when present.
- Android system Back and gesture Back are handled by the `NavHost` back stack.

## Development Approach

Use Navigation Compose for the authenticated app area.

1. Add the `androidx.navigation:navigation-compose` dependency.
2. Add a typed route helper for `conversations` and `chat/{peerUserId}`.
3. Render `LoginScreen` outside the `NavHost` so authentication state remains authoritative.
4. Render `ConversationListScreen` at the `conversations` route.
5. Navigate to `chat/{peerUserId}` when a conversation is opened.
6. Render `ChatScreen` at the chat route and pass the route peer id into `ChatViewModel`.
7. Make the chat top Back button call `NavController.popBackStack`.
8. Let Navigation Compose handle Android system Back and gesture Back through the route back stack.
9. Keep `ChatScreen` disposal behavior unchanged so `ChatViewModel.stop()` still runs when leaving the chat screen.

## Test Strategy

- Add unit tests for the route helper:
  - `conversations` is the start destination route.
  - Chat route pattern is `chat/{peerUserId}`.
  - Chat route creation trims peer ids.
  - Blank peer ids do not create a route.
- Keep existing `ChatViewModel.stop()` test coverage intact and run the existing chat tests to ensure collector cleanup behavior is not affected.
- If Compose UI test dependencies are not already configured, record UI manual verification for:
  - Chat top Back button.
  - Android system Back button.
  - Android gesture Back.
  - Returning to `ConversationListScreen` without losing login state.

## Out Of Scope

- Do not change message sending or receiving semantics.
- Do not change chat history pagination behavior.
- Do not change SQLite DAO code.
- Do not change WebSocket protocol code.
- Do not add Room.
- Do not add an IM SDK.
