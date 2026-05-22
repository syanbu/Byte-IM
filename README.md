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
mvn -q exec:java
```

Use these addresses:

- Windows/Postman: `http://127.0.0.1:8080`, `ws://127.0.0.1:8080/ws`
- Android emulator: `http://10.0.2.2:8080`, `ws://10.0.2.2:8080/ws`

