# Event-Driven Message Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the authenticated-state 1-second SQLite polling loop with an event-driven Outbox that wakes on authentication, pending-row changes, or the exact earliest retry deadline while preserving the existing 5s/10s/20s/40s/60s retry and five-attempt semantics.

**Architecture:** `ConnectionLifecycleManager` remains the sole heartbeat and reconnect owner; the Outbox only consumes its authoritative `ConnectionState.Authenticated` transition. `MessageRepository` owns a process-local monotonic `OutboxChangeSignal` and emits it after every committed pending-row mutation, while SQLite remains the durable source of truth. `MessageOutboxWorker` uses `collectLatest` to scope work to an authenticated connection and suspends until either the signal revision changes or `PendingMessageDao.earliestNextRetryAt()` becomes due.

**Tech Stack:** Kotlin 2.x, Android SQLite, Kotlin Coroutines 1.9 (`StateFlow`, `collectLatest`, `first`, `withTimeoutOrNull`), JUnit 4, `kotlinx-coroutines-test`.

## Global Constraints

- Preserve the existing SQLite transaction order: write `messages`, `conversations`, and `pending_messages`, commit, then attempt network send.
- Preserve the original `packetCmd`, `packetBody`, and `messageId` on every retry.
- Preserve retry delays exactly: 5s, 10s, 20s, 40s, 60s, with five recorded retry attempts before `FAILED`.
- Do not use `HEARTBEAT_ACK` as an Outbox clock; heartbeat only detects dead connections and drives reconnect/authentication.
- Do not query or retry pending rows while the connection state is not `ConnectionState.Authenticated`.
- On every transition to `Authenticated`, query SQLite immediately so overdue rows survive process death and reconnect.
- Do not add WorkManager, AlarmManager, foreground services, or a new dependency for this process-local retry path.
- Treat duplicate network sends as expected at-least-once behavior; server-side `messageId` idempotency remains responsible for effective-once acceptance.
- Preserve unrelated working-tree changes in `MainActivity.kt`, `SeqGenerator.kt`, and `PushPollWorker.kt`.

---

## File Structure

- Create `app/src/main/java/com/buyansong/im/message/OutboxChangeSignal.kt`: process-local monotonic wake signal with no persistence responsibility.
- Modify `app/src/main/java/com/buyansong/im/storage/PendingMessageDao.kt`: expose the earliest retry deadline.
- Modify `app/src/main/java/com/buyansong/im/storage/AndroidPendingMessageDao.kt`: implement the indexed `MIN(next_retry_at)` lookup.
- Modify `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`: emit wake revisions after committed pending changes and expose the next deadline to the worker.
- Modify `app/src/main/java/com/buyansong/im/message/MessageOutboxWorker.kt`: replace periodic polling with authenticated event/deadline suspension.
- Create `app/src/test/java/com/buyansong/im/storage/PendingMessageDaoTest.kt`: cover earliest-deadline semantics in the in-memory DAO.
- Create `app/src/test/java/com/buyansong/im/message/OutboxChangeSignalTest.kt`: cover monotonic and non-lossy revision behavior.
- Create `app/src/test/java/com/buyansong/im/message/MessageRepositoryOutboxSignalTest.kt`: cover post-commit enqueue/ACK/retry notifications.
- Create `app/src/test/java/com/buyansong/im/message/MessageOutboxWorkerTest.kt`: cover deadline scheduling, reconnect recovery, ACK cancellation, and absence of polling.
- Modify `docs/status/B9-message-reliability.md`: document the event-driven behavior and updated verification evidence.

---

### Task 1: Earliest Pending Retry Deadline

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/PendingMessageDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/AndroidPendingMessageDao.kt`
- Create: `app/src/test/java/com/buyansong/im/storage/PendingMessageDaoTest.kt`

**Interfaces:**
- Consumes: existing `PendingMessage.nextRetryAt: Long` and the `idx_pending_next_retry` SQLite index.
- Produces: `PendingMessageDao.earliestNextRetryAt(): Long?` for the Outbox scheduler.

- [ ] **Step 1: Write the failing in-memory DAO tests**

Create `PendingMessageDaoTest.kt` with focused cases for empty, ordered, and deleted rows:

```kotlin
package com.buyansong.im.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingMessageDaoTest {
    @Test
    fun earliestNextRetryAtReturnsNullWhenOutboxIsEmpty() {
        assertNull(InMemoryPendingMessageDao().earliestNextRetryAt())
    }

    @Test
    fun earliestNextRetryAtTracksTheMinimumRemainingDeadline() {
        val dao = InMemoryPendingMessageDao()
        dao.upsert(pending("late", 20_000L))
        dao.upsert(pending("early", 5_000L))

        assertEquals(5_000L, dao.earliestNextRetryAt())

        dao.delete("early")
        assertEquals(20_000L, dao.earliestNextRetryAt())
    }

    private fun pending(messageId: String, nextRetryAt: Long) = PendingMessage(
        messageId = messageId,
        packetCmd = 10,
        packetBody = "{\"messageId\":\"$messageId\"}",
        retryCount = 0,
        nextRetryAt = nextRetryAt,
        createdAt = 0L
    )
}
```

- [ ] **Step 2: Run the test and verify the missing interface fails compilation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.PendingMessageDaoTest --console=plain
```

Expected: compilation fails because `earliestNextRetryAt` does not exist.

- [ ] **Step 3: Add the DAO contract and in-memory implementation**

Add to `PendingMessageDao` and `InMemoryPendingMessageDao`:

```kotlin
interface PendingMessageDao {
    fun upsert(pendingMessage: PendingMessage)
    fun delete(messageId: String): Boolean
    fun findByMessageId(messageId: String): PendingMessage?
    fun dueMessages(now: Long, limit: Int): List<PendingMessage>
    fun earliestNextRetryAt(): Long?
}

override fun earliestNextRetryAt(): Long? {
    return pendingById.values.minOfOrNull(PendingMessage::nextRetryAt)
}
```

- [ ] **Step 4: Implement the Android SQLite minimum lookup**

Add this implementation to `AndroidPendingMessageDao`:

```kotlin
override fun earliestNextRetryAt(): Long? {
    return database.rawQuery(
        "SELECT MIN(next_retry_at) FROM pending_messages",
        null
    ).use { cursor ->
        if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
    }
}
```

The existing `idx_pending_next_retry` index supports this lookup; no schema migration is required.

- [ ] **Step 5: Run the focused test and Android compilation**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.PendingMessageDaoTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Expected: both commands pass.

- [ ] **Step 6: Commit the deadline-query slice**

```powershell
git add app/src/main/java/com/buyansong/im/storage/PendingMessageDao.kt app/src/main/java/com/buyansong/im/storage/AndroidPendingMessageDao.kt app/src/test/java/com/buyansong/im/storage/PendingMessageDaoTest.kt
git commit -m "feat: expose earliest outbox retry deadline"
```

---

### Task 2: Durable-Mutation Wake Signal

**Files:**
- Create: `app/src/main/java/com/buyansong/im/message/OutboxChangeSignal.kt`
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Create: `app/src/test/java/com/buyansong/im/message/OutboxChangeSignalTest.kt`
- Create: `app/src/test/java/com/buyansong/im/message/MessageRepositoryOutboxSignalTest.kt`

**Interfaces:**
- Consumes: `PendingMessageDao.earliestNextRetryAt(): Long?` from Task 1.
- Produces: `OutboxChangeSignal.revisions: StateFlow<Long>`, `OutboxChangeSignal.notifyChanged()`, `MessageRepository.outboxRevisions`, and `MessageRepository.earliestPendingRetryAt()`.

- [ ] **Step 1: Write the failing monotonic-signal test**

Create `OutboxChangeSignalTest.kt`:

```kotlin
package com.buyansong.im.message

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxChangeSignalTest {
    @Test
    fun notifyChangedPublishesAMonotonicRevision() {
        val signal = OutboxChangeSignal()

        assertEquals(0L, signal.revisions.value)
        signal.notifyChanged()
        signal.notifyChanged()

        assertEquals(2L, signal.revisions.value)
    }
}
```

- [ ] **Step 2: Run the signal test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.OutboxChangeSignalTest --console=plain
```

Expected: compilation fails because `OutboxChangeSignal` is missing.

- [ ] **Step 3: Implement the process-local revision signal**

Create `OutboxChangeSignal.kt`:

```kotlin
package com.buyansong.im.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class OutboxChangeSignal {
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = mutableRevisions.asStateFlow()

    fun notifyChanged() {
        mutableRevisions.update { revision -> revision + 1L }
    }
}
```

Use a revision rather than a one-shot `SharedFlow<Unit>` so a change that occurs between the database query and suspension remains observable and cannot be lost.

- [ ] **Step 4: Write failing repository signal tests**

Create `MessageRepositoryOutboxSignalTest.kt` using the existing in-memory DAOs and a fake `ImConnection`. Cover these exact assertions:

```kotlin
@Test
fun sendTextSignalsOnlyAfterTheTransactionCommits() {
    val events = mutableListOf<String>()
    val transactionRunner = RecordingTransactionRunner(events)
    val fixture = Fixture(
        transactionRunner = transactionRunner,
        events = events,
        onSend = { signalRevision ->
            assertEquals(1L, signalRevision)
            assertTrue(transactionRunner.committed)
        }
    )

    fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)

    assertEquals(listOf("begin", "commit", "send"), events)
}

@Test
fun messageAckDeletesPendingAndAdvancesTheRevision() {
    val fixture = Fixture()
    val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
    val revisionAfterEnqueue = fixture.signal.revisions.value

    fixture.repository.handlePacket(
        ImPacket(
            ImCommand.MESSAGE_ACK.value,
            """{"messageId":"${message.messageId}","serverSeq":88,"serverTime":1200}""".toByteArray()
        )
    )

    assertTrue(fixture.pendingDao.findByMessageId(message.messageId) == null)
    assertTrue(fixture.signal.revisions.value > revisionAfterEnqueue)
}

@Test
fun failedTransactionDoesNotPublishAnOutboxChange() {
    val fixture = Fixture(
        transactionRunner = object : TransactionRunner {
            override fun runInTransaction(block: () -> Unit) {
                throw IllegalStateException("rollback")
            }
        }
    )

    assertThrows(IllegalStateException::class.java) {
        fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
    }

    assertEquals(0L, fixture.signal.revisions.value)
}
```

Use these concrete test helpers. The fixture injects one `OutboxChangeSignal` into the repository, and the fake connection observes its revision before recording the network send:

```kotlin
private class RecordingTransactionRunner(
    private val events: MutableList<String>
) : TransactionRunner {
    var committed = false

    override fun runInTransaction(block: () -> Unit) {
        events += "begin"
        block()
        committed = true
        events += "commit"
    }
}

private class Fixture(
    transactionRunner: TransactionRunner = TransactionRunner.immediate(),
    private val events: MutableList<String> = mutableListOf(),
    onSend: (Long) -> Unit = {}
) {
    val signal = OutboxChangeSignal()
    val messageDao = InMemoryMessageDao()
    val pendingDao = InMemoryPendingMessageDao()
    private val conversationDao = InMemoryConversationDao()
    private val connection = FakeConnection(events) {
        onSend(signal.revisions.value)
    }
    val repository = MessageRepository(
        messageDao = messageDao,
        conversationDao = conversationDao,
        pendingMessageDao = pendingDao,
        connection = connection,
        messageIdGenerator = MessageIdGenerator(startCounter = 1),
        seqGenerator = SeqGenerator(),
        transactionRunner = transactionRunner,
        outboxChangeSignal = signal
    )
}

private class FakeConnection(
    private val events: MutableList<String>,
    private val beforeSend: () -> Unit
) : ImConnection {
    override val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    override val incomingPackets = MutableSharedFlow<ImPacket>()

    override fun connect(token: String) = Unit
    override fun disconnect() = Unit

    override fun send(packet: ImPacket): Boolean {
        beforeSend()
        events += "send"
        return true
    }
}
```

This proves the revision remains zero if the transaction throws and is already one before the first network write.

- [ ] **Step 5: Run the repository tests and verify the missing constructor/accessors fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryOutboxSignalTest --console=plain
```

Expected: compilation fails because `MessageRepository` does not yet accept or expose the signal.

- [ ] **Step 6: Integrate the signal into all pending-row mutation paths**

Add the constructor dependency and worker-facing accessors:

```kotlin
class MessageRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val pendingMessageDao: PendingMessageDao,
    private val connection: ImConnection,
    private val messageIdGenerator: MessageIdGenerator,
    private val seqGenerator: SeqGenerator,
    private val retryPolicy: MessageRetryPolicy = MessageRetryPolicy(),
    private val transactionRunner: TransactionRunner = TransactionRunner.immediate(),
    private val outboxChangeSignal: OutboxChangeSignal = OutboxChangeSignal(),
    private val profileRepository: ProfileRepository? = null,
    thumbnailCache: ChatThumbnailCache = NoopChatThumbnailCache,
    private val thumbnailDownloadScheduler: ThumbnailDownloadScheduler = ImmediateThumbnailDownloadScheduler(thumbnailCache),
    private val groupReadCursorRepository: GroupReadCursorRepository? = null
) {
    internal val outboxRevisions: StateFlow<Long>
        get() = outboxChangeSignal.revisions

    internal fun earliestPendingRetryAt(): Long? = pendingMessageDao.earliestNextRetryAt()
}
```

Call `outboxChangeSignal.notifyChanged()` only after successful durable mutations:

```kotlin
transactionRunner.runInTransaction {
    messageDao.insertOrIgnore(message)
    conversationDao.upsertFromMessage(message, incrementUnread = false)
    pendingMessageDao.upsert(
        PendingMessage(
            messageId = message.messageId,
            packetCmd = packet.cmd,
            packetBody = packet.body.decodeToString(),
            retryCount = 0,
            nextRetryAt = now + DEFAULT_ACK_TIMEOUT_MS,
            createdAt = now
        )
    )
}
outboxChangeSignal.notifyChanged()
connection.send(packet)
```

Apply the same post-commit notification to:

- `sendText`
- `sendGroupText`
- `completeImageUploadAndQueueSend`
- `requeueImageMessageSend`

In `handleAck`, notify only when a pending row was actually removed:

```kotlin
messageDao.markAcked(messageId, serverSeq, serverTime)
if (pendingMessageDao.delete(messageId)) {
    outboxChangeSignal.notifyChanged()
}
notifyConversationChanged(conversationId)
```

Inside `retryDuePendingMessages`, track `pendingChanged` separately from UI/cache changes. Set it for every pending delete or retry upsert, then publish one revision after the batch:

```kotlin
if (pendingChanged) {
    outboxChangeSignal.notifyChanged()
}
```

- [ ] **Step 7: Run focused repository and signal tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.OutboxChangeSignalTest --tests com.buyansong.im.message.MessageRepositoryOutboxSignalTest --console=plain
```

Expected: all tests pass, including the post-commit ordering and ACK deletion cases.

- [ ] **Step 8: Commit the repository notification slice**

```powershell
git add app/src/main/java/com/buyansong/im/message/OutboxChangeSignal.kt app/src/main/java/com/buyansong/im/message/MessageRepository.kt app/src/test/java/com/buyansong/im/message/OutboxChangeSignalTest.kt app/src/test/java/com/buyansong/im/message/MessageRepositoryOutboxSignalTest.kt
git commit -m "feat: publish durable outbox changes"
```

---

### Task 3: Event-Driven Authenticated Outbox Scheduler

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/MessageOutboxWorker.kt`
- Create: `app/src/test/java/com/buyansong/im/message/MessageOutboxWorkerTest.kt`

**Interfaces:**
- Consumes: `MessageRepository.outboxRevisions`, `MessageRepository.earliestPendingRetryAt()`, `MessageRepository.retryDuePendingMessages(now)`, and `ImConnection.states`.
- Produces: an Outbox worker with no scan interval and an injectable `nowProvider: () -> Long` for deterministic scheduling tests.

- [ ] **Step 1: Write failing worker tests for exact deadlines and no polling**

Create a fixture with `StandardTestDispatcher`, `TestScope.backgroundScope`, `nowProvider = { testScheduler.currentTime }`, in-memory DAOs, and a fake connection. Add these tests:

```kotlin
@Test
fun authenticatedWorkerSleepsUntilTheExactRetryDeadlineWithoutPolling() = runTest {
    val fixture = Fixture(this)
    fixture.connection.state.value = ConnectionState.Authenticated
    fixture.worker.start()
    runCurrent()

    fixture.repository.sendText("u1", "u2", "hello", now = 0L)
    runCurrent()
    assertEquals(1, fixture.connection.sentPackets.size)

    advanceTimeBy(4_999L)
    runCurrent()
    assertEquals(1, fixture.connection.sentPackets.size)

    advanceTimeBy(1L)
    runCurrent()
    assertEquals(2, fixture.connection.sentPackets.size)
}

@Test
fun messageAckCancelsTheScheduledRetry() = runTest {
    val fixture = Fixture(this)
    fixture.connection.state.value = ConnectionState.Authenticated
    fixture.worker.start()
    val message = fixture.repository.sendText("u1", "u2", "hello", now = 0L)
    runCurrent()

    fixture.repository.handlePacket(ack(message.messageId, serverSeq = 1L))
    advanceTimeBy(60_000L)
    runCurrent()

    assertEquals(1, fixture.connection.sentPackets.size)
}
```

Add a `CountingPendingMessageDao` decorator and assert an empty authenticated Outbox performs no additional `dueMessages` or `earliestNextRetryAt` calls after advancing virtual time by 10 minutes.

- [ ] **Step 2: Write failing reconnect and process-recovery tests**

Add these state-transition cases:

```kotlin
@Test
fun disconnectedWorkerDoesNotRetryAndReauthenticationDrainsOverdueRows() = runTest {
    val fixture = Fixture(this)
    fixture.connection.state.value = ConnectionState.Authenticated
    fixture.worker.start()
    fixture.repository.sendText("u1", "u2", "hello", now = 0L)
    runCurrent()

    fixture.connection.state.value = ConnectionState.Reconnecting(1_000L, "network unavailable")
    advanceTimeBy(30_000L)
    runCurrent()
    assertEquals(1, fixture.connection.sentPackets.size)

    fixture.connection.state.value = ConnectionState.Authenticated
    runCurrent()
    assertEquals(2, fixture.connection.sentPackets.size)
}

@Test
fun authenticatedStartupRecoversAnOverduePersistedRowWithoutAnInMemorySignal() = runTest {
    val fixture = Fixture(this)
    fixture.persistOutgoingMessageAndPending(
        messageId = "persisted",
        nextRetryAt = 0L
    )

    fixture.worker.start()
    fixture.connection.state.value = ConnectionState.Authenticated
    runCurrent()

    assertEquals("persisted", fixture.connection.sentPackets.single().messageIdFromBody())
}
```

Use these concrete helpers in the fixture so the recovery row is valid—the repository intentionally deletes orphan pending rows that have no matching local message:

```kotlin
fun persistOutgoingMessageAndPending(messageId: String, nextRetryAt: Long) {
    val message = ChatMessage(
        messageId = messageId,
        conversationId = "single:u1:u2",
        senderId = "u1",
        receiverId = "u2",
        clientSeq = 1L,
        serverSeq = null,
        content = "persisted",
        status = MessageStatus.SENDING,
        direction = MessageDirection.OUTGOING,
        createdAt = 0L,
        updatedAt = 0L
    )
    messageDao.insertOrIgnore(message)
    pendingDao.upsert(
        PendingMessage(
            messageId = messageId,
            packetCmd = ImCommand.SEND_MESSAGE.value,
            packetBody = """{"messageId":"$messageId"}""",
            retryCount = 0,
            nextRetryAt = nextRetryAt,
            createdAt = 0L
        )
    )
}

private fun ack(messageId: String, serverSeq: Long) = ImPacket(
    cmd = ImCommand.MESSAGE_ACK.value,
    body = """{"messageId":"$messageId","serverSeq":$serverSeq,"serverTime":1000}""".toByteArray()
)

private fun ImPacket.messageIdFromBody(): String {
    return JsonParser.parseString(body.decodeToString()).asJsonObject
        .get("messageId")
        .asString
}
```

Implement the query-count decorator used by the idle test as follows:

```kotlin
private class CountingPendingMessageDao(
    private val delegate: PendingMessageDao = InMemoryPendingMessageDao()
) : PendingMessageDao by delegate {
    var dueQueryCount = 0
    var deadlineQueryCount = 0

    override fun dueMessages(now: Long, limit: Int): List<PendingMessage> {
        dueQueryCount += 1
        return delegate.dueMessages(now, limit)
    }

    override fun earliestNextRetryAt(): Long? {
        deadlineQueryCount += 1
        return delegate.earliestNextRetryAt()
    }
}
```

- [ ] **Step 3: Run the worker tests and verify the polling implementation fails expectations**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageOutboxWorkerTest --console=plain
```

Expected: tests fail because the existing worker wakes every `scanIntervalMillis` and has no `nowProvider` or revision wait.

- [ ] **Step 4: Replace the polling loop with revision/deadline suspension**

Refactor `MessageOutboxWorker` to this control flow:

```kotlin
class MessageOutboxWorker(
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            connection.states.collectLatest { state ->
                if (state == ConnectionState.Authenticated) {
                    runAuthenticatedLoop()
                }
            }
        }
    }

    private suspend fun runAuthenticatedLoop() {
        while (currentCoroutineContext().isActive) {
            val observedRevision = repository.outboxRevisions.value
            val now = nowProvider()
            repository.retryDuePendingMessages(now)
            val nextRetryAt = repository.earliestPendingRetryAt()

            if (connection.states.value != ConnectionState.Authenticated) return
            if (repository.outboxRevisions.value != observedRevision) continue
            if (nextRetryAt != null && nextRetryAt <= nowProvider()) continue

            if (nextRetryAt == null) {
                repository.outboxRevisions.first { it != observedRevision }
            } else {
                withTimeoutOrNull((nextRetryAt - nowProvider()).coerceAtLeast(1L)) {
                    repository.outboxRevisions.first { it != observedRevision }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
```

Add the required imports for `currentCoroutineContext`, `first`, and `withTimeoutOrNull`; remove `delay`, `scanIntervalMillis`, and `DEFAULT_SCAN_INTERVAL_MILLIS`.

The ordering `read revision -> query/process -> query deadline -> compare revision -> suspend` is mandatory. It closes the lost-wakeup window if a new pending row or ACK arrives between the database query and `first { ... }` collection.

- [ ] **Step 5: Run all Outbox-focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.PendingMessageDaoTest --tests com.buyansong.im.message.OutboxChangeSignalTest --tests com.buyansong.im.message.MessageRepositoryOutboxSignalTest --tests com.buyansong.im.message.MessageOutboxWorkerTest --console=plain
```

Expected: all tests pass under virtual time; the no-polling test reports stable DAO query counts while idle.

- [ ] **Step 6: Run the connection lifecycle regression test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.connection.ConnectionLifecycleManagerTest --console=plain
```

Expected: pass. No `ConnectionLifecycleManager` or heartbeat behavior changes are required.

- [ ] **Step 7: Commit the event-driven scheduler**

```powershell
git add app/src/main/java/com/buyansong/im/message/MessageOutboxWorker.kt app/src/test/java/com/buyansong/im/message/MessageOutboxWorkerTest.kt
git commit -m "refactor: make message outbox event driven"
```

---

### Task 4: Reliability Documentation and Full Verification

**Files:**
- Modify: `docs/status/B9-message-reliability.md`

**Interfaces:**
- Consumes: completed event-driven scheduling behavior from Tasks 1-3.
- Produces: durable documentation and verification evidence consistent with the implementation.

- [ ] **Step 1: Replace the polling description in B9 status documentation**

Replace “scans due `pending_messages` on a short loop” with the following semantics:

```markdown
- The Outbox is event-driven while the process is alive. It wakes when the connection becomes `Authenticated`, when a committed pending-row mutation advances the Outbox revision, or when the earliest persisted `nextRetryAt` deadline is reached.
- While disconnected or reconnecting, the Outbox performs no SQLite polling. Heartbeat/network handling remains in `ConnectionLifecycleManager`; successful re-authentication triggers an immediate overdue-row scan.
- SQLite remains the durable source of truth. After process restart, the first `Authenticated` state recovers pending rows even though in-memory wake revisions were lost.
```

Record that an exact retry deadline may still wake the process-local coroutine while authenticated; the optimization removes fixed 1-second polling, not required retry timers.

- [ ] **Step 2: Run the complete Android unit-test suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

Expected: all Android JVM tests pass.

- [ ] **Step 3: Build the debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run mock-server idempotency regressions**

Run from `mock-server`:

```powershell
mvn -q -Dtest=MessageRouterTest test
```

Expected: duplicate `messageId` sends return the original `MESSAGE_ACK`, do not allocate another `serverSeq`, and do not forward a duplicate receiver message.

- [ ] **Step 5: Perform a manual reconnect smoke test**

Use an emulator/device and the local mock server:

1. Authenticate and send one message with the server reachable; verify it becomes `SENT` and no retry occurs after 5 seconds.
2. Stop the server, send one message, and verify it remains persisted as `SENDING`.
3. Leave the app process alive for at least 10 seconds; verify logs show reconnect attempts but no once-per-second Outbox scans.
4. Restart the server; verify `Authenticated` immediately resends the overdue message with the original `messageId`.
5. Kill the app process after persisting an offline message, relaunch, restore the session, and verify the first `Authenticated` transition recovers it.

Do not use Android “Force stop” as the process-death test because the OS blocks the app until explicit user launch; this implementation does not add a system wake mechanism.

- [ ] **Step 6: Append actual verification results to the status document**

Add commands, date, and observed pass/fail counts to the verification table. Do not claim battery improvement solely from unit tests; record the architectural result precisely as “fixed 1-second polling removed.”

- [ ] **Step 7: Commit documentation and verification evidence**

```powershell
git add docs/status/B9-message-reliability.md
git commit -m "docs: describe event-driven outbox reliability"
```

---

## Final Acceptance Checklist

- [ ] An empty authenticated Outbox suspends indefinitely and performs no periodic SQLite reads.
- [ ] A newly committed pending row wakes the worker without waiting for a heartbeat.
- [ ] A healthy connection with a lost `MESSAGE_ACK` retries at the persisted 5s deadline.
- [ ] An ACK before the deadline removes the row and prevents the scheduled retry.
- [ ] A disconnected/reconnecting state cancels the deadline wait and performs no Outbox reads.
- [ ] Re-entering `Authenticated` immediately processes overdue rows.
- [ ] A process restart loses only in-memory revisions; SQLite pending rows recover after authentication.
- [ ] Retry packets preserve `messageId`, command, and body.
- [ ] Five retry attempts still transition the local message to `FAILED` and remove pending state.
- [ ] Existing heartbeat intervals and reconnect backoff remain unchanged.
- [ ] Full Android tests, APK build, and mock-server idempotency tests pass.
