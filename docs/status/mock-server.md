# Mock Server Status

## Status

Done for the current B1/B2 local verification path.

## Completed

- Added `mock-server` Maven project.
- Supports `POST /login`.
- Supports `POST /register`.
- Supports `POST /refresh`.
- Supports `POST /logout`.
- Supports `GET /health`.
- Supports WebSocket `/ws`.
- HTTP auth returns nested JSON compatible with Android `AuthJsonParser`.
- HTTP auth accepts mainland China phone numbers and passwords.
- HTTP auth issues signed local mock access JWTs and random refresh tokens.
- Refresh tokens are stored as SHA-256 hashes in SQLite.
- WebSocket `AUTH` validates access token signature and expiry.
- WebSocket protocol matches Android frame format.
- Handles `AUTH`, `HEARTBEAT`, and `SEND_MESSAGE`.
- Sends `AUTH_ACK`, `HEARTBEAT_ACK`, `MESSAGE_ACK`, and online receiver `RECEIVE_MESSAGE`.
- Logs connection-path events and packet errors for local diagnostics.
- Removes channel session state when clients disconnect.

## Verification

| Date | Command | Result |
|---|---|---|
| 2026-05-22 | `mvn -q test` in `mock-server` | Passed: protocol codec, auth response, message ACK/forward routing, and channel session removal tests. |
| 2026-05-22 | `mvn -q package` in `mock-server` | Passed: Java/Netty mock server packaged successfully. |
| 2026-05-22 | Java process smoke test + `Invoke-RestMethod` | Superseded by B1 phone-account auth; use registered phone accounts such as `13800113800 / 123456`. |
| 2026-05-22 | `mvn -q test` and `mvn -q package` in `mock-server` | Passed: server logs AUTH, SEND_MESSAGE, MESSAGE_ACK, RECEIVE_MESSAGE forwarding, offline receiver skips, heartbeats, and packet errors. |
| 2026-05-22 | `mvn -q test -Dtest=UserStoreTest` in `mock-server` | Passed: account storage now creates the requested `.sqlite` file and does not create H2 sidecar files. |
| 2026-05-22 | `mvn -q test` in `mock-server` | Passed: all mock-server tests pass after replacing H2 with SQLite JDBC. |

## External Notes

- Restart the running mock server after auth or protocol changes before manual testing.
- Physical devices need the Windows host LAN IP instead of emulator-only `10.0.2.2`.
