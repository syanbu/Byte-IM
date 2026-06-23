# Received Thumbnail Conditional Prewarm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce receiver-side Coil decode and memory-cache churn by prewarming downloaded thumbnails only when the receiving conversation is currently active, while keeping background conversations ready for navigation-time prewarm.

**Architecture:** Move receiver thumbnail prewarm from an unconditional scheduler callback to a small policy-driven decision. `MessageRepository` already tracks `activeConversationId`; use that as the source of truth. `ThumbnailDownloadScheduler` keeps owning download/cache order, but receives a callback that can inspect `ChatMessage`, priority, local path, and an active-conversation budget before deciding whether to call Coil prewarm.

**Tech Stack:** Kotlin, Coroutines, Coil Compose 2.7.0, SQLite-backed chat models, JUnit4.

---

## Current Code Facts

- `MessageRepository` tracks the active chat in `activeConversationId`, set by `openConversationById(...)` and cleared by `closeConversation()`.
- Incoming image rows are hidden until `localThumbnailPath` is written because `ChatMessage.isReadyForChatDisplay()` returns false for incoming images without a local thumbnail.
- `ThumbnailDownloadScheduler` currently calls `prewarmLocalThumbnail(localPath)` unconditionally after `thumbnailCache.cacheThumbnail(...)` succeeds and before `onCached(...)`.
- `MainActivity` wires that callback as `ChatInitialImagePrewarmer.prewarmLocalThumbnail(context, localPath)`.
- Background conversations already work with navigation prewarm: opening a chat calls `messageRepository.preloadInitialPageSync(conversationId)`, then `ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)`, then navigates.
- `prewarmBeforeNavigation(...)` is bounded to 700 ms, 12 images, concurrency 3. `preloadInitialPageSync(...)` is bounded to 100 ms and caches 20 display-ready messages.

## Design Decision

Receiver-side prewarm should be conditional:

- If the thumbnail belongs to the currently active conversation, prewarm before `localThumbnailPath` is written.
- If the thumbnail belongs to a background conversation, skip Coil prewarm and write `localThumbnailPath` immediately.
- Active-conversation prewarm should have a small per-drain budget so a burst of received images cannot decode an unbounded number of bitmaps before UI emission.

This keeps the current-chat first-display behavior, avoids wasting Coil memory on background chats, and lets existing navigation prewarm handle later chat entry.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/buyansong/im/message/ReceivedThumbnailPrewarmPolicy.kt` | Create | Pure policy for active-conversation and per-drain budget decisions. |
| `app/src/test/java/com/buyansong/im/message/ReceivedThumbnailPrewarmPolicyTest.kt` | Create | Unit tests for active/background/null-active and budget behavior. |
| `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt` | Modify | Pass `ChatMessage`, priority, and local path to the prewarm callback; enforce per-worker budget. |
| `app/src/test/java/com/buyansong/im/message/ThumbnailDownloadSchedulerTest.kt` | Create | Verify active messages prewarm before `onCached`, background messages skip prewarm, and active prewarm budget is capped. |
| `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` | Modify | Provide active-conversation-aware prewarm decision to the scheduler default construction path. |
| `app/src/main/java/com/buyansong/im/MainActivity.kt` | Modify | Update scheduler wiring to the new callback shape. |
| `docs/status/B11-image-message-design-status.md` | Modify | Document conditional receiver prewarm and background conversation handoff to navigation prewarm. |

## Non-Goals

- Do not remove sender-side hot-send prewarm.
- Do not remove navigation-time `prewarmBeforeNavigation(...)`.
- Do not change idle viewport prefetch.
- Do not change `ChatLocalThumbnailRequest.size(Size.ORIGINAL)`.
- Do not tune Coil global memory cache.

---

## Task 1: Add Received Thumbnail Prewarm Policy

**Files:**
- Create: `app/src/main/java/com/buyansong/im/message/ReceivedThumbnailPrewarmPolicy.kt`
- Create: `app/src/test/java/com/buyansong/im/message/ReceivedThumbnailPrewarmPolicyTest.kt`

- [ ] **Step 1: Write failing policy tests**

Create `app/src/test/java/com/buyansong/im/message/ReceivedThumbnailPrewarmPolicyTest.kt`:

```kotlin
package com.buyansong.im.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivedThumbnailPrewarmPolicyTest {

    @Test
    fun shouldPrewarmReturnsTrueForActiveConversationWithinBudget() {
        assertEquals(
            true,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = "single:u_a:u_b",
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 5
            )
        )
    }

    @Test
    fun shouldPrewarmReturnsFalseForBackgroundConversation() {
        assertEquals(
            false,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = "single:u_b:u_c",
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 5
            )
        )
    }

    @Test
    fun shouldPrewarmReturnsFalseWhenNoConversationIsActive() {
        assertEquals(
            false,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = null,
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 5
            )
        )
    }

    @Test
    fun shouldPrewarmReturnsFalseWhenBudgetIsExhausted() {
        assertEquals(
            false,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = "single:u_a:u_b",
                alreadyPrewarmedInDrain = 5,
                maxPrewarmPerDrain = 5
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.ReceivedThumbnailPrewarmPolicyTest
```

Expected: FAIL because `ReceivedThumbnailPrewarmPolicy` does not exist.

- [ ] **Step 3: Implement the policy**

Create `app/src/main/java/com/buyansong/im/message/ReceivedThumbnailPrewarmPolicy.kt`:

```kotlin
package com.buyansong.im.message

object ReceivedThumbnailPrewarmPolicy {
    fun shouldPrewarm(
        messageConversationId: String,
        activeConversationId: String?,
        alreadyPrewarmedInDrain: Int,
        maxPrewarmPerDrain: Int
    ): Boolean {
        if (activeConversationId == null) {
            return false
        }
        if (messageConversationId != activeConversationId) {
            return false
        }
        return alreadyPrewarmedInDrain < maxPrewarmPerDrain.coerceAtLeast(0)
    }
}
```

- [ ] **Step 4: Run policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.ReceivedThumbnailPrewarmPolicyTest
```

Expected: PASS.

---

## Task 2: Update Thumbnail Scheduler Callback Shape and Budget

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt`
- Create: `app/src/test/java/com/buyansong/im/message/ThumbnailDownloadSchedulerTest.kt`

- [ ] **Step 1: Write failing scheduler tests**

Create `app/src/test/java/com/buyansong/im/message/ThumbnailDownloadSchedulerTest.kt`:

```kotlin
package com.buyansong.im.message

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailDownloadSchedulerTest {

    private class FakeThumbnailCache : ChatThumbnailCache {
        override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String {
            return "/cache/$messageId.jpg"
        }
    }

    @Test
    fun immediateSchedulerPrewarmsBeforeOnCachedWhenCallbackChoosesToPrewarm() {
        val events = mutableListOf<String>()
        val scheduler = ImmediateThumbnailDownloadScheduler(
            thumbnailCache = FakeThumbnailCache(),
            prewarmLocalThumbnail = { message, _, localPath ->
                events += "prewarm:${message.messageId}:$localPath"
                true
            }
        )

        scheduler.enqueue(message("m1"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }

        assertEquals(
            listOf("prewarm:m1:/cache/m1.jpg", "cached:m1:/cache/m1.jpg"),
            events
        )
    }

    @Test
    fun immediateSchedulerSkipsPrewarmWhenCallbackReturnsFalse() {
        val events = mutableListOf<String>()
        val scheduler = ImmediateThumbnailDownloadScheduler(
            thumbnailCache = FakeThumbnailCache(),
            prewarmLocalThumbnail = { _, _, _ -> false }
        )

        scheduler.enqueue(message("m1"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }

        assertEquals(listOf("cached:m1:/cache/m1.jpg"), events)
    }

    @Test
    fun coroutineSchedulerCapsPrewarmPerDrain() = runBlocking {
        val events = mutableListOf<String>()
        val scopeJob = Job()
        val scheduler = CoroutineThumbnailDownloadScheduler(
            thumbnailCache = FakeThumbnailCache(),
            scope = CoroutineScope(scopeJob + Dispatchers.Unconfined),
            maxPrewarmPerDrain = 1,
            prewarmLocalThumbnail = { message, _, localPath ->
                events += "prewarm:${message.messageId}:$localPath"
                true
            }
        )

        scheduler.enqueue(message("m1"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }
        scheduler.enqueue(message("m2"), ThumbnailDownloadPriority.NORMAL) { messageId, localPath ->
            events += "cached:$messageId:$localPath"
        }

        assertEquals(
            listOf(
                "prewarm:m1:/cache/m1.jpg",
                "cached:m1:/cache/m1.jpg",
                "cached:m2:/cache/m2.jpg"
            ),
            events
        )
        scopeJob.cancel()
    }

    private fun message(id: String): ChatMessage {
        return ChatMessage(
            messageId = id,
            conversationId = "single:u_a:u_b",
            senderId = "u_a",
            receiverId = "u_b",
            clientSeq = 1L,
            serverSeq = 1L,
            content = "[图片]",
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = 1L,
            updatedAt = 1L,
            type = MessageType.IMAGE,
            thumbnailUrl = "https://example.test/$id-thumb.jpg"
        )
    }
}
```

- [ ] **Step 2: Run scheduler tests and verify failure**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.ThumbnailDownloadSchedulerTest
```

Expected: FAIL because the scheduler callback currently has shape `suspend (String) -> Unit` and has no `maxPrewarmPerDrain`.

- [ ] **Step 3: Update scheduler constructor and drain logic**

In `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt`, change both scheduler constructors to:

```kotlin
private val prewarmLocalThumbnail: suspend (
    message: ChatMessage,
    priority: ThumbnailDownloadPriority,
    localThumbnailPath: String
) -> Boolean = { _, _, _ -> false }
```

For `CoroutineThumbnailDownloadScheduler`, also add:

```kotlin
private val maxPrewarmPerDrain: Int = 5
```

Update `PendingThumbnailDownload` to store:

```kotlin
val message: ChatMessage
```

instead of only `messageId`.

Update enqueue to store the whole `message`:

```kotlin
message = message,
thumbnailUrl = thumbnailUrl,
```

Update immediate scheduler order to:

```kotlin
val localPath = thumbnailCache.cacheThumbnail(message.messageId, thumbnailUrl) ?: return false
kotlinx.coroutines.runBlocking {
    prewarmLocalThumbnail(message, priority, localPath)
}
onCached(message.messageId, localPath)
return true
```

Update coroutine `drainQueue()` to track budget:

```kotlin
private suspend fun drainQueue() {
    var prewarmedInDrain = 0
    while (true) {
        val request = synchronized(lock) {
            val next = pendingRequests
                .minWithOrNull(
                    compareBy<PendingThumbnailDownload> { it.priority.rank }
                        .thenBy { it.sequence }
                )
            if (next == null) {
                workerRunning = false
                return
            }
            pendingRequests.remove(next)
            next
        }
        val localPath = thumbnailCache.cacheThumbnail(
            request.message.messageId,
            request.thumbnailUrl
        ) ?: continue
        if (prewarmedInDrain < maxPrewarmPerDrain.coerceAtLeast(0)) {
            val didPrewarm = prewarmLocalThumbnail(request.message, request.priority, localPath)
            if (didPrewarm) {
                prewarmedInDrain += 1
            }
        }
        request.onCached(request.message.messageId, localPath)
    }
}
```

- [ ] **Step 4: Run scheduler tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.ThumbnailDownloadSchedulerTest
```

Expected: PASS.

---

## Task 3: Wire Active-Conversation-Aware Prewarm

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`
- Modify existing tests or add focused tests in `app/src/test/java/com/buyansong/im/message/MessageRepositoryCacheTest.kt`

- [ ] **Step 1: Add focused repository tests for active/background decision**

In `MessageRepositoryCacheTest`, add a scheduler fake:

```kotlin
private class CapturingThumbnailScheduler : ThumbnailDownloadScheduler {
    val enqueued = mutableListOf<ChatMessage>()
    var onCached: ((String, String) -> Unit)? = null

    override fun enqueue(
        message: ChatMessage,
        priority: ThumbnailDownloadPriority,
        onCached: (messageId: String, localThumbnailPath: String) -> Unit
    ): Boolean {
        enqueued += message
        this.onCached = onCached
        return true
    }
}
```

Add a repository helper that accepts a scheduler:

```kotlin
private fun repository(
    messageDao: CountingMessageDao,
    thumbnailDownloadScheduler: ThumbnailDownloadScheduler
): MessageRepository {
    return MessageRepository(
        messageDao = messageDao,
        conversationDao = InMemoryConversationDao(),
        pendingMessageDao = InMemoryPendingMessageDao(),
        connection = FakeConnection(),
        messageIdGenerator = MessageIdGenerator(),
        seqGenerator = SeqGenerator(),
        thumbnailDownloadScheduler = thumbnailDownloadScheduler
    )
}
```

Add tests:

```kotlin
@Test
fun incomingImageThumbnailCallbackUpdatesLocalPathForBackgroundConversation() {
    val messageDao = CountingMessageDao()
    val scheduler = CapturingThumbnailScheduler()
    val repository = repository(messageDao, scheduler)

    repository.handlePacket(imagePacket(messageId = "img1", senderId = "u_a", receiverId = "u_b"))
    scheduler.onCached?.invoke("img1", "/cache/img1.jpg")

    assertEquals("/cache/img1.jpg", messageDao.findByMessageId("img1")?.localThumbnailPath)
}
```

Also add:

```kotlin
private fun imagePacket(messageId: String, senderId: String, receiverId: String): ImPacket {
    return ImPacket(
        cmd = ImCommand.RECEIVE_MESSAGE.value,
        body = """
            {
              "messageId":"$messageId",
              "senderId":"$senderId",
              "receiverId":"$receiverId",
              "clientSeq":1,
              "serverSeq":1,
              "content":"[图片]",
              "timestamp":1,
              "type":"IMAGE",
              "image":{
                "imageUrl":"https://example.test/$messageId-original.jpg",
                "thumbnailUrl":"https://example.test/$messageId-thumb.jpg",
                "width":640,
                "height":480,
                "mimeType":"image/jpeg",
                "fileSizeBytes":1234
              }
            }
        """.trimIndent().toByteArray()
    )
}
```

- [ ] **Step 2: Run repository cache tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryCacheTest
```

Expected: PASS if the helper compiles; these tests protect callback update behavior while scheduler prewarm logic changes.

- [ ] **Step 3: Add repository prewarm decision API**

In `MessageRepository.kt`, add:

```kotlin
fun shouldPrewarmReceivedThumbnail(message: ChatMessage, alreadyPrewarmedInDrain: Int, maxPrewarmPerDrain: Int): Boolean {
    return ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
        messageConversationId = message.conversationId,
        activeConversationId = activeConversationId,
        alreadyPrewarmedInDrain = alreadyPrewarmedInDrain,
        maxPrewarmPerDrain = maxPrewarmPerDrain
    )
}
```

Keep this method package-visible/public so `MainActivity` can wire the scheduler callback without reflection or extra state holders.

- [ ] **Step 4: Wire `MainActivity` callback to check active conversation**

Change `CoroutineThumbnailDownloadScheduler` construction in `AccountScopedRepositories.create(...)` to:

```kotlin
thumbnailDownloadScheduler = CoroutineThumbnailDownloadScheduler(
    thumbnailCache = thumbnailCache,
    scope = thumbnailDownloadScope,
    maxPrewarmPerDrain = 5,
    prewarmLocalThumbnail = { message, _, localPath ->
        if (!messageRepository.shouldPrewarmReceivedThumbnail(
                message = message,
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 1
            )
        ) {
            false
        } else {
            ChatInitialImagePrewarmer.prewarmLocalThumbnail(context, localPath)
        }
    }
)
```

Then simplify this wiring if Task 2 chooses to pass `alreadyPrewarmedInDrain` into the callback directly. The final callback should make exactly one decision: active conversation plus budget means prewarm; background conversation means return false.

- [ ] **Step 5: Reconcile callback budget shape**

If Task 2 keeps budget internal to the scheduler, update the callback signature to include:

```kotlin
alreadyPrewarmedInDrain: Int,
maxPrewarmPerDrain: Int
```

and wire:

```kotlin
prewarmLocalThumbnail = { message, _, localPath, alreadyPrewarmedInDrain, maxPrewarmPerDrain ->
    if (!messageRepository.shouldPrewarmReceivedThumbnail(message, alreadyPrewarmedInDrain, maxPrewarmPerDrain)) {
        false
    } else {
        ChatInitialImagePrewarmer.prewarmLocalThumbnail(context, localPath)
    }
}
```

Use one callback shape consistently across `ImmediateThumbnailDownloadScheduler`, `CoroutineThumbnailDownloadScheduler`, tests, and `MainActivity`.

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.ReceivedThumbnailPrewarmPolicyTest --tests com.buyansong.im.message.ThumbnailDownloadSchedulerTest --tests com.buyansong.im.message.MessageRepositoryCacheTest
```

Expected: PASS.

---

## Task 4: Update Documentation

**Files:**
- Modify: `docs/status/B11-image-message-design-status.md`

- [ ] **Step 1: Update receiver-side preload strategy**

In `Thumbnail preload strategy:`, replace:

```markdown
- Receiver-side downloaded thumbnails are prewarmed after `ThumbnailDownloadScheduler` caches the file and before `localThumbnailPath` is written to the message row.
```

with:

```markdown
- Receiver-side downloaded thumbnails are prewarmed after cache write and before `localThumbnailPath` emission only for the currently active conversation; background conversations write `localThumbnailPath` without Coil prewarm and rely on navigation-time prewarm when opened.
```

- [ ] **Step 2: Update risks**

Add under `Current Risks`:

```markdown
- Background-conversation image receive no longer spends Coil memory immediately; opening that conversation depends on bounded navigation prewarm plus idle viewport prefetch. Device testing should confirm the 700 ms navigation prewarm cap is still enough for common recent-image conversations.
```

---

## Final Verification

- [ ] **Step 1: Run focused unit tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.ReceivedThumbnailPrewarmPolicyTest --tests com.buyansong.im.message.ThumbnailDownloadSchedulerTest --tests com.buyansong.im.message.MessageRepositoryCacheTest
```

Expected: PASS.

- [ ] **Step 2: Run full debug unit tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Manual emulator/device checks**

1. Open A-B chat on device B, receive an image from A. Expected: incoming image appears after thumbnail cache completion with no obvious first-display gray flash.
2. Keep B viewing C-B chat, receive an image from A. Expected: A-B conversation updates, local thumbnail path is persisted, but no immediate Coil prewarm is performed for A-B.
3. Open A-B after background receipt. Expected: navigation waits on bounded prewarm, then recent image thumbnails display without obvious gray flash for the initial page.
4. Receive 20 images into the currently active chat. Expected: only the configured active-conversation prewarm budget runs immediately; remaining images still become displayable and can be warmed by idle viewport prefetch or normal `AsyncImage` loading.

## Self-Review Notes

- This plan intentionally preserves current-chat first-display quality while reducing background-conversation memory churn.
- Navigation prewarm and idle viewport prefetch are the fallback path for background conversations.
- The exact callback shape must be consistent; if the implementer chooses the budget-in-callback version, update all test snippets in Task 2 and Task 3 together.
