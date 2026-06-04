# B6 Binary Protocol Status

## Requirement

Custom binary protocol: `Header(magic + version + length + cmd) + Body(JSON or Protobuf) + CRC`.

## Status

Done.

## Completed

- Added `ImPacket`.
- Added `ImCommand`.
- Added `ImPacketCodec`.
- Added `Crc32`.
- Added `ProtocolException`.
- Frame format is `magic(2) + version(1) + length(4) + cmd(4) + body + crc(4)`.
- CRC validates all bytes before the trailing CRC field.
- Android and mock server use the same binary frame format.
- WebSocket protocol messages, client connection states, and local message statuses are documented in `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`.

## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-05-22 | Phase 3 | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed: 18 unit tests including protocol encode/decode, invalid magic, invalid length, and invalid CRC. |
| 2026-05-22 | Phase 3 | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed: debug APK assembled with protocol codec. |
| 2026-05-22 | Mock Server | `mvn -q test` in `mock-server` | Passed: protocol codec tests on the Java/Netty mock server. |
| 2026-05-22 | WebSocket Protocol Docs | Documentation update | Added `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`. |

