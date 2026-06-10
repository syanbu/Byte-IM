# B2 Single-Chat Status

## Requirement

Single-chat text messages over a WebSocket long connection with real-time send and receive.

## Status

Done for the current local mock-server path.

## Completed

- Added `ImConnection` interface with connection state and incoming-packet flows.
- Added `OkHttpImConnection`.
- WebSocket sends protocol `AUTH` after opening.
- Incoming binary WebSocket frames are decoded with the custom protocol codec.
- Added `ConnectionState.Authenticated` after receiving protocol `AUTH_ACK`.
- Added `MessageIdGenerator` and `SeqGenerator`.
- `MessageRepository.sendText` stores outgoing messages as `SENDING`, updates conversation preview, writes pending records, and sends `SEND_MESSAGE`.
- `MessageRepository.handlePacket` handles `MESSAGE_ACK` and `RECEIVE_MESSAGE`.
- ACK handling marks messages as `SENT`, stores `serverSeq`, and removes pending records.
- Incoming messages are persisted and increment conversation unread count.
- Added minimal `ChatViewModel`.
- Added minimal `ChatScreen` with peer id input, message list, send box, connection status, and logout.
- Login success currently routes directly from login to chat.
- Default peer is temporarily hard-coded from the logged-in account for local demo convenience.
- Single-chat conversation ids are canonicalized by sorting the two user ids.
- Fixed chat text-bubble layout so long outgoing and incoming text wraps onto the next line instead of expanding horizontally and pushing the avatar out of the row. This change was made after reproducing that longer single-chat and group-chat text messages could hide the avatar even though avatar data was present.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 4 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 19 unit tests including AUTH packet construction. |
| 2026-05-22 | Phase 4 | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with OkHttp WebSocket connection implementation. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 22 unit tests including send-text persistence, SEND_MESSAGE packet dispatch, MESSAGE_ACK status update, pending deletion, incoming persistence, and unread increment. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with message repository core. |
| 2026-05-22 | Android Chat UI | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 25 unit tests including ChatViewModel WebSocket connect, send refresh, and incoming packet refresh. |
| 2026-05-22 | Android Chat UI | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with minimal chat screen wired to local mock WebSocket URL. |
| 2026-05-22 | Manual A/B Chat Smoke Test | Two Android emulators + local mock server | Passed by user verification: both `13800113800 -> 13900113900` and `13900113900 -> 13800113800` messages were ACKed and forwarded after both clients were online. |
| 2026-05-22 | WebSocket Auth State Display | `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.connection.ConnectionStateReducerTest --tests com.buyansong.im.chat.ChatViewModelTest --console=plain`; `mvn -q test -Dtest=MessageRouterTest` in `mock-server` | Passed: Android maps `AUTH_ACK` to `ConnectionState.Authenticated`, chat UI exposes connection status text, and mock-server records authenticated status after sending AUTH_ACK. |
| 2026-06-06 | Chat Text Bubble Width Guard | `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatTextBubbleLayoutPolicyTest --console=plain` | Passed: long text bubbles are width-constrained to 72% of the available row width so content wraps instead of pushing single-chat or group-chat avatars out of the row. |

## Remaining Risks

- Offline sending, reconnect resend, and richer conversation behavior belong to later B7/B9 work.
- The current screen is a minimal demo chat screen, not the final conversation-list-first IM flow.

## First-message anchor

In the chat screen, messages are laid out **top-to-bottom** (the message
`LazyColumn` uses `reverseLayout = false`). The first message of a
brand-new conversation therefore appears at the top of the visible area,
and subsequent messages stack immediately below it. As soon as the
conversation has more messages than fit on one screen, the latest
message is scrolled into view at the bottom of the list, just above the
input bar.

This is driven by
[`ChatAutoScrollPolicy`](../../app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt):

- [`shouldScrollToLatest(previousLatestMessageId, latestMessageId)`](../../app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt) — the existing boolean that returns
  `true` whenever the latest message id changes (including the very
  first message arriving in an empty conversation).
- [`scrollToLatestIndex(messageCount)`](../../app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt) — the helper that returns the last index of the
  list. `ChatScreen` calls
  `listState.animateScrollToItem(ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size))`
  whenever `shouldScrollToLatest` is `true`, so the latest message is
  always in view at the bottom of the list.

The `history-loader` block (which displays the time of the oldest
message and any "no more history" status) is rendered as the *first*
item of the `LazyColumn` content, before `itemsIndexed`, so it stays
at the visual top of the column in the new top-to-bottom layout.
