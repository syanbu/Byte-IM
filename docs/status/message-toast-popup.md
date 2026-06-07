# Message Toast Popup Status

## Requirement

When a non-active conversation receives a new incoming `TEXT` or `IMAGE`
message, the Android app shows a top floating in-app popup.

The popup should:

- show the conversation title, avatar, message preview, and message time
- support single-chat and group-chat messages
- open the target chat when tapped
- auto-dismiss after 4 seconds
- show only one popup at a time, replacing the old popup with the newest one
- not appear for the conversation that is currently open

Design source: [`../superpowers/specs/2026-06-03-message-toast-popup-design.md`](../superpowers/specs/2026-06-03-message-toast-popup-design.md).

Implementation plan: [`../superpowers/plans/2026-06-04-message-toast-popup.md`](../superpowers/plans/2026-06-04-message-toast-popup.md).

## Status

Implemented on the Android client.

The feature is an in-app notification only. It does not add Android system
notifications, stacked notifications, swipe gestures, or long-press actions.

## Core Design

The popup is modeled as a short-lived one-shot UI event, not as a persistent
database-backed view.

`MessageRepository` builds a frozen `IncomingMessageAlert` when an incoming
message is persisted. "Frozen" means the repository copies the title, avatar,
preview, timestamp, and conversation id into a fixed alert snapshot at receive
time. The popup UI renders that snapshot directly and does not asynchronously
refresh profile or conversation data while the 4-second popup is visible.

This avoids UI jumps such as a nickname or avatar changing while the popup is
on screen, and keeps the popup independent from later profile refreshes.

## Runtime Flow

1. `MessagePacketProcessor` receives a WebSocket `RECEIVE_MESSAGE` packet and
   routes it to `MessageRepository.handlePacket`.
2. `MessageRepository.handleIncoming` parses and persists the message through
   the existing local message/conversation DAO path.
3. If the message is newly inserted, is `TEXT` or `IMAGE`, and its conversation
   is not the active conversation, the repository builds an
   `IncomingMessageAlert`.
4. The repository emits the alert through `messageAlerts`, a `SharedFlow`.
5. `MainActivity` collects `messageAlerts` inside `AuthenticatedImNavHost` and
   forwards each alert to `MessageAlertController.show`.
6. `MessageAlertController` stores the current alert in a `StateFlow`, cancels
   any previous auto-dismiss timer, and starts a new 4-second timer.
7. `MessageAlertHost` renders the current alert as a Compose overlay above the
   existing `NavHost`.
8. Tapping the popup uses `SelfHostedImRoute.Chat.createRoute(conversationId)`
   and `navigateToChat` to open the chat.
9. When the app goes to background, `MainActivity` dismisses the current popup
    on lifecycle `ON_STOP` so stale in-app popups do not reappear on return.

## Alert Data Model

`IncomingMessageAlert` contains:

- `conversationId`: canonical single or group conversation id
- `isGroup`: controls group avatar placeholder behavior
- `title`: display title frozen at receive time
- `avatarUrl`: avatar URL frozen at receive time
- `preview`: text/image preview frozen at receive time
- `rawTimestamp`: original message timestamp used for `HH:mm` display

For single chat:

- title comes from local sender profile nickname when available
- avatar comes from local sender profile
- title falls back to `senderId`
- text preview truncates after 20 characters
- image preview uses `MessageAlertPolicy.previewForImage()`

For group chat:

- title comes from the existing conversation title when available
- title falls back to packet group name, then conversation id
- avatar comes from the existing group conversation avatar
- preview is `sender: content` for text
- preview is `sender: ${MessageAlertPolicy.previewForImage()}` for image
- sender display name comes from local sender profile and falls back to
  `senderId`

## Single Alert Policy

`MessageAlertController` owns the popup replacement and lifetime policy:

- `show(alert)` replaces the current alert immediately
- every new alert resets the auto-dismiss timer
- `dismiss()` clears the alert and cancels the timer
- `openCurrent(onOpenConversation)` dismisses the alert before navigating
- no queue is kept
- no old alert is replayed to new collectors

The repository flow uses `SharedFlow` without replay because the popup is a
real-time event. It should not show an old alert after Activity or Compose state
recreation.

## UI Design

`MessageAlertHost` is placed as an overlay above the authenticated app
`NavHost`.

The popup card:

- is top-centered
- respects status bars and the current scaffold padding
- reuses `AvatarImage`
- reuses `ByteImColors`
- uses a compact row layout: avatar, title/time, preview
- animates in/out with vertical slide plus fade

The popup is intentionally independent of the current tab. It can appear above
Messages, Contacts, Me, and other non-active screens.

## Implemented Files

Android main code:

- [`../../app/src/main/java/com/buyansong/im/alert/IncomingMessageAlert.kt`](../../app/src/main/java/com/buyansong/im/alert/IncomingMessageAlert.kt)
- [`../../app/src/main/java/com/buyansong/im/alert/MessageAlertPolicy.kt`](../../app/src/main/java/com/buyansong/im/alert/MessageAlertPolicy.kt)
- [`../../app/src/main/java/com/buyansong/im/alert/MessageAlertController.kt`](../../app/src/main/java/com/buyansong/im/alert/MessageAlertController.kt)
- [`../../app/src/main/java/com/buyansong/im/alert/MessageToastPopup.kt`](../../app/src/main/java/com/buyansong/im/alert/MessageToastPopup.kt)
- [`../../app/src/main/java/com/buyansong/im/message/MessageRepository.kt`](../../app/src/main/java/com/buyansong/im/message/MessageRepository.kt)
- [`../../app/src/main/java/com/buyansong/im/MainActivity.kt`](../../app/src/main/java/com/buyansong/im/MainActivity.kt)

Tests:

- [`../../app/src/test/java/com/buyansong/im/alert/MessageAlertPolicyTest.kt`](../../app/src/test/java/com/buyansong/im/alert/MessageAlertPolicyTest.kt)
- [`../../app/src/test/java/com/buyansong/im/alert/MessageAlertControllerTest.kt`](../../app/src/test/java/com/buyansong/im/alert/MessageAlertControllerTest.kt)
- [`../../app/src/test/java/com/buyansong/im/message/MessageRepositoryIncomingAlertTest.kt`](../../app/src/test/java/com/buyansong/im/message/MessageRepositoryIncomingAlertTest.kt)

## Verification Records

2026-06-04:

- `.\gradlew.bat :app:compileDebugKotlin --console=plain` passed.
- `.\gradlew.bat :app:testDebugUnitTest --tests "com.buyansong.im.alert.*" --console=plain` passed.
- `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryIncomingAlertTest --console=plain` passed.
- `.\gradlew.bat :app:testDebugUnitTest --tests "com.buyansong.im.message.*" --console=plain` passed.
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.

2026-06-05:

- `./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.alert.MessageAlertPolicyTest" --tests "com.buyansong.im.message.MessageRepositoryIncomingAlertTest" --console=plain` passed.
- Updated the popup to display image previews as `[图片]`.
- Removed the in-app close affordance; popups now disappear by timer, opening
  the chat, replacement by a newer alert, or app background dismissal.

Full `.\gradlew.bat :app:testDebugUnitTest --console=plain` was also run. The
alert-related tests passed, but the full suite still has one unrelated local
configuration failure:

- `MockServerConfigTest.packagedAssetTargetsAdbReverseLoopbackByDefault`
- expected packaged asset host: `127.0.0.1`
- current `app/src/main/assets/mock-server.properties` host: `10.0.2.2`

That failure is caused by the current local mock-server asset configuration and
was not changed by this feature.

## Manual Verification Checklist

- On Messages tab, user B sends user A a single-chat text message: popup appears
  with B's avatar/name/content/time.
- Tap the popup: app navigates to that chat and the existing chat-open path
  clears unread count.
- User B sends an image message: popup preview uses the stable image-message
  label from `MessageAlertPolicy.previewForImage()`.
- On Contacts tab, a new message arrives: popup appears above the current
  screen.
- On Me tab, a new message arrives: popup appears above the current screen.
- While user A is already viewing B's chat, B sends a message in that same
  conversation: no popup appears.
- While user A is viewing B's chat, user C sends a message in another
  conversation: popup appears for C.
- Group message arrives: popup title is the group name and preview is
  `sender: content`.
- Do nothing for 4 seconds: popup disappears automatically.
- Multiple messages arrive quickly: only the latest popup is shown and the
  4-second timer resets.
- Put the app in background while popup is visible, then return: the old popup
  is gone.

## Current Risks

- Manual multi-device verification is still needed for exact top inset and
  overlay positioning on devices with unusual status-bar/gesture insets.
- Group title/avatar quality depends on whether the local conversation row has
  already been created or synced.
- Sender nickname/avatar quality depends on local profile cache; this is
  intentional because the alert snapshot does not fetch remote profile data.
- The popup intentionally has no visible close affordance.
