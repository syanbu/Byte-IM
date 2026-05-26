# Development Constraints

## Android Message Receive Semantics

The Android client must receive and persist IM messages for the authenticated user regardless of which top-level page is visible.

- `Messages`, `Contacts`, and `Me` must all keep the login session capable of receiving WebSocket `RECEIVE_MESSAGE` packets.
- Message receiving must not depend on the currently visible page ViewModel collecting `incomingPackets`.
- A login-session scoped receiver, such as `MessagePacketProcessor`, must collect message packets and persist them through `MessageRepository`.
- Page ViewModels may refresh UI from local storage or repository update events, but they must not be the only consumer that prevents packet loss.
- Opening `Chat` after receiving a message on `Contacts` or `Me` must show the persisted message from local SQLite.
- This receive guarantee does not imply B9 retry, ACK timeout retry, pending outbox replay, or reconnect backfill.

## Android Back Navigation Semantics

The top-left back button on all pages must follow the same semantics as the Android system Back action.

- Do not implement separate back navigation logic for the top-left back button and the system Back action.
- Each page must have only one unified `onBack` / `onNavigateBack` callback.
- Both the top-left back button and `BackHandler` must call the same callback.
- Top-level pages, such as `Messages`, `Contacts`, and `Me`, must move the task to the background on system Back. They must not call `Activity.finish()`.
- Secondary or tertiary pages must navigate back to the previous level first.

Current IM page hierarchy examples:

- `Messages` top-level page: system Back moves the task to the background, like WeChat returning to the launcher without killing the app.
- `Contacts` top-level page: system Back moves the task to the background, like WeChat returning to the launcher without killing the app.
- `Me` top-level page: system Back moves the task to the background, like WeChat returning to the launcher without killing the app.
- `Me -> Profile`: both the top-left back button and system Back return to `Me`.
- `Me -> Profile -> Name`: both the top-left back button and system Back return to `Profile`.
- `Chat`: both the top-left back button and system Back return to `Messages`.

Implementation suggestions:

- No page should directly call Activity `finish()` for Back navigation.
- Top-level Back should call `Activity.moveTaskToBack(true)` instead of `finish()`.
- Internal page hierarchy should be handled by the page’s own unified back handler.
- Top-level backgrounding logic should only be triggered after confirming that there is no child page to navigate back to.
