# SelfHostedIM

Android self-developed IM client for Project C in `docs/2026-engine.pdf`.

## Current Status

See `docs/DEVELOPMENT_STATUS.md` for the feature completion and verification log.

## Local Build

This repository is configured as a single-module Android project:

```powershell
C:\Users\10954\.gradle\wrapper\dists\gradle-9.0.0-bin\d6wjpkvcgsg3oed0qlfss3wgl\gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain
C:\Users\10954\.gradle\wrapper\dists\gradle-9.0.0-bin\d6wjpkvcgsg3oed0qlfss3wgl\gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain
```

## Local Mock Server

The `mock-server` directory contains a Java/Netty server for local testing.

```powershell
cd D:\Desktop\engine\IM\mock-server
mvn -q test
mvn -q compile exec:java
```

Use these addresses:

- Windows/Postman: `http://127.0.0.1:8080`, `ws://127.0.0.1:8080/ws`
- USB real device with adb reverse: `http://127.0.0.1:8080`, `ws://127.0.0.1:8080/ws`
- Android emulator: `http://10.0.2.2:8080`, `ws://10.0.2.2:8080/ws`

The Android app reads mock-server host and port from:

```text
app/src/main/assets/mock-server.properties
```

Default configuration targets USB real-device debugging:

```properties
host=127.0.0.1
port=8080
```

Before running the app on a USB-connected real device, forward the device port
to the computer mock server:

```powershell
adb reverse tcp:8080 tcp:8080
```

For Android emulator debugging, change only the host:

```properties
host=10.0.2.2
port=8080
```

Register users before logging in. Suggested local test accounts:

- `13800113800 / 123456`
- `13900113900 / 123456`
- `17724734511 / 123456`
- `13267100423 / 123456`

These four demo accounts are mutual contacts in the Android client, so each one
sees the other three on the Contacts tab.

Login/register returns a signed local mock access JWT with a 15-minute expiry
and a refresh token with a 7-day expiry. The Android client restores a saved
session directly when the access token is still valid, silently refreshes when
only the access token has expired, and returns to login when the refresh token is
expired or revoked.

