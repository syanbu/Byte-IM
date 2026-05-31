# B7 Heartbeat And Reconnect Status

## Requirement

Heartbeat and reconnect with exponential backoff and foreground/background awareness.

## Status

Done on the Android client.

## Existing Support

- Protocol command ids include `HEARTBEAT` and `HEARTBEAT_ACK`.
- Mock server can respond to heartbeat packets.
- Connection state is exposed to UI as a `StateFlow`.

## Completed

- Added `ReconnectPolicy` with delays of 1s, 2s, 4s, 8s, 16s, and a 30s cap.
- Added `ConnectionLifecycleManager` as the single Android connection supervisor. It wraps `ImConnection`, forwards packets and sends, and exposes managed connection state.
- Added `ConnectionState.Reconnecting(delayMillis, reason)` so UI state can distinguish reconnecting from connecting, authenticated, disconnected, and failed.
- After `ConnectionState.Authenticated`, the client sends `HEARTBEAT` every 15 seconds in the foreground and every 75 seconds in the background.
- `HEARTBEAT_ACK` refreshes heartbeat liveness.
- If 2 heartbeat intervals pass without an ACK, the manager disconnects the socket and reconnects through `ReconnectPolicy`.
- `Disconnected`, `Failed`, heartbeat timeout, and failed WebSocket writes automatically reconnect while the app process is running, including the 75s-heartbeat background state.
- If `connection.send(...)` returns `false` for a message or heartbeat packet, the manager treats it as a transport failure, disconnects the stale socket, enters `Reconnecting(delayMillis, "send failed")`, and reconnects through the same backoff policy instead of waiting for the next heartbeat timeout or screen lifecycle check.
- `MainActivity` registers a `ConnectivityManager.NetworkCallback`. When Android reports internet-capable network availability, `ConnectionLifecycleManager.notifyNetworkAvailable()` cancels any pending reconnect backoff delay and reconnects immediately.
- Authentication success resets the reconnect policy back to the 1s delay.
- `ConversationListViewModel` and `ChatViewModel` now treat `Reconnecting` as an active lifecycle state instead of issuing duplicate direct connects.
- `MainActivity` wires a shared `ConnectionLifecycleManager` into message repository and ViewModels.
- The Messages page renders connection status only when communication is not healthy, for example `Connection: Disconnected`, `Connection: Connecting`, `Connection: Reconnecting in 4s`, or `Connection: Failed: ...`; healthy `Connected`/`Authenticated` states are hidden.
- The Messages page renders local conversations before remote profile refresh completes, so a stopped server or slow profile request does not block the local list while reconnect status is shown.

## Foreground/Background Strategy

The Android client uses a lower-frequency background keepalive policy:

- Foreground: heartbeat runs every 15 seconds after authentication, and disconnect/failure paths reconnect automatically.
- Background: the WebSocket connection remains open, heartbeat slows to every 75 seconds, and disconnect/failure paths still reconnect automatically.
- Return to foreground: the manager checks the current socket state. If still authenticated, heartbeat switches back to every 15 seconds. If disconnected or failed, reconnect scheduling starts.
- Write failure: a failed WebSocket send now triggers immediate reconnect scheduling, so pending sender messages do not need a Back-to-Messages route transition before the connection is repaired.
- Network restore: if reconnect is already waiting in a long backoff window, Android network availability wakes the manager and starts a fresh connect attempt immediately.
- Top-level Back from Messages, Contacts, or Me moves the task to the background with `Activity.moveTaskToBack(true)` instead of finishing the Activity, so the B7 background connection strategy can continue running while the process remains alive.

This deliberately avoids B8/B9 behavior: reconnect does not replay pending messages, reorder messages, or retry unacked messages.

## Remaining Risks

- Manual emulator or device validation with real network toggling is still recommended.
- No 50-cycle manual disconnect/reconnect soak record has been captured yet.
- Android may still restrict or kill background work depending on emulator/device policy; B13 push remains the later-stage answer for killed-process delivery.
- Manual emulator verification should include Back from Messages, Contacts, and Me confirming the app returns to the launcher without Activity destruction.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-25 | B7 focused unit tests | `.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.connection.ReconnectPolicyTest --tests com.codex.im.connection.ConnectionLifecycleManagerTest --console=plain` | Passed: reconnect delay sequence, reset, heartbeat send, ACK liveness, heartbeat timeout reconnect, auth reset, stop cancellation, background 75s heartbeat, background reconnect, and foreground 15s restore behavior. |
| 2026-05-25 | Android unit tests | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | Passed. |
| 2026-05-25 | Android debug build | `.\gradlew.bat :app:assembleDebug --console=plain` | Passed. |
| 2026-05-25 | Mock server tests | `mvn -q test` in `mock-server` | Passed. |
| 2026-05-31 | Send-failure/network-restore reconnect regression | `.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.connection.ConnectionLifecycleManagerTest --console=plain` | Passed: failed packet send disconnects the stale socket, enters `Reconnecting(1s, "send failed")`, reconnects with the existing token provider/backoff path, and network availability cancels a pending backoff delay for immediate reconnect. |

