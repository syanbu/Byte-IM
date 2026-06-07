# Mock Server Status

## Status

Done for the current B1-B9.5 local verification path, including B5.5 durable accepted-message persistence.

## Completed

- Added `mock-server` Maven project.
- Supports `POST /login`.
- Supports `POST /register`.
- Supports `POST /refresh`.
- Supports `POST /logout`.
- Supports `GET /friends/me`.
- Supports `GET /health`.
- Supports WebSocket `/ws`.
- HTTP auth returns nested JSON compatible with Android `AuthJsonParser`.
- HTTP auth accepts mainland China phone numbers and passwords.
- HTTP auth issues signed local mock access JWTs and random refresh tokens.
- Refresh tokens are stored as SHA-256 hashes in SQLite.
- WebSocket `AUTH` validates access token signature and expiry.
- WebSocket protocol matches Android frame format.
- Handles `AUTH`, `HEARTBEAT`, `SEND_MESSAGE`, and `DELIVERY_ACK`.
- Sends `AUTH_ACK`, `HEARTBEAT_ACK`, `MESSAGE_ACK`, online receiver `RECEIVE_MESSAGE`, and queued offline `RECEIVE_MESSAGE` after the receiver authenticates.
- Persists users in SQLite.
- Persists mutual friend indexes in SQLite.
- Persists conversation `serverSeq` state in SQLite.
- Persists accepted messages and receiver delivery state in SQLite.
- Restores accepted messages and undelivered receiver replay state after server restart.
- Logs connection-path events and packet errors for local diagnostics.
- Removes channel session state when clients disconnect.
- Mock contact lists now come from server-side friendships instead of Android fixed demo accounts.
- The friendship database is `mock-server/data/mock-im-friends.sqlite`.
- The friendship schema is `friendships(owner_user_id, friend_user_id, created_at)` with a primary key on `(owner_user_id, friend_user_id)`.
- Mutual friendships are stored as two directed rows, so if A is B's friend then B is A's friend.
- Seed tool: `mvn -q -Dexec.mainClass=com.buyansong.imserver.tools.MockFriendSeeder compile exec:java` registers `15000000000` through `15000000499` with password `123456` and gives the first three accounts every other account as mutual friends.
- Seed scripts:
  - Windows: `mock-server/seed-mock-friends.ps1`
  - Ubuntu/Linux: `mock-server/seed-mock-friends.sh`

| Date | Command | Result |
|---|---|---|
| 2026-06-05 | `mvn -q test` in `mock-server`; `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.contacts.ContactJsonParserTest --tests com.buyansong.im.contacts.ContactListViewModelTest --tests com.buyansong.im.group.GroupCreateViewModelTest` | Passed: friend SQLite store, friend JSON service, 500-account seed behavior, and Android Contacts/GroupCreate loading contacts from server friend IDs before profile batch refresh. |
| 2026-05-27 | `mvn -q -Dtest=MessageRouterTest test`; `mvn -q test` in `mock-server` | Passed: accepted-message SQLite persistence, router restart restore, receiver auth replay after restart, no replay after `DELIVERY_ACK`, and restart-proof sender `messageId` idempotency. |
| 2026-05-25 | `mvn -q -Dtest=MessageRouterTest test`; `mvn -q test` in `mock-server` | Passed: messages sent to an offline receiver are queued in memory and delivered as `RECEIVE_MESSAGE` immediately after that receiver authenticates. |
| 2026-05-22 | `mvn -q test` in `mock-server` | Passed: protocol codec, auth response, message ACK/forward routing, and channel session removal tests. |
| 2026-05-22 | `mvn -q package` in `mock-server` | Passed: Java/Netty mock server packaged successfully. |
| 2026-05-22 | Java process smoke test + `Invoke-RestMethod` | Superseded by B1 phone-account auth; use registered phone accounts such as `13800113800 / 123456`. |
| 2026-05-22 | `mvn -q test` and `mvn -q package` in `mock-server` | Passed: server logs AUTH, SEND_MESSAGE, MESSAGE_ACK, RECEIVE_MESSAGE forwarding, offline receiver skips, heartbeats, and packet errors. |
| 2026-05-22 | `mvn -q test -Dtest=UserStoreTest` in `mock-server` | Passed: account storage now creates the requested `.sqlite` file and does not create H2 sidecar files. |
| 2026-05-22 | `mvn -q test` in `mock-server` | Passed: all mock-server tests pass after replacing H2 with SQLite JDBC. |

## External Notes

- Restart the running mock server after auth or protocol changes before manual testing.
- Physical devices need the Windows host LAN IP instead of emulator-only `10.0.2.2`.
