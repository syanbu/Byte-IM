# self-design Chat Back Navigation Status

## Background

This self-design task fixes the chat detail return flow in the Android IM client. It is independent from B1-B5 and must not be treated as a B-prefixed feature.

## Current Behavior

Before the fix, users could enter `ChatScreen` from `ConversationListScreen`, but pressing the Android system Back button or using the Android back gesture exited the app to the launcher instead of returning to the conversation list.

The implementation has now been migrated to Navigation Compose. The authenticated app area uses a `NavHost` with `conversations` and `chat/{peerUserId}` routes, so system Back and gesture Back use the navigation back stack.

## Goal

The expected flow is:

`ConversationsScreen -> ChatScreen -> ConversationsScreen`

The fix must support:

- Chat page top Back button returns to the conversation list.
- Android system Back button returns to the conversation list.
- Android gesture Back returns to the conversation list.
- Login state remains authenticated after returning, and the conversation list remains visible.

## Acceptance Criteria

- Given the user is on `ConversationListScreen`, when the user opens a conversation and taps the chat top Back button, then the app returns to `ConversationListScreen` and does not exit.
- Given the user is on `ChatScreen`, when the user presses the Android system Back button, then the app returns to `ConversationListScreen` and does not exit.
- Given the user is on `ChatScreen`, when the user uses the Android back gesture, then the app returns to `ConversationListScreen` and does not exit.
- Given the user returns from `ChatScreen`, then the current authenticated session is retained and the app does not navigate back to `LoginScreen`.

## Scope Guard

- Status: Implemented.
- This is a self-design navigation fix, not a B1-B5 task.
- This task does not modify B3 conversation list semantics.
- This task does not modify B4 chat history pagination semantics.
- This task does not change message sending or receiving behavior.
- This task does not change SQLite DAO behavior.
- This task does not change the WebSocket protocol.

## Implementation Notes

- Added Navigation Compose as the login-area navigation mechanism.
- `ConversationListScreen` opens `chat/{peerUserId}` through `NavController.navigate`.
- `ChatScreen` top Back calls `NavController.popBackStack`.
- Android system Back and gesture Back are handled by the `NavHost` back stack.
- Authentication remains outside the `NavHost`, so returning from chat does not trigger logout or route to `LoginScreen`.
