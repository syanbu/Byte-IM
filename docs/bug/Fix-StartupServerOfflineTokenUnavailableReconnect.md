# Fix: Startup Server Offline Token Unavailable Reconnect

## Status

- Status: Completed
- Completed on: 2026-06-04
- Branch: current working branch

## Problem Summary

When the Android app was already started and authenticated from a stored local session, but the mock server was not running, manually starting the server later did not make the app actively link to the server.

The visible symptom looked like heartbeat/reconnect did not work. The actual failure happened before heartbeat could start:

1. The stored access token could be expired.
2. `ConnectionLifecycleManager` called `tokenProvider`.
3. `MainActivity` wires `tokenProvider` to `repository.ensureValidSession()?.accessToken`.
4. `ensureValidSession()` tried to refresh the expired access token with the stored refresh token.
5. Because the server was offline, refresh could not return a new access token.
6. The old manager treated the null token result as terminal, cleared `requestedToken`, and moved to `Disconnected`.
7. Later, when the server was manually started, the manager no longer had a token hint and did not continue the reconnect flow.

Heartbeat was not the root cause. Heartbeat is intentionally started only after the WebSocket session reaches `ConnectionState.Authenticated`.

## Token Semantics

`ConnectionLifecycleManager.requestedToken` is not a refresh token.

It is an access-token hint for the current connection lifecycle:

- `connect(token)` stores the initial access token hint.
- reconnect, heartbeat timeout, send failure, and network recovery use this value to keep the connection lifecycle alive.
- the actual refresh token stays inside `AuthRepository` and `TokenStore`.
- `ConnectionLifecycleManager` does not perform token rotation directly.

The token rotation flow is owned by `AuthRepository.ensureValidSession()`:

- if the access token is still valid, return the current session.
- if the access token is expired, use the stored refresh token to request a new access token and rotated refresh token.
- if the refresh request fails due to network/server outage, keep the stored session and return null.
- if the refresh token is truly invalid or expired, clear the stored session.

## Root Cause

The old `ConnectionLifecycleManager.attemptConnect(...)` behavior was:

```kotlin
if (resolvedToken.isNullOrBlank()) {
    requestedToken = null
    mutableStates.value = ConnectionState.Disconnected
    return@launch
}
```

This made a temporary token-provider failure look like a deliberate end of the connection lifecycle.

For this bug, `resolvedToken == null` meant "server is temporarily unavailable, so the refresh call cannot complete." It did not necessarily mean "the user is logged out" or "the refresh token is invalid."

## Implemented Fix Summary

`ConnectionLifecycleManager` now treats a temporary null token from `tokenProvider` as reconnectable:

1. It keeps the existing access-token hint.
2. It enters `ConnectionState.Reconnecting(delayMillis, "token unavailable")`.
3. It retries through the existing `ReconnectPolicy` exponential backoff.
4. Each retry calls `tokenProvider` again, which calls `ensureValidSession()` again.
5. Once the server is available and token refresh succeeds, the manager connects with the fresh access token.
6. WebSocket `AUTH` can then complete, the state reaches `Authenticated`, and heartbeat starts normally.

The reconnect job also clears its own `reconnectJob` reference before calling the next `attemptConnect(...)`, so repeated token-provider failures can schedule the next backoff cycle instead of getting blocked by the previous job still being active.

## Result

After the fix:

- Starting the app before the server no longer permanently breaks the connection lifecycle.
- If access token refresh is temporarily unavailable because the server is offline, the client keeps retrying.
- Manually starting the server allows a later backoff attempt to refresh the token and connect.
- Heartbeat still only starts after successful `AUTH_ACK`, preserving the intended authenticated-state boundary.
- Logout or explicit `disconnect()` still stops the lifecycle and clears the token hint.

## Verification Result

Verified on 2026-06-04:

- `bash ./gradlew :app:testDebugUnitTest --tests com.buyansong.im.connection.ConnectionLifecycleManagerTest --console=plain`
  - Passed.
  - Covers initial connect when `tokenProvider` is temporarily unavailable.
  - Covers reconnect when `tokenProvider` is temporarily unavailable after a prior authenticated session.
- `./gradlew :app:assembleDebug --console=plain`
  - Passed.

Full Android unit test run:

- `bash ./gradlew :app:testDebugUnitTest --console=plain`
  - Failed only on existing unrelated `MockServerConfigTest.packagedAssetTargetsAdbReverseLoopbackByDefault`.
  - The failure is caused by packaged `mock-server.properties` currently using `host=10.0.2.2` while the test expects `127.0.0.1`.
  - Connection/auth related tests passed.

## Related Files

- `app/src/main/java/com/buyansong/im/connection/ConnectionLifecycleManager.kt`
- `app/src/test/java/com/buyansong/im/connection/ConnectionLifecycleManagerTest.kt`
- `docs/status/B7-heartbeat-reconnect.md`
- `docs/bug/Fix-WebSocketAuthFailureAndUnauthenticatedMessageHandling.md`
