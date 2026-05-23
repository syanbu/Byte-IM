# IM Client Development Status

This file records completed and pending work while implementing `docs/superpowers/plans/2026-05-21-im-client-roadmap.md`.

## Verification Log

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 0 | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: `AppInfoTest > exposesInitialProjectMilestone PASSED`; build successful. |
| 2026-05-22 | Phase 0 | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled successfully. |
| 2026-05-22 | Phase 1 | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 9 unit tests covering AppInfo, AuthRepository, AuthJsonParser, and LoginViewModel. |
| 2026-05-22 | Phase 1 | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with Compose login screen. |
| 2026-05-22 | Phase 2 | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 14 unit tests including message deduplication, pagination, ACK update, conversation ordering, and unread clearing. |
| 2026-05-22 | Phase 2 | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with SQLiteOpenHelper and Android DAO implementations. |
| 2026-05-22 | Phase 3 | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 18 unit tests including protocol encode/decode, invalid magic, invalid length, and invalid CRC. |
| 2026-05-22 | Phase 3 | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with protocol codec. |
| 2026-05-22 | Phase 4 | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 19 unit tests including AUTH packet construction. |
| 2026-05-22 | Phase 4 | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with OkHttp WebSocket connection implementation. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 22 unit tests including send-text persistence, SEND_MESSAGE packet dispatch, MESSAGE_ACK status update, pending deletion, incoming persistence, and unread increment. |
| 2026-05-22 | Phase 5 | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with message repository core. |
| 2026-05-22 | Mock Server | `mvn -q test` in `mock-server` | Passed: protocol codec, auth response, message ACK/forward routing, and channel session removal tests. |
| 2026-05-22 | Mock Server | `mvn -q package` in `mock-server` | Passed: Java/Netty mock server packaged successfully. |
| 2026-05-22 | Mock Server | Java process smoke test + `Invoke-RestMethod` | Superseded by B1 phone-account auth: use registered phone accounts such as `13800113800 / 123456`. |
| 2026-05-22 | Android Chat UI | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 25 unit tests including ChatViewModel WebSocket connect, send refresh, and incoming packet refresh. |
| 2026-05-22 | Android Chat UI | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with minimal chat screen wired to local mock WebSocket URL. |
| 2026-05-22 | Login Crash Fix | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 26 unit tests including `OkHttpAuthApiTest.loginReturnsFailureWhenNetworkThrowsIOException`; network failure now returns `AuthResult.Failure` instead of escaping the coroutine. |
| 2026-05-22 | Login Crash Fix | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled after enabling cleartext HTTP for local mock server testing. |
| 2026-05-22 | Mock Server Diagnostics | `mvn -q test` and `mvn -q package` in `mock-server` | Passed: server now logs AUTH, SEND_MESSAGE, MESSAGE_ACK, RECEIVE_MESSAGE forwarding, offline receiver skips, heartbeats, and packet errors. |
| 2026-05-22 | Manual A/B Chat Smoke Test | Two Android emulators + local mock server | Passed by user verification: both `13800113800 -> 13900113900` and `13900113900 -> 13800113800` messages were ACKed and forwarded after both clients were online. |
| 2026-05-22 | B1 Phone Account Auth | `mvn -q test` in `mock-server` | Passed: register/login now require mainland China phone numbers, store per-user salt plus PBKDF2 password hash, reject duplicate users, reject wrong passwords, and do not store plaintext passwords. |
| 2026-05-22 | B1 Android Auth | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 27 unit tests including Android auth JSON request body sending `phone` and `password`. |
| 2026-05-22 | B1 Android Build | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled after phone-number auth updates. |
| 2026-05-22 | B1 Token Expiry Fix | `mvn -q test` in `mock-server` | Superseded by B1 dual-token auth: WebSocket AUTH rejects legacy, invalid, or expired access tokens. |
| 2026-05-22 | B1 Android Session Restore Fix | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 30 unit tests including stale non-phone sessions and expired sessions being cleared before auto-entering chat. |
| 2026-05-22 | B1 Android Session Restore Fix | `gradle-9.0.0\\bin\\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled after token expiry and stale-session cleanup changes. |
| 2026-05-22 | B1 Register UX Fix | Database query against the pre-migration mock-server account database | Confirmed the old local DB contained `13800138000` and `13900139000`; this database was later removed during the SQLite migration. |
| 2026-05-22 | B1 Register UX Fix | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --tests com.codex.im.auth.RegistrationInputValidatorTest --console=plain` | Passed: registration password confirmation accepts matching passwords and rejects mismatches. |
| 2026-05-22 | Mock Server SQLite Migration | `mvn -q test -Dtest=UserStoreTest` in `mock-server` | Passed: mock-server account storage now creates the requested `.sqlite` file and does not create H2 `.mv.db` / `.trace.db` sidecars. |
| 2026-05-22 | Mock Server SQLite Migration | `mvn -q test` in `mock-server` | Passed: all mock-server tests pass after replacing H2 with SQLite JDBC. |
| 2026-05-22 | B1 Login/Register UX | `uiautomator dump` on `emulator-5554` after installing debug APK | Passed: app opens on the login screen with `Phone number`, `Password`, `Login`, and `Create account`; tapping `Create account` switches to the register screen with `Confirm password`, `Register`, and `Back to login`. |
| 2026-05-22 | Single Chat Default Peer Fix | `gradle-9.0.0\\bin\\gradle.bat :app:testDebugUnitTest --tests com.codex.im.DefaultPeerResolverTest --console=plain` | Passed: default peer is `13900113900` for `13800113800`, and `13800113800` for accounts starting with `139`. |
| 2026-05-22 | B1 Dual Token Auth | `mvn -q test` in `mock-server` | Passed: login/register issue access and refresh tokens, refresh returns a new access token while refresh remains valid, logout revokes refresh tokens, and WebSocket AUTH still rejects invalid or expired access tokens. |
| 2026-05-22 | B1 Dual Token Android | `.\\gradlew.bat :app:testDebugUnitTest --console=plain` | Passed: 39 unit tests including auth JSON parsing, refresh/logout HTTP bodies, silent refresh when access expires, refresh failure cleanup, and logout clearing stored session. |
| 2026-05-22 | B1 Dual Token Builds | `mvn -q package` in `mock-server`; `.\\gradlew.bat :app:assembleDebug --console=plain` | Passed: mock-server packaged and debug APK assembled after dual-token auth and logout changes. |
| 2026-05-22 | WebSocket Auth State Display | `.\\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.connection.ConnectionStateReducerTest --tests com.codex.im.chat.ChatViewModelTest --console=plain`; `mvn -q test -Dtest=MessageRouterTest` in `mock-server` | Passed: Android maps `AUTH_ACK` to `ConnectionState.Authenticated`, chat UI exposes connection status text, and mock-server records a single authenticated status event after sending AUTH_ACK. |
| 2026-05-22 | WebSocket Protocol Docs | Documentation update | Added `docs/WEBSOCKET_PROTOCOL_AND_STATES.md` explaining protocol message types, client `ConnectionState`, local message status, runtime flow, and mock-server logs. |

## Completed

- Phase 0: Android Kotlin project skeleton.
  - Single `:app` module created.
  - Kotlin, Compose, Coroutines, OkHttp, and JUnit dependencies configured.
  - `MainActivity` launches a minimal Compose screen.
  - Local Android SDK path configured in ignored `local.properties`.
- Phase 1 / B1: Login/register and JWT token storage, local mock implementation.
  - `AuthApi`, `AuthRepository`, `AuthSession`, and `AuthResult` added.
  - `InMemoryTokenStore` and `SharedPreferencesTokenStore` added.
  - `OkHttpAuthApi` added with configurable base URL and JSON request body.
  - `AuthJsonParser` supports flat and nested success responses plus failure responses.
  - Compose `LoginScreen` and `LoginViewModel` added.
  - Login/register now use mainland China phone numbers as the account identifier.
  - Android auth requests send `phone` plus `password`; the UI label and validation copy use phone-number accounts.
  - App opens on the login screen by default; users can switch to the register screen with `Create account`.
  - Register UI includes a second password field and checks both password entries match before calling the server.
  - Login now requires a registered account and the correct password.
  - Auth success responses include a signed local mock access JWT plus `accessExpiresAt`, a random refresh token plus `refreshExpiresAt`, and legacy-compatible `token` / `expiresAt` aliases.
  - Access tokens are valid for 15 minutes; refresh tokens are valid for 7 days.
  - Android stores `access_token`, `access_expires_at`, `refresh_token`, and `refresh_expires_at` in `SharedPreferences` through `SharedPreferencesTokenStore`; production hardening can replace this with `EncryptedSharedPreferences` / Android Keystore without changing callers.
  - Android restores saved login state when the user id is a mainland China phone number and the access token has not expired.
  - If the access token has expired but the refresh token is still valid, Android calls `/refresh` silently, stores the new access token, and enters chat.
  - If the refresh token is expired, revoked, missing, or rejected, Android clears the saved session and returns to login.
  - Legacy saved sessions such as old username-based tokens are cleared instead of auto-entering chat.
  - Mock server stores only a random salt and PBKDF2-SHA256 password hash; plaintext passwords are not persisted.
  - Mock server stores refresh token SHA-256 hashes, not refresh token plaintext, in SQLite and marks tokens revoked on logout.
  - Mock server persists local test users in `mock-server/data/mock-im-users.sqlite`.
- Phase 2: SQLite message, conversation, and pending-message storage.
  - `ImDatabaseHelper` creates `messages`, `conversations`, and `pending_messages` with indexes.
  - DAO contracts added for messages, conversations, and pending messages.
  - In-memory DAO implementations added for JVM contract testing.
  - Android SQLite DAO implementations added for app runtime.
- Phase 3: Custom binary protocol codec.
  - `ImPacket`, `ImCommand`, `ImPacketCodec`, `Crc32`, and `ProtocolException` added.
  - Frame format implemented as `magic(2) + version(1) + length(4) + cmd(4) + body + crc(4)`.
  - CRC validates all bytes before the trailing CRC field.
  - WebSocket protocol messages, client connection states, and local message statuses are documented in `docs/WEBSOCKET_PROTOCOL_AND_STATES.md`.
- Phase 4: WebSocket long connection, local implementation.
  - `ImConnection` interface added with state and incoming packet flows.
  - `ConnectionState` added.
  - `ConnectionState.Authenticated` is set after the client receives protocol `AUTH_ACK`, so the app distinguishes WebSocket-open from IM-authenticated.
  - `AuthPacketFactory` added for protocol-level AUTH packets.
  - `OkHttpImConnection` added; it opens WebSocket, sends AUTH on open, decodes binary packets, and exposes failures.
- Phase 5: Single-chat send and receive, business core.
  - `MessageIdGenerator` and `SeqGenerator` added.
  - `MessageRepository.sendText` stores outgoing messages as `SENDING`, updates conversation preview, writes pending retry records, and sends `SEND_MESSAGE` packets.
  - `MessageRepository.handlePacket` handles `MESSAGE_ACK` and `RECEIVE_MESSAGE`.
  - ACK handling marks messages as `SENT`, stores `serverSeq`, and removes pending records.
  - Incoming message handling persists received messages and increments unread count.
- Local Java/Netty mock server.
  - Added `mock-server` Maven project.
  - Supports `POST /login`, `POST /register`, `POST /refresh`, `POST /logout`, `GET /health`, and WebSocket `/ws`.
  - HTTP auth returns nested JSON compatible with the Android client's `AuthJsonParser`.
  - HTTP auth accepts mainland China phone numbers and passwords, not arbitrary usernames.
  - `POST /register` creates a local user record with salt plus PBKDF2 password hash.
  - `POST /login` succeeds only for existing users with a matching password.
  - HTTP auth issues signed local mock access JWTs with a 15-minute expiry and random refresh tokens with a 7-day expiry.
  - Refresh tokens are persisted as SHA-256 hashes in the `refresh_tokens` SQLite table with `expires_at`, `issued_at`, and nullable `revoked_at`.
  - `POST /refresh` accepts a valid refresh token and returns a fresh access JWT; `POST /logout` revokes the refresh token.
  - WebSocket `AUTH` validates the access token signature and expiry before registering the client as online.
  - WebSocket protocol uses the same binary frame format as the Android client: `magic + version + length + cmd + body + crc`.
  - Handles `AUTH`, `HEARTBEAT`, and `SEND_MESSAGE`.
  - Sends `AUTH_ACK`, `HEARTBEAT_ACK`, `MESSAGE_ACK`, and online receiver `RECEIVE_MESSAGE`.
- Minimal Android chat UI for local mock testing.
  - Added `ChatViewModel` to connect WebSocket with the login token and collect incoming packets.
  - Added `ChatScreen` with peer userId input, message list, and send box.
  - Chat screen includes a `Logout` button that clears local access/refresh tokens and asks the mock server to revoke the refresh token.
  - Chat screen displays the current connection status, including `Authenticated` after WebSocket AUTH succeeds.
  - Login success now switches from `LoginScreen` to `ChatScreen`.
  - Android client uses `http://10.0.2.2:8080` and `ws://10.0.2.2:8080/ws` for emulator-to-host mock server testing.
  - Default peer is temporarily hard-coded from the logged-in account: `138...` users chat with `13900113900`; `139...` users chat with `13800113800`.
  - Single-chat conversation IDs are canonicalized by sorting the two user IDs, so `13800113800 <-> 13900113900` uses the same local conversation on both sides.
- Login crash fix.
  - `OkHttpAuthApi` now catches `IOException` and returns `AuthResult.Failure`.
  - Android manifest enables `usesCleartextTraffic` for local `http://` mock server testing.
- Mock server diagnostics.
  - Server prints connection-path events such as `AUTH userId=13800113800`, `SEND_MESSAGE sender=13800113800 receiver=13900113900`, `MESSAGE_ACK sent`, and `RECEIVE_MESSAGE forwarded`.
  - Server prints status events such as `STATUS AUTHENTICATED userId=... authAck=sent` and `STATUS DISCONNECTED userId=...`.
  - If the receiver is not connected, server prints `RECEIVE_MESSAGE skipped receiver offline`.

## In Progress

- None.

## Not Started

- Phase 6: History pagination.
- Phase 7: Heartbeat and reconnect.
- Phase 8: Reliability, retry, deduplication, and ordering.
- Phase 9: Conversation list and unread behavior.
- Phase 10: Performance, packet capture, and stability evidence.

## Blocked External Verification

- Phase 1 / B1 real login/register is implemented against the local mock server. Test accounts must be registered first, for example `13800113800 / 123456` and `13900113900 / 123456`.
- After the B1 auth hardening, the running mock server must be restarted before testing. Only registered phone-number accounts are valid for HTTP login.
- If `POST /register` returns "User already registered", the persisted SQLite database already has that phone number; login can continue with the existing password, or use a new test phone number.
- The mock-server account database has been migrated from H2 to SQLite. The old H2 files `mock-im-users.mv.db` and `mock-im-users.trace.db` were removed from `mock-server/data/`.
- Because the old H2 database files were deleted, the new SQLite database starts empty; test phone accounts need to be registered again after starting the updated mock server.
- Phase 2 SQLiteOpenHelper runtime behavior is not device-verified yet because `adb devices` currently shows no connected emulator or physical device. DAO behavior is covered by JVM contract tests, and Android DAO code is compile-verified by `assembleDebug`.
- Phase 4 real WebSocket connection is implemented in Android and compile/unit-test verified; the two-emulator A/B smoke test has been manually verified by the user against the local mock server.
- Phase 5 two-client single-chat UI is implemented and compile/unit-test verified; the basic online delivery path has been manually verified by the user. Offline message storage, reconnect resend, and richer conversation behavior are still pending later phases.
- Physical Android devices cannot use `10.0.2.2` to reach the Windows host. Use an Android emulator, or change the app base URLs to the Windows machine's LAN IP and allow the firewall port.
