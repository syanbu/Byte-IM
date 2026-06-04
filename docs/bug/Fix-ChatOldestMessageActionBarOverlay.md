# Fix: Chat Oldest Message Action Bar Overlay

## Status

- Status: Completed
- Completed on: 2026-06-04
- Branch: current working branch

## Observed Symptoms

When the user scrolls to the oldest loaded chat history and long-presses a
message bubble, the temporary action bar (`复制` / `撤回`) can cover the oldest
message.

Observed behavior:

- Reproduces in both single chat and group chat.
- The oldest visible bubble is partially blocked after long press.
- The action bar appears above the bubble even when the bubble is already at
  the visual top edge of the chat area.

Expected behavior:

- The action bar should behave like a floating message operation menu.
- Opening the action bar must not change the measured height of the message row,
  push the bubble, or move the avatar.
- The oldest end of the chat should reserve a small timeline area above the
  first message, using a centered gray time marker such as `15:30`.

## Related Code

Single chat and group chat share the same chat message list implementation:

- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `ChatScreen` renders messages with `LazyColumn(reverseLayout = true)`.
- Group chat uses the same `LazyColumn` and message row path, with group-specific
  state such as `state.isGroup`, sender profiles, and mention members.

The B2 single-chat status document points to the original single-chat flow:

- `docs/status/B2-single-chat.md`

## Root Cause

The first attempted fix moved the action bar below the bubble when the oldest
message was near the top of the chat area. That avoided top clipping, but it
revealed a better root cause:

- `ChatMessageActionBar` was rendered inside the message row's measured content.
- Showing the action bar changed the row height.
- Because the row height changed, Compose remeasured and relaid out the
  `LazyColumn`, which could visually push the bubble/avatar.

The stable model is therefore not "move the action bar into a different part of
the row". The action bar should be an overlay that does not participate in row
measurement.

## Implemented Fix Summary

Implemented changes:

1. Changed `ChatMessageContent` so `ChatBubbleLine` remains the measured message
   row content.
2. Rendered `ChatMessageActionBar` inside a Compose `Popup`, positioned above
   the bubble as a floating action menu.
3. Removed the near-top flip behavior, because the action bar no longer needs
   to be inserted below the bubble.
4. Added an oldest-history timeline area at the visual top of the reversed
   `LazyColumn`.
5. The timeline area displays the oldest loaded message time through
   `ChatDisplayPolicy.topTimelineTimeText(...)`, using a centered system notice
   style such as `15:30`.

## Changed Files

- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatDisplayPolicy.kt`
- `app/src/test/java/com/codex/im/chat/ChatMessageRowLayoutTest.kt`

## Verification Result

Verified with targeted automated tests on 2026-06-04:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.chat.ChatAutoScrollPolicyTest --tests com.codex.im.chat.ChatMessageRowLayoutTest --tests com.codex.im.chat.ChatMessageActionLayoutPolicyTest --tests com.codex.im.chat.ChatDisplayPolicyTest --console=plain
```

Result:

- `BUILD SUCCESSFUL`
- `ChatAutoScrollPolicyTest` passed as a related regression check for history
  pagination scroll policy.
- `ChatMessageRowLayoutTest` passed, including popup action-bar and oldest
  timeline spacer structure coverage.
- `ChatMessageActionLayoutPolicyTest` passed, confirming long-press action bar
  behavior remains wired for text and image bubbles.
- `ChatDisplayPolicyTest` passed in the targeted verification run that included
  display policy coverage.

Manual verification on 2026-06-04:

- Passed. At the oldest loaded chat history, long-pressing a message bubble
  shows the floating operation menu without covering the bubble or pushing the
  message row.
- Confirmed against the shared chat UI path used by both single chat and group
  chat.

## Manual Regression Checklist

Recommended manual checks on device/emulator:

1. Open a single chat, scroll to the oldest loaded message, long-press the oldest
   text bubble, and verify `复制` / `撤回` floats above without moving the bubble.
2. Repeat in a group chat.
3. Verify the visual top of the oldest loaded chat history shows a centered gray
   time marker.
4. Long-press a middle message and verify the action bar still appears above the
   bubble without changing row height.
5. Long-press an image message near the oldest visible end and verify the recall
   action bar also avoids covering the image bubble.
