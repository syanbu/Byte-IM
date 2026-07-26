# WebSocket Protobuf Envelope Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current `Magic + Version + Length + Command + JSON Body + CRC32` WebSocket wire format with one versioned Protobuf `ImEnvelope` whose `oneof payload` identifies every authentication, heartbeat, chat, ACK, read-receipt, and recall message.

**Architecture:** OkHttp and Netty continue to exchange one binary WebSocket message per application envelope. Protobuf owns serialization, payload typing, and protocol-version validation; WebSocket owns message boundaries, while WSS/TLS remains responsible for transport integrity in production. Connection lifecycle, heartbeat timing, reconnect backoff, JWT refresh, message idempotency, and retry policies keep their current behavior; only their packet construction/inspection points are adapted to generated Protobuf types.

**Tech Stack:** Android/Kotlin 2.0.21, OkHttp WebSocket 4.12.0, Java 21, Netty 4.1.111.Final, Protocol Buffers 4.34.1, Protobuf Gradle Plugin 0.10.0, protobuf-maven-plugin 0.6.1, JUnit 4.

## Global Constraints

- Do not change the six `ConnectionState` variants or their state-transition semantics.
- Do not change foreground/background heartbeat intervals, the two-missed-ACK threshold, or the 1–30 second reconnect policy.
- Do not change `AuthRepository.ensureValidSession()`, refresh-token rotation, Mutex serialization, or session-clear policy.
- Do not change HTTP JSON APIs; this migration applies only to WebSocket binary messages.
- Do not change Android or mock-server SQLite schemas in this migration. Existing JSON persistence is treated as an internal storage format behind explicit mappers, not as the WebSocket wire format.
- Use protocol version `2`; reject envelopes with another version or no `oneof payload`.
- Enforce a decoded/encoded envelope limit of 64 KiB. Image bytes remain in OSS; WebSocket messages carry URLs and metadata only.
- Do not use Protobuf `Any`, a redundant numeric `cmd`, Magic, a body-length field, or CRC32 in the new wire format.
- Client and mock server are cut over in the same release. If independently deployed old clients must remain supported, add a separate dual-protocol rollout plan before Task 6.
- Never reuse removed Protobuf field numbers; reserve deleted field numbers and enum numeric values in later schema revisions.

---

## Impact Summary

This is not a two-file codec-only change. Business behavior remains stable, but protocol types currently leak into several layers.

| Area | Impact | Behavior change? |
|---|---|---|
| Android/Java build | Add shared `.proto` code generation and runtimes | No |
| OkHttp/Netty WebSocket boundary | Replace custom codec with `ImEnvelope.parseFrom/toByteArray` | Wire format changes |
| `ImConnection` and server `OutboundClient` | Replace `ImPacket` parameter/flow types with `ImEnvelope` | No |
| Auth and heartbeat factories/reducers | Replace `cmd` checks and JSON bodies with typed payloads | No |
| `MessageRepository`/`MessageRouter` | Replace hand-written JSON parsing/building at WebSocket boundaries | No |
| Pending-message retry | Rebuild Protobuf from the existing durable JSON snapshot | No |
| Mock-server accepted-message persistence | Convert typed payloads to/from existing JSON columns in the store adapter | No schema change |
| Tests and fakes | Replace `ImPacket` fixtures with generated envelope fixtures | No |
| HTTP endpoints, UI, DAOs, auth refresh, reconnect timing | No protocol dependency | No change |

## File Structure

### Shared schema and build

| File | Change | Responsibility |
|---|---|---|
| `protocol-schema/src/main/proto/im/v2/im_protocol.proto` | Create | Single source of truth for all WebSocket envelopes and payloads. |
| `build.gradle` | Modify | Register Protobuf Gradle Plugin 0.10.0. |
| `app/build.gradle` | Modify | Generate Android Java-lite messages from the shared schema. |
| `mock-server/pom.xml` | Modify | Generate full Java messages from the same shared schema. |

### Android

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/buyansong/im/protocol/ImEnvelopeCodec.kt` | Create | Size, version, payload-presence validation around Protobuf parsing/serialization. |
| `app/src/main/java/com/buyansong/im/protocol/ImPacket.kt` | Delete in Task 6 | Legacy packet container. |
| `app/src/main/java/com/buyansong/im/protocol/ImPacketCodec.kt` | Delete in Task 6 | Legacy Magic/length/CRC codec. |
| `app/src/main/java/com/buyansong/im/protocol/ImCommand.kt` | Delete in Task 6 | Legacy duplicated numeric dispatch. |
| `app/src/main/java/com/buyansong/im/protocol/Crc32.kt` | Delete in Task 6 | Redundant WebSocket CRC. |
| `app/src/main/java/com/buyansong/im/connection/ImConnection.kt` | Modify | Expose/send `ImEnvelope`. |
| `app/src/main/java/com/buyansong/im/connection/OkHttpImConnection.kt` | Modify | Send and parse Protobuf binary WebSocket messages. |
| `app/src/main/java/com/buyansong/im/connection/AuthPacketFactory.kt` | Modify | Produce `AUTH` envelope. |
| `app/src/main/java/com/buyansong/im/connection/HeartbeatPacketFactory.kt` | Modify | Produce `HEARTBEAT` envelope. |
| `app/src/main/java/com/buyansong/im/connection/ConnectionStateReducer.kt` | Modify | Reduce `AUTH_ACK`/`AUTH_NACK` from `payloadCase`. |
| `app/src/main/java/com/buyansong/im/connection/ConnectionLifecycleManager.kt` | Modify | Detect `HEARTBEAT_ACK` by `payloadCase`; timing logic stays unchanged. |
| `app/src/main/java/com/buyansong/im/message/MessageProtoMapper.kt` | Create | Map `ChatMessage`, durable pending JSON, and ACK/receipt fields to/from generated payloads. |
| `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` | Modify | Dispatch typed payloads and use mapper-built envelopes. |

### Mock server

| File | Change | Responsibility |
|---|---|---|
| `mock-server/src/main/java/com/buyansong/imserver/protocol/ImEnvelopeCodec.java` | Create | Mirror size/version/payload validation. |
| `mock-server/src/main/java/com/buyansong/imserver/protocol/ImPacket.java` | Delete in Task 6 | Legacy packet record. |
| `mock-server/src/main/java/com/buyansong/imserver/protocol/ImPacketCodec.java` | Delete in Task 6 | Legacy custom codec. |
| `mock-server/src/main/java/com/buyansong/imserver/protocol/ImCommand.java` | Delete in Task 6 | Legacy command enum. |
| `mock-server/src/main/java/com/buyansong/imserver/netty/WebSocketFrameHandler.java` | Modify | Parse envelope, enforce auth gate, dispatch by `PayloadCase`. |
| `mock-server/src/main/java/com/buyansong/imserver/netty/ChannelOutboundClient.java` | Modify | Serialize `ImEnvelope` into `BinaryWebSocketFrame`. |
| `mock-server/src/main/java/com/buyansong/imserver/session/OutboundClient.java` | Modify | Send typed envelopes. |
| `mock-server/src/main/java/com/buyansong/imserver/session/MessageProtoMapper.java` | Create | Build typed envelopes and isolate existing JSON persistence conversion. |
| `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` | Modify | Accept typed payloads and build typed ACK/receive/receipt/recall messages. |
| `mock-server/src/main/java/com/buyansong/imserver/MockImServer.java` | Modify | Aggregate fragmented WebSocket messages up to 64 KiB. |

---

### Task 1: Add the Shared Protobuf Schema and Code Generation

**Files:**
- Create: `protocol-schema/src/main/proto/im/v2/im_protocol.proto`
- Modify: `build.gradle`
- Modify: `app/build.gradle`
- Modify: `mock-server/pom.xml`
- Test: generated-source compilation in both builds

**Interfaces:**
- Produces: `com.buyansong.im.protocol.v2.ImEnvelope` and one generated class per payload.
- Produces: `ImEnvelope.PayloadCase getPayloadCase()` for type dispatch.
- Consumes: no application code yet; this task only adds generated types beside the legacy protocol.

- [ ] **Step 1: Add a schema-generation smoke test that initially cannot compile**

Create `app/src/test/java/com/buyansong/im/protocol/GeneratedProtocolSmokeTest.kt`:

```kotlin
package com.buyansong.im.protocol

import com.buyansong.im.protocol.v2.Heartbeat
import com.buyansong.im.protocol.v2.ImEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedProtocolSmokeTest {
    @Test
    fun generatedEnvelopeExposesOneofPayloadCase() {
        val envelope = ImEnvelope.newBuilder()
            .setProtocolVersion(2)
            .setHeartbeat(Heartbeat.newBuilder().setClientTime(123L))
            .build()

        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT, envelope.payloadCase)
    }
}
```

- [ ] **Step 2: Run the smoke test and verify failure**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.protocol.GeneratedProtocolSmokeTest --console=plain
```

Expected: FAIL because `com.buyansong.im.protocol.v2` generated classes do not exist.

- [ ] **Step 3: Create the shared schema**

Create `protocol-schema/src/main/proto/im/v2/im_protocol.proto`:

```proto
syntax = "proto3";

package buyansong.im.v2;

option java_package = "com.buyansong.im.protocol.v2";
option java_outer_classname = "ImProtocolProto";
option java_multiple_files = true;

message ImEnvelope {
  uint32 protocol_version = 1;

  oneof payload {
    Auth auth = 10;
    AuthAck auth_ack = 11;
    AuthNack auth_nack = 12;
    Heartbeat heartbeat = 20;
    HeartbeatAck heartbeat_ack = 21;
    SendMessage send_message = 30;
    MessageAck message_ack = 31;
    ReceiveMessage receive_message = 32;
    DeliveryAck delivery_ack = 33;
    ReadAck read_ack = 34;
    RecallMessage recall_message = 40;
    RecallAck recall_ack = 41;
    RecallNotify recall_notify = 42;
    RecallNotifyAck recall_notify_ack = 43;
  }
}

message Auth { string access_token = 1; }
message AuthAck { string user_id = 1; uint64 server_time = 2; }
message AuthNack { AuthFailureReason reason = 1; }

enum AuthFailureReason {
  AUTH_FAILURE_REASON_UNSPECIFIED = 0;
  AUTH_FAILURE_REASON_TOKEN_EXPIRED = 1;
  AUTH_FAILURE_REASON_TOKEN_INVALID = 2;
  AUTH_FAILURE_REASON_TOKEN_MISSING = 3;
}

message Heartbeat { uint64 client_time = 1; }
message HeartbeatAck { uint64 server_time = 1; }

enum ConversationType {
  CONVERSATION_TYPE_UNSPECIFIED = 0;
  CONVERSATION_TYPE_SINGLE = 1;
  CONVERSATION_TYPE_GROUP = 2;
}

enum MessageType {
  MESSAGE_TYPE_UNSPECIFIED = 0;
  MESSAGE_TYPE_TEXT = 1;
  MESSAGE_TYPE_IMAGE = 2;
}

message ImagePayload {
  string image_url = 1;
  string thumbnail_url = 2;
  uint32 width = 3;
  uint32 height = 4;
  string mime_type = 5;
  uint64 size_bytes = 6;
}

message ChatMessagePayload {
  string message_id = 1;
  string conversation_id = 2;
  ConversationType conversation_type = 3;
  string group_id = 4;
  string group_name = 5;
  string sender_id = 6;
  string receiver_id = 7;
  uint64 client_seq = 8;
  optional uint64 server_seq = 9;
  MessageType message_type = 10;
  string content = 11;
  ImagePayload image = 12;
  repeated string mentioned_user_ids = 13;
  uint64 client_time = 14;
  optional uint64 server_time = 15;
  optional uint64 sender_profile_version = 16;
}

message SendMessage { ChatMessagePayload message = 1; }
message ReceiveMessage { ChatMessagePayload message = 1; }

message MessageAck {
  string message_id = 1;
  string conversation_id = 2;
  uint64 client_seq = 3;
  uint64 server_seq = 4;
  uint64 server_time = 5;
}

message DeliveryAck {
  string message_id = 1;
  string conversation_id = 2;
  uint64 server_seq = 3;
  string receiver_id = 4;
}

message ReadAck {
  string conversation_id = 1;
  ConversationType conversation_type = 2;
  string reader_id = 3;
  string peer_id = 4;
  uint64 read_up_to_server_seq = 5;
  uint64 read_at = 6;
}

message RecallMessage {
  string message_id = 1;
  string conversation_id = 2;
  string requester_id = 3;
}

message RecallAck {
  string message_id = 1;
  string conversation_id = 2;
  bool success = 3;
  string reason = 4;
  string recalled_by = 5;
  uint64 recalled_at = 6;
}

message RecallNotify {
  string message_id = 1;
  string conversation_id = 2;
  string recalled_by = 3;
  uint64 recalled_at = 4;
}

message RecallNotifyAck {
  string message_id = 1;
  string conversation_id = 2;
  string receiver_id = 3;
  uint64 recalled_at = 4;
}
```

- [ ] **Step 4: Configure Android Java-lite generation**

In root `build.gradle`, add:

```gradle
id "com.google.protobuf" version "0.10.0" apply false
```

In `app/build.gradle`, apply the plugin:

```gradle
id "com.google.protobuf"
```

Add the shared schema source directory inside `android`:

```gradle
sourceSets {
    main {
        proto.srcDir "../protocol-schema/src/main/proto"
    }
}
```

Add the runtime and generator configuration:

```gradle
dependencies {
    implementation "com.google.protobuf:protobuf-javalite:4.34.1"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.34.1"
    }
    generateProtoTasks {
        all().configureEach { task ->
            task.builtins {
                java { option "lite" }
            }
        }
    }
}
```

- [ ] **Step 5: Configure mock-server Java generation from the same schema**

In `mock-server/pom.xml`, add properties:

```xml
<protobuf.version>4.34.1</protobuf.version>
<protobuf-maven-plugin.version>0.6.1</protobuf-maven-plugin.version>
<os-maven-plugin.version>1.7.1</os-maven-plugin.version>
```

Add dependency:

```xml
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>${protobuf.version}</version>
</dependency>
```

Add build extension and plugin:

```xml
<extensions>
    <extension>
        <groupId>kr.motd.maven</groupId>
        <artifactId>os-maven-plugin</artifactId>
        <version>${os-maven-plugin.version}</version>
    </extension>
</extensions>
<plugins>
    <plugin>
        <groupId>org.xolstice.maven.plugins</groupId>
        <artifactId>protobuf-maven-plugin</artifactId>
        <version>${protobuf-maven-plugin.version}</version>
        <configuration>
            <protoSourceRoot>${project.basedir}/../protocol-schema/src/main/proto</protoSourceRoot>
            <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
        </configuration>
        <executions>
            <execution>
                <goals><goal>compile</goal><goal>test-compile</goal></goals>
            </execution>
        </executions>
    </plugin>
</plugins>
```

Merge this into the existing `<build><plugins>` block; do not create a second `<build>` element.

- [ ] **Step 6: Run both generated-source builds**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.protocol.GeneratedProtocolSmokeTest --console=plain
```

Expected: PASS.

Run from `mock-server`:

```powershell
mvn -q -DskipTests compile
```

Expected: BUILD SUCCESS with generated classes under `mock-server/target/generated-sources/protobuf/java`; generated sources must not be committed.

- [ ] **Step 7: Commit the schema/toolchain unit**

```powershell
git add protocol-schema/src/main/proto/im/v2/im_protocol.proto build.gradle app/build.gradle mock-server/pom.xml app/src/test/java/com/buyansong/im/protocol/GeneratedProtocolSmokeTest.kt
git commit -m "build: generate shared websocket protobuf schema"
```

---

### Task 2: Add Android Protobuf Codecs and Mappers Beside the Legacy Protocol

**Files:**
- Create: `app/src/main/java/com/buyansong/im/protocol/ImEnvelopeCodec.kt`
- Create: `app/src/test/java/com/buyansong/im/protocol/ImEnvelopeCodecTest.kt`
- Create: `app/src/main/java/com/buyansong/im/message/MessageProtoMapper.kt`
- Create: `app/src/test/java/com/buyansong/im/message/MessageProtoMapperTest.kt`

**Interfaces:**
- Produces: `ImEnvelopeCodec.encode(envelope: ImEnvelope): ByteArray`.
- Produces: `ImEnvelopeCodec.decode(bytes: ByteArray): ImEnvelope`.
- Produces: `MessageProtoMapper.sendEnvelope(message: ChatMessage): ImEnvelope`.
- Produces: `MessageProtoMapper.incomingMessage(payload: ChatMessagePayload): ChatMessage` with explicit incoming status/direction supplied by the caller or mapper contract.
- Produces typed envelope builders for `DeliveryAck`, `ReadAck`, `RecallMessage`, and `RecallNotifyAck`.
- Produces: `sendEnvelopeFromPendingJson(json: String): ImEnvelope` for existing `pending_messages.packet_body` rows.
- Consumes: generated payload classes from Task 1.
- Does not modify `ImConnection` yet, so this task compiles and passes while the legacy wire protocol remains active.

- [ ] **Step 1: Write failing codec tests**

Cover these exact cases in `ImEnvelopeCodecTest.kt`:

```kotlin
@Test fun heartbeatRoundTrips()
@Test fun decodeRejectsEmptyBytes()
@Test fun decodeRejectsVersionOne()
@Test fun decodeRejectsPayloadNotSet()
@Test fun decodeRejectsEnvelopeOver64KiB()
```

Use `Heartbeat(clientTime=123)` for the round trip and `ImEnvelope.newBuilder().setProtocolVersion(2).build()` for missing payload.

- [ ] **Step 2: Run codec tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.protocol.ImEnvelopeCodecTest --console=plain
```

Expected: FAIL because `ImEnvelopeCodec` does not exist.

- [ ] **Step 3: Implement the validation wrapper**

Create `ImEnvelopeCodec.kt` with this public contract:

```kotlin
object ImEnvelopeCodec {
    const val PROTOCOL_VERSION = 2
    const val MAX_ENVELOPE_BYTES = 64 * 1024

    fun encode(envelope: ImEnvelope): ByteArray
    fun decode(bytes: ByteArray): ImEnvelope
}
```

Both methods must enforce size, `protocolVersion == 2`, and `payloadCase != PAYLOAD_NOT_SET`. Convert `InvalidProtocolBufferException` and validation failures to the existing `ProtocolException` so `OkHttpImConnection` keeps one failure path.

- [ ] **Step 4: Write mapper tests before changing active transport code**

Tests must cover:

```text
single text ChatMessage -> SEND_MESSAGE envelope
group text with mentions -> SEND_MESSAGE envelope
single image metadata -> SEND_MESSAGE envelope
ChatMessagePayload -> incoming ChatMessage field parity
existing pending JSON snapshot -> SEND_MESSAGE envelope
unknown/unspecified conversation or message enum -> ProtocolException
```

For image parity, assert URL, thumbnail URL, width, height, MIME type, and size. For group parity, assert group ID, group name, and de-duplicated mention IDs.

- [ ] **Step 5: Run mapper tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageProtoMapperTest --console=plain
```

Expected: FAIL because `MessageProtoMapper` does not exist.

- [ ] **Step 6: Implement domain/protobuf mapping**

Implement explicit enum mappings rather than `valueOf`:

```kotlin
ConversationType.SINGLE -> CONVERSATION_TYPE_SINGLE
ConversationType.GROUP -> CONVERSATION_TYPE_GROUP
MessageType.TEXT -> MESSAGE_TYPE_TEXT
MessageType.IMAGE -> MESSAGE_TYPE_IMAGE
```

Reject `UNSPECIFIED` and `UNRECOGNIZED`. Use `hasServerSeq()`, `hasImage()`, and `hasSenderProfileVersion()` to preserve nullable domain fields.

Keep one JSON compatibility function exclusively for durable outbox snapshots:

```kotlin
fun sendEnvelopeFromPendingJson(json: String): ImEnvelope
```

It must parse the same fields currently produced by `ChatMessage.toSendBody()`. No WebSocket path may serialize this JSON after Task 3.

- [ ] **Step 7: Run standalone codec and mapper tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.protocol.ImEnvelopeCodecTest --tests com.buyansong.im.message.MessageProtoMapperTest --console=plain
```

Expected: PASS while all current application code still uses `ImPacket`.

- [ ] **Step 8: Commit the additive Android protocol unit**

```powershell
git add app/src/main/java/com/buyansong/im/protocol/ImEnvelopeCodec.kt app/src/main/java/com/buyansong/im/message/MessageProtoMapper.kt app/src/test/java/com/buyansong/im/protocol/ImEnvelopeCodecTest.kt app/src/test/java/com/buyansong/im/message/MessageProtoMapperTest.kt
git commit -m "feat: add android protobuf envelope adapters"
```

---

### Task 3: Atomically Cut Android Transport and Message Handling to Protobuf

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/connection/ImConnection.kt`
- Modify: `app/src/main/java/com/buyansong/im/connection/OkHttpImConnection.kt`
- Modify: `app/src/main/java/com/buyansong/im/connection/AuthPacketFactory.kt`
- Modify: `app/src/main/java/com/buyansong/im/connection/HeartbeatPacketFactory.kt`
- Modify: `app/src/main/java/com/buyansong/im/connection/ConnectionStateReducer.kt`
- Modify: `app/src/main/java/com/buyansong/im/connection/ConnectionLifecycleManager.kt`
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Modify: `app/src/main/java/com/buyansong/im/message/MessagePacketProcessor.kt`
- Modify: all tests/fakes importing `ImPacket` under `app/src/test/java/com/buyansong/im`

**Interfaces:**
- Produces: `ImConnection.incomingPackets: SharedFlow<ImEnvelope>` and `send(envelope: ImEnvelope): Boolean`.
- Consumes: `ImEnvelopeCodec` and `MessageProtoMapper` from Task 2.
- This is an atomic compile boundary: connection interface, all producers/consumers, and test fakes change together.

- [ ] **Step 1: Change the connection interface, factories, and OkHttp transport together**

Change `ImConnection` to:

```kotlin
val incomingPackets: SharedFlow<ImEnvelope>
fun send(envelope: ImEnvelope): Boolean
```

Keep `connect(token)` and `disconnect()` unchanged. Make auth and heartbeat factories return version-2 envelopes. In `OkHttpImConnection`, encode/decode via `ImEnvelopeCodec`; preserve the existing `Connecting -> Connected -> Authenticated/Failed` lifecycle and `ProtocolException` path.

- [ ] **Step 2: Update reducer and heartbeat inspection without changing policy**

Switch `ConnectionStateReducer` on `envelope.payloadCase` for `AUTH_ACK` and `AUTH_NACK`. In `ConnectionLifecycleManager`, increment `heartbeatAckGeneration` only for `HEARTBEAT_ACK`. Do not alter heartbeat counters, intervals, reconnect scheduling, or the token-provider call.

- [ ] **Step 3: Replace repository packet construction**

Change all sends in `MessageRepository`:

```text
SEND_MESSAGE       -> MessageProtoMapper.sendEnvelope(message)
DELIVERY_ACK       -> typed DeliveryAck envelope
READ_ACK           -> typed ReadAck envelope
RECALL_MESSAGE     -> typed RecallMessage envelope
RECALL_NOTIFY_ACK  -> typed RecallNotifyAck envelope
```

Change `handlePacket` to switch on `envelope.payloadCase` and pass generated payloads directly to typed handlers. Replace `handleAck(json)`, `handleIncoming(json)`, `handleReadAck(json)`, `handleRecallAck(json)`, and `handleRecallNotify(json)` with typed parameters. Preserve DAO writes, unread logic, thumbnail scheduling, ACK deletion, recall behavior, and update flows exactly.

- [ ] **Step 4: Keep pending-message SQLite compatible**

Do not change `PendingMessage`, `AndroidPendingMessageDao`, or the database version. Continue storing the current JSON send snapshot in `packet_body`, but document it in code as a durable outbox format rather than a WebSocket format. On retry, replace:

```kotlin
ImPacket(cmd = pending.packetCmd, body = pending.packetBody.toByteArray())
```

with:

```kotlin
MessageProtoMapper.sendEnvelopeFromPendingJson(pending.packetBody)
```

Validate that `packetCmd` is the existing `SEND_MESSAGE` value `10`; malformed snapshots must mark the message failed according to the existing retry-exhaustion path rather than crash the worker.

- [ ] **Step 5: Convert all Android test fixtures**

Replace JSON `ImPacket` fixtures with generated envelopes. Keep at least one `sendEnvelopeFromPendingJson` test so old queued rows remain retryable after upgrade. Mechanically update unrelated `FakeConnection` implementations to the new `ImEnvelope` signature without changing their assertions.

- [ ] **Step 6: Run Android connection, message, and view-model regressions**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.buyansong.im.connection.*" --tests "com.buyansong.im.message.*" --tests "com.buyansong.im.chat.*" --tests "com.buyansong.im.conversation.*" --console=plain
```

Expected: PASS. In particular, ACK retry, duplicate suppression, message ordering, image metadata, group mentions, read receipts, and recall tests retain their previous expectations.

- [ ] **Step 7: Commit the atomic Android cutover**

```powershell
git add app/src/main/java/com/buyansong/im/connection app/src/main/java/com/buyansong/im/message app/src/test/java/com/buyansong/im
git commit -m "refactor: cut android websocket traffic to protobuf"
```

---

### Task 4: Add Server Protobuf Codecs and Mappers Beside the Legacy Protocol

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/protocol/ImEnvelopeCodec.java`
- Create: `mock-server/src/test/java/com/buyansong/imserver/protocol/ImEnvelopeCodecTest.java`
- Create: `mock-server/src/main/java/com/buyansong/imserver/session/MessageProtoMapper.java`
- Create: `mock-server/src/test/java/com/buyansong/imserver/session/MessageProtoMapperTest.java`

**Interfaces:**
- Produces: server-side `ImEnvelopeCodec.encode/decode` with the same validation as Android.
- Produces typed builders for auth ACK, heartbeat ACK, message ACK/receive, read/delivery ACK, and recall flows.
- Produces `JsonObject messageToStoredJson(ChatMessagePayload)` and `ChatMessagePayload storedJsonToMessage(JsonObject)` only for the existing accepted-message SQLite adapter.
- Consumes: generated schema from Task 1.
- Does not modify `OutboundClient` or Netty handlers yet, so legacy server tests remain green.

- [ ] **Step 1: Write server codec tests**

Mirror the Android cases: round trip, malformed protobuf, version mismatch, missing payload, and 64 KiB limit. Also assert Android-generated golden bytes decode on Java by placing one small hex/base64 heartbeat fixture in both codec tests.

- [ ] **Step 2: Run the test and verify failure**

From `mock-server`:

```powershell
mvn -q -Dtest=ImEnvelopeCodecTest test
```

Expected: FAIL because server `ImEnvelopeCodec` does not exist.

- [ ] **Step 3: Implement mirrored validation**

Create `ImEnvelopeCodec.java` with:

```java
public static final int PROTOCOL_VERSION = 2;
public static final int MAX_ENVELOPE_BYTES = 64 * 1024;
public static byte[] encode(ImEnvelope envelope);
public static ImEnvelope decode(byte[] bytes);
```

Reject mismatched version and `PAYLOAD_NOT_SET`; wrap `InvalidProtocolBufferException` in the existing server `ProtocolException`.

- [ ] **Step 4: Write mapper parity tests**

Cover text, image, group mention, optional server sequence/profile version, message ACK, and stored JSON round trips. Assert that `storedJsonToMessage(messageToStoredJson(payload))` preserves every schema field used by the router.

- [ ] **Step 5: Run mapper tests and verify failure**

```powershell
mvn -q -Dtest=MessageProtoMapperTest test
```

Expected: FAIL because `MessageProtoMapper` does not exist.

- [ ] **Step 6: Implement typed builders and persistence compatibility mapping**

Use explicit Protobuf enum switches and reject `UNSPECIFIED`/`UNRECOGNIZED`. Keep Gson conversion methods scoped to the existing accepted-message store; no Netty or public router handler method should consume JSON after Task 5.

- [ ] **Step 7: Run standalone server codec and mapper tests**

```powershell
mvn -q -Dtest=ImEnvelopeCodecTest,MessageProtoMapperTest test
```

Expected: PASS while active server transport still uses `ImPacket`.

- [ ] **Step 8: Commit the additive server protocol unit**

```powershell
git add mock-server/src/main/java/com/buyansong/imserver/protocol/ImEnvelopeCodec.java mock-server/src/main/java/com/buyansong/imserver/session/MessageProtoMapper.java mock-server/src/test/java/com/buyansong/imserver/protocol/ImEnvelopeCodecTest.java mock-server/src/test/java/com/buyansong/imserver/session/MessageProtoMapperTest.java
git commit -m "feat: add server protobuf envelope adapters"
```

---

### Task 5: Atomically Cut Server Transport and Routing to Protobuf

**Files:**
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/ChannelOutboundClient.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/session/OutboundClient.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/WebSocketFrameHandler.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/MockImServer.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
- Modify: all server tests/fakes importing `ImPacket` or implementing `OutboundClient`

**Interfaces:**
- Produces: `OutboundClient.send(ImEnvelope envelope)` and typed Netty/router dispatch.
- Consumes: `ImEnvelopeCodec` and `MessageProtoMapper` from Task 4.
- This is an atomic compile boundary: outbound interface, Netty dispatcher, router signatures, persistence adapter calls, and test fakes change together.

- [ ] **Step 1: Change outbound transport and Netty parsing together**

Change `OutboundClient.send` and `ChannelOutboundClient.send` to accept `ImEnvelope`; encode it directly into `BinaryWebSocketFrame`. Update capturing/fake clients to store `List<ImEnvelope>`.

In `WebSocketFrameHandler`, decode once and switch on `envelope.getPayloadCase()`. Authentication accepts only `AUTH` before registration; heartbeat and business messages retain the current authenticated-session gates. Unknown or missing payloads produce a protocol failure and close the channel. Keep typed `AUTH_NACK` plus immediate close behavior.

Map the existing `com.buyansong.imserver.auth.TokenService.AuthFailureReason` to the generated `com.buyansong.im.protocol.v2.AuthFailureReason` explicitly; use fully qualified names where necessary so the two enums cannot be accidentally interchanged.

- [ ] **Step 2: Change router method signatures**

Use generated types:

```java
handleSendMessage(String senderUserId, SendMessage request)
handleDeliveryAck(String receiverUserId, DeliveryAck ack)
handleReadAck(String socketUserId, ReadAck ack)
handleRecallMessage(String socketUserId, RecallMessage request)
handleRecallNotifyAck(String socketUserId, RecallNotifyAck ack)
```

`handleAuth` continues to verify a string access token but receives it from `Auth.getAccessToken()`. `handleHeartbeat` builds a typed `HeartbeatAck`.

- [ ] **Step 3: Replace router JSON field access with generated getters/builders**

Use `ChatMessagePayload.toBuilder()` when adding server sequence, server time, sender profile version, group name, or per-recipient receiver ID. Build `MessageAck` once and reuse it for idempotent duplicate ACKs. Switch group/message enums explicitly and reject `UNSPECIFIED`/`UNRECOGNIZED` before persistence or routing.

Preserve these invariants:

```text
sender identity comes from authenticated socket, never payload senderId
duplicate messageId returns the original serverSeq ACK
group send fans out one receiver-specific payload per member
delivery ACK removes the receiver's pending copy
read ACK broadcast semantics are unchanged
recall authorization and pending recall notification semantics are unchanged
```

- [ ] **Step 4: Isolate existing accepted-message JSON storage**

Do not alter SQLite DDL. `AcceptedMessage` should hold typed `MessageAck` and `ChatMessagePayload`. At `SqliteAcceptedMessageStore` boundaries only, call `MessageProtoMapper.messageToStoredJson`, `ackToStoredJson`, and inverse functions for existing `ack_json`/`message_json` columns. Gson must no longer appear in Netty WebSocket parsing or public router handler methods.

- [ ] **Step 5: Aggregate WebSocket continuation frames**

In `MockImServer`, configure the WebSocket protocol max payload and add:

```java
new WebSocketFrameAggregator(ImEnvelopeCodec.MAX_ENVELOPE_BYTES)
```

before `WebSocketFrameHandler`. This reassembles fragmented WebSocket messages before Protobuf parsing without reintroducing application-level length framing.

- [ ] **Step 6: Convert all server protocol tests and fakes**

Replace `new ImPacket(cmd, jsonBytes)` fixtures with generated request envelopes/payloads. Assertions use `getPayloadCase()` and typed getters. Keep test coverage for single/group send, duplicate ACK, offline replay, push enqueue, delivery ACK, group read ACK, recall, and auth rejection.

- [ ] **Step 7: Run all mock-server tests**

```powershell
mvn -q test
```

Expected: BUILD SUCCESS. Include auth success/NACK, unauthenticated rejection, malformed payload close, fragmented-message aggregation, single/group send, duplicate ACK, offline replay, push enqueue, delivery ACK, group read ACK, and recall. No test should parse a WebSocket payload with Gson; JSON is allowed only in HTTP tests and accepted-message persistence compatibility tests.

- [ ] **Step 8: Commit the atomic server cutover**

```powershell
git add mock-server/src/main/java/com/buyansong/imserver/netty mock-server/src/main/java/com/buyansong/imserver/session mock-server/src/main/java/com/buyansong/imserver/MockImServer.java mock-server/src/test
git commit -m "refactor: cut server websocket traffic to protobuf"
```

---

### Task 6: Cut Over End-to-End and Remove the Legacy Wire Protocol

**Files:**
- Delete: `app/src/main/java/com/buyansong/im/protocol/ImPacket.kt`
- Delete: `app/src/main/java/com/buyansong/im/protocol/ImPacketCodec.kt`
- Delete: `app/src/main/java/com/buyansong/im/protocol/ImCommand.kt`
- Delete: `app/src/main/java/com/buyansong/im/protocol/Crc32.kt`
- Delete: `mock-server/src/main/java/com/buyansong/imserver/protocol/ImPacket.java`
- Delete: `mock-server/src/main/java/com/buyansong/imserver/protocol/ImPacketCodec.java`
- Delete: `mock-server/src/main/java/com/buyansong/imserver/protocol/ImCommand.java`
- Modify: remaining tests/docs found by legacy-symbol scan
- Modify: `README.md`
- Modify: `docs/status/B1-auth.md`
- Modify: `docs/status/B7-heartbeat-reconnect.md`
- Create: `docs/status/websocket-protobuf-protocol.md`

**Interfaces:**
- Consumes: completed Android and server Protobuf paths.
- Produces: one supported WebSocket wire protocol, version 2.

- [ ] **Step 1: Run a legacy-symbol scan before deletion**

```powershell
rg -n -S "ImPacket|ImPacketCodec|ImCommand|Crc32|MAGIC|Invalid CRC|packet\.cmd|packet\.body" app/src mock-server/src
```

Expected: only legacy source files, comments/docs scheduled for update, and the intentionally retained `PendingMessage.packetCmd/packetBody` persistence fields remain. Any active WebSocket code hit must be migrated before proceeding.

- [ ] **Step 2: Delete legacy codec/container/command files**

Delete the seven files listed above. Do not delete `ProtocolException`; both new codecs use it.

- [ ] **Step 3: Compile both sides immediately**

```powershell
.\gradlew.bat :app:compileDebugKotlin --console=plain
```

```powershell
Set-Location mock-server
mvn -q -DskipTests compile
```

Expected: both compile with no legacy protocol imports.

- [ ] **Step 4: Add end-to-end protocol documentation**

Document:

```text
one binary WebSocket message = one ImEnvelope
protocol_version = 2
oneof payload is the sole message discriminator
64 KiB maximum envelope
WSS/TLS is required for production integrity
field-number reservation and compatibility rules
client/server lockstep cutover assumption
pending Android outbox JSON and server accepted-message JSON are internal persistence formats, not wire formats
```

Update resume-facing docs to say “OkHttp WebSocket + versioned Protobuf oneof envelope,” not “Magic + Header + Body + CRC32.”

- [ ] **Step 5: Run full automated verification**

From repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

From `mock-server`:

```powershell
mvn -q test
```

Expected: both suites PASS.

- [ ] **Step 6: Run manual two-client verification**

1. Start mock server and connect two Android clients.
2. Verify login produces `AUTH_ACK` and heartbeat reaches `HEARTBEAT_ACK` in foreground and background.
3. Send single text and image messages; verify message ACK, delivery ACK, offline replay, and image metadata.
4. Send group text with mentions; verify fan-out and group read cursors.
5. Recall a delivered and an offline-pending message; verify ACK/notify behavior.
6. Let Access Token expire; verify reconnect calls the existing token provider, refreshes JWT, and re-authenticates.
7. Disable and restore network; verify immediate recovery and unchanged 1–30 second retry policy.
8. Corrupt a protobuf byte or send protocol version 1; verify protocol failure closes the connection without crashing either process.

- [ ] **Step 7: Commit cleanup and documentation**

```powershell
git add app/src mock-server/src README.md docs/status protocol-schema
git commit -m "refactor: complete websocket protobuf cutover"
```

---

## Final Verification Checklist

- [ ] `rg` finds no active imports or calls to legacy `ImPacket`, `ImPacketCodec`, `ImCommand`, `Crc32`, Magic, or CRC validation.
- [ ] Android and Java generate classes from the same checked-in `.proto`; generated source directories are ignored by Git.
- [ ] Every outbound envelope sets protocol version 2 and exactly one payload.
- [ ] Both decoders reject oversized, malformed, wrong-version, and payload-less envelopes.
- [ ] Authentication, six connection states, heartbeat timing, missed-ACK limit, exponential backoff, network callbacks, and token provider behavior are unchanged.
- [ ] Single/group text, images, mentions, message ACK, delivery ACK, read ACK, recall ACK/notify, offline replay, ordering, and idempotency pass existing tests.
- [ ] Android pending JSON rows created before the upgrade can still be retried as Protobuf sends.
- [ ] Mock-server accepted-message JSON rows created before the upgrade can still be restored into typed payloads.
- [ ] HTTP JSON endpoints remain unaffected.

## Self-Review Notes

- Scope is one subsystem: the WebSocket wire protocol and the direct adapters required to consume it.
- The plan deliberately does not migrate internal SQLite formats; that would be a separate storage migration with independent rollback concerns.
- `oneof payload` is the sole discriminator; adding both `cmd` and `oneof` would create two sources of truth.
- WebSocket already supplies application-message boundaries, so length and Magic are not duplicated. WSS/TLS, not CRC32, supplies production integrity.
- The lockstep cutover assumption is explicit. A real independently deployed server must support legacy and Protobuf clients concurrently before removing version 1.
