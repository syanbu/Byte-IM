# B1 Auth Status

## Requirement

Login/register through HTTP APIs with JWT token support.

## Status

Done.

## Completed

- Added `AuthApi`, `AuthRepository`, `AuthSession`, and `AuthResult`.
- Added `InMemoryTokenStore` and `SharedPreferencesTokenStore`.
- Added `OkHttpAuthApi` with configurable base URL and JSON request body.
- Added `AuthJsonParser` for flat, nested success, and failure responses.
- Added Compose `LoginScreen` and `LoginViewModel`.
- Login/register use mainland China phone numbers as account identifiers.
- Register UI includes password confirmation and local mismatch validation.
- Login requires a registered account and correct password.
- Auth success responses include access token, access expiry, refresh token, refresh expiry, and legacy-compatible aliases.
- Access tokens are valid for 15 minutes; refresh tokens are valid for 7 days.
- Android stores access/refresh token state in `SharedPreferences`.
- Android restores saved login state when the user id is a phone account and the access token is valid.
- Android silently refreshes when the access token is expired but the refresh token is valid, and persists the newly returned access/refresh token pair.
- Android clears stale, expired, revoked, or legacy sessions.
- Logout clears local tokens and revokes the refresh token on the mock server.

## Mock Server Support

- HTTP auth accepts phone numbers and passwords.
- `POST /register` stores random salt plus PBKDF2-SHA256 password hash.
- Plaintext passwords are not persisted.
- Access token signing currently uses symmetric `HS256` (`HmacSHA256`) with a shared mock-server secret, not asymmetric `RS256` signing/verification.
- Refresh token hashes are persisted in SQLite and marked revoked on logout or refresh-token rotation.
- `POST /refresh` now rotates both the access token and refresh token, and invalidates the previous refresh token in the same SQLite transaction.
- WebSocket `AUTH` validates access token signature and expiry.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 1 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 9 unit tests covering AppInfo, AuthRepository, AuthJsonParser, and LoginViewModel. |
| 2026-05-22 | Phase 1 | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with Compose login screen. |
| 2026-05-22 | B1 Phone Account Auth | `mvn -q test` in `mock-server` | Passed: register/login require mainland China phone numbers, store per-user salt plus PBKDF2 password hash, reject duplicate users, reject wrong passwords, and do not store plaintext passwords. |
| 2026-05-22 | B1 Android Auth | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 27 unit tests including Android auth JSON request body sending `phone` and `password`. |
| 2026-05-22 | B1 Android Build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled after phone-number auth updates. |
| 2026-05-22 | B1 Session Restore Fix | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: stale non-phone sessions and expired sessions are cleared before auto-entering chat. |
| 2026-05-22 | B1 Register UX Fix | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.auth.RegistrationInputValidatorTest --console=plain` | Passed: registration password confirmation accepts matching passwords and rejects mismatches. |
| 2026-05-22 | B1 Login/Register UX | `uiautomator dump` on `emulator-5554` after installing debug APK | Passed: login screen shows phone number, password, login, and create-account controls; register screen shows confirm password, register, and back-to-login controls. |
| 2026-05-22 | B1 Dual Token Auth | `mvn -q test` in `mock-server` | Passed: login/register issue access and refresh tokens, refresh is supported, logout revokes refresh tokens, and WebSocket AUTH rejects invalid or expired access tokens. |
| 2026-05-22 | B1 Dual Token Android | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | Passed: 39 unit tests including auth JSON parsing, refresh/logout HTTP bodies, silent refresh, refresh failure cleanup, and logout clearing stored session. |
| 2026-05-22 | B1 Dual Token Builds | `mvn -q package` in `mock-server`; `.\gradlew.bat :app:assembleDebug --console=plain` | Passed: mock-server packaged and debug APK assembled after dual-token auth and logout changes. |
| 2026-05-28 | B1 Refresh Token Rotation Fix | `mvn -q -Dtest=AuthServiceTest test` in `mock-server`; `.\gradlew.bat :app:testDebugUnitTest --console=plain` | Passed: refresh now issues a new access/refresh token pair, invalidates the previous refresh token, and Android persists the rotated refresh token. |

## External Notes

- The local mock-server account database is `mock-server/data/mock-im-users.sqlite`.
- After the SQLite migration, test phone accounts need to be registered again if the database starts empty.

