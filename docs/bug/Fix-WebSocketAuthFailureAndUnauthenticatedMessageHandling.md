# Fix: WebSocket Auth Failure And Unauthenticated Message Handling

## Status

- Status: Completed
- Completed on: 2026-05-28
- Branch: current working branch

## Implemented Fix Summary

This bug has been fixed on both the Android client and the mock server.

Implemented changes:

1. Added protocol-level `AUTH_NACK(reason)` support on Android and mock-server.
2. Mock server now sends `AUTH_NACK` and immediately closes the WebSocket when `AUTH` fails.
3. Mock server now rejects unauthenticated `SEND_MESSAGE`, `DELIVERY_ACK`, and `HEARTBEAT` packets.
4. Removed the server fallback that trusted `senderId` or `receiverId` from packet bodies before authentication.
5. Added `AuthRepository.ensureValidSession()` as the single client entry point for obtaining a currently valid session before WebSocket connect or reconnect.
6. Refactored `ConnectionLifecycleManager` so every connect and reconnect resolves its token through the unified session-validation path instead of reusing a stale cached access token.
7. Added client handling for `AUTH_NACK` so auth rejection becomes an explicit connection failure reason.
8. Added observable session-state propagation so unrecoverable auth failure clears local session state and drives the login UI back to logged-out state.

## Result

After the fix:

- Expired access tokens no longer leave the client reconnect loop stuck on stale WebSocket auth.
- Heartbeat resumes only after the connection re-authenticates successfully, which matches the intended state machine.
- Unauthenticated sockets can no longer send business traffic to the mock server.
- If refresh cannot recover the session, the stored session is cleared and the Android login state is reset.

## Verification Result

Verified with targeted automated tests on 2026-05-28:

- Android:
  - `bash ./gradlew :app:testDebugUnitTest --tests 'com.buyansong.im.auth.AuthRepositoryTest' --tests 'com.buyansong.im.auth.LoginViewModelTest' --tests 'com.buyansong.im.connection.ConnectionLifecycleManagerTest' --tests 'com.buyansong.im.connection.ConnectionStateReducerTest'`
- Mock server:
  - `mvn -q -Dtest=WebSocketFrameHandlerTest,MessageRouterTest test`

Verified behaviors include:

- `AUTH_NACK(TOKEN_EXPIRED|TOKEN_INVALID|TOKEN_MISSING)` handling path
- reconnect using refreshed token instead of stale cached token
- stopping reconnect when no valid session can be recovered
- auto-resetting UI auth state after session clear
- rejecting unauthenticated `SEND_MESSAGE`
- rejecting unauthenticated `HEARTBEAT`

## Goal

Define the concrete fix flow for the bug where:

- WebSocket auth fails after the access token expires.
- Android clients stop sending heartbeats because they never re-enter `Authenticated`.
- The mock server still accepts business packets from unauthenticated WebSocket connections.

This document is the implementation guide for the fix, not only a root-cause analysis.

## Observed Symptoms

The issue was reported with the following mock-server logs:

```text
2026-05-28 09:49:58.703 [IM] AUTH rejected invalid or expired token
2026-05-28 09:49:58.703 [IM] AUTH rejected invalid or expired token
2026-05-28 09:55:26.577 [IM] SEND_MESSAGE sender=13900113900 receiver=13800113800 conversationId=single:13800113800:13900113900 messageId=13900113900-1779933324595-000001 clientSeq=1 serverSeq=1779845783814 content=1
2026-05-28 09:55:26.582 [IM] MESSAGE_ACK skipped sender offline sender=13900113900 messageId=13900113900-1779933324595-000001
2026-05-28 09:55:26.584 [IM] RECEIVE_MESSAGE queued receiver offline receiver=13800113800 messageId=13900113900-1779933324595-000001 serverSeq=1779845783814
```

User-visible symptoms:

- Two Android emulators stopped sending heartbeat traffic.
- WebSocket authentication had already failed on the server.
- A sender could still trigger `SEND_MESSAGE` handling even though the connection was not authenticated.
- The sender did not receive `MESSAGE_ACK`.
- The receiver was treated as offline and the message was queued.

## Problem Summary

The current behavior breaks in three places:

1. The Android client can reconnect with a stale in-memory access token.
2. The mock server rejects `AUTH` but does not explicitly terminate the unauthenticated connection.
3. The mock server still processes `SEND_MESSAGE` and other business packets even when the connection never authenticated.

Because heartbeat starts only after `Authenticated`, the visible symptom is "heartbeat stopped". The actual failure starts earlier at WebSocket authentication.

## Selected Fix Strategy

Use one combined strategy across client and server:

1. Add `AUTH_NACK(reason)` on the WebSocket protocol.
2. Close the WebSocket immediately after sending `AUTH_NACK`.
3. Reject all business packets from unauthenticated connections.
4. Introduce one client-side entry point that always returns a currently valid access token before WebSocket connect or reconnect.
5. Refresh only for recoverable auth failures, specifically `TOKEN_EXPIRED`.

This is the selected final approach. The fix should not be implemented as isolated partial changes.

## Fix Flow

### Phase 1: Tighten The Server Auth Boundary

Update the mock server so that authentication becomes a hard gate.

Required changes:

1. Extend the protocol with `AUTH_NACK`.
2. Include a reason code in the `AUTH_NACK` body.
3. In `handleAuth`, if token verification fails:
   - send `AUTH_NACK`
   - log the reason
   - close the WebSocket
4. Do not leave the connection in an unauthenticated but still-open state.

Recommended reason codes:

- `TOKEN_EXPIRED`
- `TOKEN_INVALID`
- `TOKEN_MISSING`

Expected result:

- The client can distinguish auth failure from generic network failure.
- The server no longer leaves half-open connections stuck in `Connected`.

## Phase 2: Reject Business Packets Before Authentication

Update the server packet handler so that authentication is required before processing business commands.

Required behavior:

- Unauthenticated connections must not be allowed to execute `SEND_MESSAGE`.
- Unauthenticated connections must not be allowed to execute `DELIVERY_ACK`.
- Unauthenticated connections must not receive normal `HEARTBEAT_ACK`.

Important implementation rule:

- Remove the fallback that reads `senderId` or `receiverId` from packet bodies when the connection is not registered as authenticated.

Expected result:

- No message acceptance from unauthenticated sockets.
- No more contradictory logs such as accepted send plus offline sender ACK skip.
- Server-side protocol boundaries match session boundaries.

## Phase 3: Create One Client Entry Point For A Valid Access Token

The Android client must stop connecting WebSocket with whatever token object a ViewModel happens to hold.

Instead, introduce one unified access point that guarantees a valid access token at connect time.

Suggested responsibility:

- `AuthRepository.ensureValidSession(): AuthSession?`

Required behavior:

1. Read the current stored session.
2. If there is no session, return `null`.
3. If refresh token is expired or missing, clear session and return `null`.
4. If access token is still valid, return the current session.
5. If access token is expired but refresh token is still valid:
   - call refresh
   - persist the new session on success
   - clear session on failure
   - return the refreshed session or `null`

All WebSocket connect paths must use this entry point:

- first connect after app startup
- reconnect after disconnect
- reconnect after heartbeat timeout
- reconnect after foreground resume if needed

Expected result:

- The client never reconnects with a known stale token.
- Access token freshness is managed in one place instead of scattered across ViewModels and connection logic.

## Phase 4: Route WebSocket Connect Through The Valid-Session Entry Point

Refactor the Android connection flow so that WebSocket connect no longer takes a long-lived stale token from UI state.

Current anti-pattern:

- `connection.connect(session.token)`

Target flow:

1. A caller requests "connect authenticated WebSocket".
2. The connection layer asks `AuthRepository.ensureValidSession()`.
3. If a valid session is returned, the WebSocket opens with the returned access token.
4. If no valid session is returned, connection does not start and the UI is driven back to logged-out state.

This should apply to:

- initial connect from conversation screen
- initial connect from chat screen
- automatic reconnect logic inside `ConnectionLifecycleManager`

Expected result:

- Connect and reconnect behavior use the same token freshness rules.
- The connection layer no longer depends on stale session snapshots.

## Phase 5: Refresh Only On Recoverable Auth Failure

Once `AUTH_NACK` exists, the client must not treat every auth failure the same.

Required client behavior:

- `TOKEN_EXPIRED`:
  - try refresh once
  - reconnect with the refreshed access token if refresh succeeds
  - clear session and logout if refresh fails
- `TOKEN_INVALID`:
  - do not refresh
  - treat the local session as corrupted or incompatible
  - clear session and logout
- `TOKEN_MISSING`:
  - do not refresh
  - clear session and logout

Important rule:

- Do not allow an infinite loop like `AUTH_NACK -> refresh -> reconnect -> AUTH_NACK -> refresh`.

Expected result:

- Recoverable auth failures recover automatically.
- Non-recoverable auth failures fail fast and cleanly.

## Phase 6: Restore Heartbeat Only After Successful Auth

No special heartbeat workaround should be added.

Heartbeat behavior should stay consistent with the current state machine:

- heartbeat starts only after `Authenticated`
- if auth fails, heartbeat does not start
- once refresh and reconnect succeed, heartbeat resumes naturally

This is important because the heartbeat mechanism is not the root cause. Authentication recovery is the root cause fix.

Expected result:

- Heartbeat returns automatically when WebSocket auth is restored.
- No duplicate or parallel heartbeat recovery logic is introduced.

## Implementation Order

Apply the fix in this order:

1. Add `AUTH_NACK` protocol support.
2. Close the WebSocket on auth failure.
3. Block unauthenticated business packet handling on the server.
4. Add `AuthRepository.ensureValidSession()` or equivalent unified session-validation API.
5. Refactor all WebSocket connect and reconnect paths to use the unified API.
6. Handle `AUTH_NACK` reasons on Android.
7. Add tests for both server and Android state transitions.

This order minimizes the time spent in inconsistent intermediate states.

## Validation Checklist

### Server Validation

- Expired token `AUTH` returns `AUTH_NACK(TOKEN_EXPIRED)` and then closes the socket.
- Invalid token `AUTH` returns `AUTH_NACK(TOKEN_INVALID)` and then closes the socket.
- Missing token `AUTH` returns `AUTH_NACK(TOKEN_MISSING)` and then closes the socket.
- Unauthenticated `SEND_MESSAGE` does not assign `serverSeq` and does not persist a message.
- Unauthenticated `DELIVERY_ACK` does not update delivery state.
- Unauthenticated `HEARTBEAT` does not return normal `HEARTBEAT_ACK`.

### Android Validation

- If access token is expired before connect, the client refreshes first.
- If refresh succeeds, WebSocket connects with the new access token.
- If refresh fails, the local session is cleared and the UI returns to login.
- `AUTH_NACK(TOKEN_EXPIRED)` triggers one refresh attempt.
- `AUTH_NACK(TOKEN_INVALID)` does not trigger refresh.
- Heartbeat resumes after successful authenticated reconnect.

### End-To-End Validation

- Two emulators left running beyond the 15-minute access token TTL can still recover after reconnect conditions.
- The system no longer gets stuck in a state where auth is rejected and heartbeat never resumes.
- The server no longer logs accepted message flow from unauthenticated senders.

## Test Coverage To Add

### Mock Server

- `AUTH` expired token -> `AUTH_NACK(TOKEN_EXPIRED)` + close
- `AUTH` invalid token -> `AUTH_NACK(TOKEN_INVALID)` + close
- unauthenticated `SEND_MESSAGE` rejected
- unauthenticated `DELIVERY_ACK` rejected
- unauthenticated `HEARTBEAT` rejected

### Android

- connect path refreshes expired access token before WebSocket connect
- reconnect path refreshes expired access token before WebSocket reconnect
- `AUTH_NACK(TOKEN_EXPIRED)` refresh success path
- `AUTH_NACK(TOKEN_EXPIRED)` refresh failure path
- `AUTH_NACK(TOKEN_INVALID)` logout path
- no infinite reconnect-refresh loop

## Expected Outcome

After this fix:

- WebSocket auth failure becomes explicit and bounded.
- Reconnect uses a currently valid access token instead of stale in-memory state.
- Unauthenticated WebSocket connections cannot send business traffic.
- Heartbeat recovers as a consequence of restored authentication, not from special-case heartbeat logic.
