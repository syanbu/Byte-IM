# Chat Entry Preload and Thumbnail Warmup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the existing 20-message Repository LRU for fast chat entry, remove main-thread blocking from metadata preload, and make Coil entry warmup bounded, dynamically concurrent, and consistent with chat-bubble decoding.

**Architecture:** SQLite remains the message source of truth, `MessageRepository` caches only the latest 20 display-ready `ChatMessage` metadata records for up to 10 conversations, and `ChatViewModel` continues to load older 20-message pages from SQLite when the user approaches the history boundary. Entry warmup receives only that initial 20-message page, submits at most six local-thumbnail requests to the singleton Coil `ImageLoader`, keeps at most three requests active with dynamic slot refill, and navigates as soon as all selected requests complete or after a 300 ms image-warmup budget. Local thumbnail bytes remain owned by `ChatThumbnailCache`; Coil owns decoded Bitmap memory.

**Tech Stack:** Kotlin 2.0.21, Android/Jetpack Compose, Kotlin coroutines 1.9.0, Coil 2.7.0, SQLite, JUnit 4

## Global Constraints

- Keep `messages` as the only durable source of message content.
- Keep `INITIAL_PAGE_SIZE = 20`.
- Keep `INITIAL_PAGE_CACHE_SIZE = 10`.
- Do not add a 40-message Repository history window or second-page LRU.
- Do not duplicate message bodies in `conversations`.
- Keep automatic older-history loading at 20 messages and preserve `LOAD_EARLIER_THRESHOLD_ITEMS = 10`.
- Keep the message metadata preload waiting budget at `100L` milliseconds.
- Treat the 100 ms metadata timeout as a coroutine waiting budget, not proof that a synchronous SQLite call is physically interrupted at exactly 100 ms.
- Set the entry image-warmup budget to `300L` milliseconds.
- Select at most `6` distinct local thumbnails for entry warmup.
- Keep at most `3` Coil requests active concurrently and refill an available slot immediately; do not wait for a fixed batch of three to finish.
- A Coil memory-cache hit must complete through the normal identical request path; do not add a second Bitmap cache or mirror Coil hit state in `MessageRepository`.
- Use the same `ChatLocalThumbnailRequest` for navigation warmup, active-receive warmup, viewport prefetch, sender warmup, and `ChatImageBubble`.
- Decode local chat thumbnails at the maximum bubble bounds, `220dp × 270dp`, converted to physical pixels.
- Remove the local request's explicit Coil `diskCacheKey`; `ChatThumbnailCache` already owns the local thumbnail file, while Coil's memory cache owns the decoded Bitmap.
- Preserve current entry order: metadata preload, image warmup, navigation.
- Preserve active-conversation receive policy: background conversations do not prewarm Coil; the active conversation may prewarm at most five received thumbnails per scheduler drain.
- Preserve idle viewport prefetch: 160 ms idle delay, four-item margin, at most 10 images, 250 ms timeout, concurrency one.
- Preserve sender-side behavior: generated local thumbnails are warmed before outgoing image rows enter chat state.
- Add no dependencies and make no database schema or index changes.
- Do not implement stale `cacheDir` file-path recovery in this plan. Record it as a separate follow-up because it changes persistence/retry behavior.

---

## Cache Ownership Contract

| Owner | Cached value | Capacity/lifetime | Eviction consequence |
|---|---|---|---|
| SQLite `messages` | Message metadata, URLs, local paths | Durable per account | Source of truth remains available |
| `MessageRepository.initialPageCache` | Latest 20 display-ready `ChatMessage` records | 10-conversation process LRU | Next entry queries SQLite |
| `ChatThumbnailCache` | Encoded local thumbnail files | Android `cacheDir` | A future recovery task must redownload a missing file |
| Coil singleton memory cache | Decoded Bitmap objects | Coil-managed memory LRU | Next identical request decodes the local file again |

Repository eviction must not clear Coil. Coil eviction must not invalidate Repository. The only bridge between them is `ChatMessage.localThumbnailPath`, passed through the shared `ChatLocalThumbnailRequest`.

## Entry Timing Contract

```text
click conversation
    |
    +-- metadata LRU hit --------------------------+
    |                                              |
    +-- metadata LRU miss -> SQLite, <=100ms ------+
                                                   |
                                                   v
                                      latest 20 message metadata
                                                   |
                                      select <=6 local thumbnails
                                                   |
                                      Coil concurrency <=3
                                      dynamic slot refill
                                                   |
                           all requests finish OR 300ms budget expires
                                                   |
                                                   v
                                               navigate
```

- Six Coil memory hits navigate after the six requests return; there is no fixed 300 ms sleep.
- If four requests hit and two require decoding, navigation waits only for those two decodes, unless the 300 ms budget expires first.
- On timeout, coroutine cancellation stops the entry warmup attempt and navigation continues. `ChatImageBubble` and idle viewport prefetch remain the fallback.
- The nominal sequential waiting budget is 100 ms for metadata plus 300 ms for images. Device measurements, not assumptions, should drive any later tuning.

## File Map

- Modify `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`: replace blocking initial-page preload with a suspending API while retaining the current 20-message/10-conversation LRU.
- Modify `app/src/main/java/com/buyansong/im/MainActivity.kt`: await the suspending metadata preload, then bounded image warmup, then navigate.
- Modify `app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt`: use an explicit local `File`, bubble-bound decode size, one memory-cache key, and no local Coil disk-cache key.
- Modify `app/src/main/java/com/buyansong/im/chat/ChatImageBubbleLayoutPolicy.kt`: expose a pure maximum decode-size calculation shared by all local-thumbnail requests.
- Modify `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt`: set 6/300 ms/3 defaults and replace fixed batches with dynamically refilled concurrency.
- Modify `app/src/test/java/com/buyansong/im/message/MessageRepositoryCacheTest.kt`: cover suspending preload, retained 20-row query, cache hit, invalidation, and LRU eviction.
- Modify `app/src/test/java/com/buyansong/im/chat/ChatViewModelInitialCacheTest.kt`: use the suspending preload and prove ViewModel hydration stays at 20 messages.
- Create `app/src/test/java/com/buyansong/im/chat/ChatImageBubbleLayoutPolicyTest.kt`: cover bubble-bound pixel conversion.
- Modify `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt`: cover six-image selection, concurrency limit, and dynamic slot refill.
- Modify `app/src/test/java/com/buyansong/im/chat/ChatLocalThumbnailRequestTest.kt`: retain path normalization coverage.
- Modify `docs/status/B2-single-chat.md`: record suspending 20-message entry preload.
- Modify `docs/status/B11-image-message-design-status.md`: define cache ownership and the 6/300 ms/3 dynamic entry-warmup contract.
- Modify `docs/bug/Fix-ChatImageGrayPlaceholderOnChatEntry.md`: add a historical/superseded banner without rewriting the old implementation record.
- Modify `docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md`: add a historical/superseded banner without rewriting the old implementation plan.

### Task 1: Keep the 20-message LRU and make metadata preload suspending

**Files:**

- Modify: `app/src/test/java/com/buyansong/im/message/MessageRepositoryCacheTest.kt:1-225`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatViewModelInitialCacheTest.kt:105-215`
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt:25-37,427-466,1050-1068,1190-1200`
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt:497-505`

**Interfaces:**

- Produces: `suspend fun MessageRepository.preloadInitialPage(conversationId: String): List<ChatMessage>`.
- Preserves: `fun MessageRepository.getCachedInitialPage(conversationId: String): List<ChatMessage>?`.
- Preserves: `fun MessageRepository.preloadInitialPageAsync(conversationId: String, scope: CoroutineScope)`.
- Removes: `fun MessageRepository.preloadInitialPageSync(conversationId: String): List<ChatMessage>`.

- [ ] **Step 1: Extend the counting DAO to record query limits**

In `MessageRepositoryCacheTest.CountingMessageDao`, add:

```kotlin
val queryPageLimits = mutableListOf<Int>()
```

Update its `queryPage` implementation:

```kotlin
override fun queryPage(
    conversationId: String,
    beforeTime: Long?,
    limit: Int
): List<ChatMessage> {
    queryPageCount += 1
    queryPageLimits += limit
    return delegate.queryPage(conversationId, beforeTime, limit)
}
```

Add this test helper:

```kotlin
private fun insertMessages(
    messageDao: CountingMessageDao,
    count: Int,
    conversationId: String = "single:u_a:u_b"
) {
    (1..count).forEach { index ->
        messageDao.insertOrIgnore(
            message(
                id = "m$index",
                conversationId = conversationId,
                createdAt = index.toLong()
            )
        )
    }
}
```

- [ ] **Step 2: Replace the blocking preload test with a failing suspend-API test**

Add:

```kotlin
import kotlinx.coroutines.runBlocking
```

Replace `getCachedInitialPage_returnsCachedPageWithoutQueryingAgain` with:

```kotlin
@Test
fun preloadInitialPage_queriesTwentyOnceAndReturnsCachedInitialPage() = runBlocking {
    val messageDao = CountingMessageDao()
    val repository = repository(messageDao)
    insertMessages(messageDao, count = 25)

    val first = repository.preloadInitialPage("single:u_a:u_b")
    val second = repository.preloadInitialPage("single:u_a:u_b")
    val cached = repository.getCachedInitialPage("single:u_a:u_b")

    assertEquals((25 downTo 6).map { "m$it" }, first.map { it.messageId })
    assertEquals(first, second)
    assertEquals(first, cached)
    assertEquals(1, messageDao.queryPageCount)
    assertEquals(listOf(20), messageDao.queryPageLimits)
}
```

- [ ] **Step 3: Run the focused test and confirm the new suspend API is missing**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.message.MessageRepositoryCacheTest.preloadInitialPage_queriesTwentyOnceAndReturnsCachedInitialPage \
  --console=plain
```

Expected: compilation fails with an unresolved reference to `preloadInitialPage`.

- [ ] **Step 4: Replace production `runBlocking` with a suspending preload**

In `MessageRepository.kt`, remove:

```kotlin
import kotlinx.coroutines.runBlocking
```

Replace `preloadInitialPageSync` with:

```kotlin
suspend fun preloadInitialPage(conversationId: String): List<ChatMessage> {
    initialPageCache[conversationId]?.let { return it }
    return withTimeoutOrNull(INITIAL_PAGE_PRELOAD_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            initialPageCache[conversationId]
                ?: loadInitialPageFromDao(conversationId).also { messages ->
                    initialPageCache[conversationId] = messages
                }
        }
    } ?: emptyList()
}
```

Rename the timeout constant without changing its value:

```kotlin
const val INITIAL_PAGE_PRELOAD_TIMEOUT_MS = 100L
```

Keep these constants unchanged:

```kotlin
const val INITIAL_PAGE_SIZE = 20
const val INITIAL_PAGE_CACHE_SIZE = 10
```

Do not add a 40-row query or cache value.

- [ ] **Step 5: Await the suspending API before image warmup and navigation**

In `MainActivity.kt`, keep the existing `uiScope.launch` and change only the repository call:

```kotlin
uiScope.launch {
    val messages = messageRepository.preloadInitialPage(conversationId)
    ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)
    SelfHostedImRoute.Chat.createRoute(conversationId)
        ?.let(navController::navigateToChat)
}
```

Do not launch either preload as an un-awaited child coroutine. The parent UI coroutine must suspend without blocking the Android Looper.

- [ ] **Step 6: Migrate ViewModel cache-hydration tests**

In `ChatViewModelInitialCacheTest.kt`, add:

```kotlin
import kotlinx.coroutines.runBlocking
```

Change `constructor_populatesMessagesFromCachedInitialPageBeforeStart` to `runBlocking`, and replace its preload call with:

```kotlin
repository.preloadInitialPage("single:u_a:u_b")
```

Change `constructor_usesCachedGroupSenderProfilesBeforeNetworkRefresh` to `runBlocking`, and replace its preload call with:

```kotlin
repository.preloadInitialPage("group:g1")
```

For `constructor_populatesMessagesFromCachedInitialPageBeforeStart`, replace its two-message seed with:

```kotlin
(1L..25L).forEach { createdAt ->
    messageDao.insertOrIgnore(
        message(
            id = "m$createdAt",
            createdAt = createdAt
        )
    )
}
```

Replace its message assertion with:

```kotlin
assertEquals(20, viewModel.state.value.messages.size)
assertEquals(
    (6L..25L).map { "m$it" },
    viewModel.state.value.messages.map { it.messageId }
)
```

- [ ] **Step 7: Verify production blocking calls and 40-row symbols are absent**

Run:

```bash
rg -n "preloadInitialPageSync|INITIAL_PAGE_SYNC_TIMEOUT_MS|CACHED_HISTORY_WINDOW_SIZE|HISTORY_WINDOW_CACHE_SIZE|runBlocking" \
  app/src/main/java/com/buyansong/im/message/MessageRepository.kt \
  app/src/main/java/com/buyansong/im/MainActivity.kt
```

Expected: no output.

Run:

```bash
rg -n "INITIAL_PAGE_SIZE = 20|INITIAL_PAGE_CACHE_SIZE = 10|INITIAL_PAGE_PRELOAD_TIMEOUT_MS = 100L" \
  app/src/main/java/com/buyansong/im/message/MessageRepository.kt
```

Expected: all three constants are present.

- [ ] **Step 8: Run repository and ViewModel cache tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.message.MessageRepositoryCacheTest \
  --tests com.buyansong.im.chat.ChatViewModelInitialCacheTest \
  --console=plain
```

Expected: both classes pass; the DAO query limit remains 20 and a cache hit avoids a second query.

- [ ] **Step 9: Commit the suspending 20-message preload**

```bash
git add \
  app/src/main/java/com/buyansong/im/message/MessageRepository.kt \
  app/src/main/java/com/buyansong/im/MainActivity.kt \
  app/src/test/java/com/buyansong/im/message/MessageRepositoryCacheTest.kt \
  app/src/test/java/com/buyansong/im/chat/ChatViewModelInitialCacheTest.kt
git commit -m "perf: suspend chat initial metadata preload"
```

### Task 2: Make the shared local-thumbnail request match bubble decode bounds

**Files:**

- Create: `app/src/test/java/com/buyansong/im/chat/ChatImageBubbleLayoutPolicyTest.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatLocalThumbnailRequestTest.kt:1-30`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatImageBubbleLayoutPolicy.kt:1-35`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt:1-22`

**Interfaces:**

- Produces: `data class ChatImageDecodeSize(val widthPx: Int, val heightPx: Int)`.
- Produces: `fun ChatImageBubbleLayoutPolicy.decodeSizePx(density: Float): ChatImageDecodeSize`.
- Preserves: `fun ChatLocalThumbnailRequest.build(context: Context, localThumbnailPath: String): ImageRequest?`.

- [ ] **Step 1: Add failing pure decode-size tests**

Create `ChatImageBubbleLayoutPolicyTest.kt`:

```kotlin
package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageBubbleLayoutPolicyTest {

    @Test
    fun decodeSizePxUsesMaximumBubbleBoundsAtDeviceDensity() {
        assertEquals(
            ChatImageDecodeSize(widthPx = 440, heightPx = 540),
            ChatImageBubbleLayoutPolicy.decodeSizePx(density = 2f)
        )
    }

    @Test
    fun decodeSizePxFallsBackToOneXForInvalidDensity() {
        assertEquals(
            ChatImageDecodeSize(widthPx = 220, heightPx = 270),
            ChatImageBubbleLayoutPolicy.decodeSizePx(density = 0f)
        )
    }
}
```

- [ ] **Step 2: Run the new tests and confirm the API is missing**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.chat.ChatImageBubbleLayoutPolicyTest \
  --console=plain
```

Expected: compilation fails because `ChatImageDecodeSize` and `decodeSizePx` do not exist.

- [ ] **Step 3: Centralize bubble bounds and add pixel conversion**

In `ChatImageBubbleLayoutPolicy.kt`, add:

```kotlin
data class ChatImageDecodeSize(
    val widthPx: Int,
    val heightPx: Int
)
```

Rename the private maximum constants:

```kotlin
internal const val MAX_WIDTH_DP = 220
internal const val MAX_HEIGHT_DP = 270
```

Update `displaySize()` to use `MAX_WIDTH_DP` and `MAX_HEIGHT_DP`, then add:

```kotlin
fun decodeSizePx(density: Float): ChatImageDecodeSize {
    val safeDensity = density.takeIf { it > 0f } ?: 1f
    return ChatImageDecodeSize(
        widthPx = (MAX_WIDTH_DP * safeDensity).roundToInt().coerceAtLeast(1),
        heightPx = (MAX_HEIGHT_DP * safeDensity).roundToInt().coerceAtLeast(1)
    )
}
```

- [ ] **Step 4: Make local-file and Bitmap-cache ownership explicit**

In `ChatLocalThumbnailRequest.kt`, replace the imports with:

```kotlin
import android.content.Context
import coil.request.ImageRequest
import java.io.File
```

Replace `build()` with:

```kotlin
fun build(context: Context, localThumbnailPath: String): ImageRequest? {
    val key = cacheKey(localThumbnailPath) ?: return null
    val decodeSize = ChatImageBubbleLayoutPolicy.decodeSizePx(
        context.resources.displayMetrics.density
    )
    return ImageRequest.Builder(context)
        .data(File(key))
        .memoryCacheKey(key)
        .size(decodeSize.widthPx, decodeSize.heightPx)
        .build()
}
```

This deliberately removes `diskCacheKey(key)` and `Size.ORIGINAL`. The file under `cacheDir` is the encoded disk cache; Coil should cache only the decoded, bubble-sized Bitmap for this local request.

- [ ] **Step 5: Preserve cache-key normalization tests**

Keep both existing `ChatLocalThumbnailRequestTest` cases unchanged:

```kotlin
@Test
fun cacheKeyReturnsTrimmedLocalThumbnailPath()

@Test
fun cacheKeyReturnsNullForBlankPath()
```

- [ ] **Step 6: Run request and layout policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.chat.ChatImageBubbleLayoutPolicyTest \
  --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest \
  --console=plain
```

Expected: both classes pass.

- [ ] **Step 7: Compile the Android source to validate the Coil 2.7 request API**

Run:

```bash
./gradlew :app:compileDebugKotlin --console=plain
```

Expected: `BUILD SUCCESSFUL`; no `coil.size.Size` import remains in `ChatLocalThumbnailRequest.kt`.

- [ ] **Step 8: Commit the shared request contract**

```bash
git add \
  app/src/main/java/com/buyansong/im/chat/ChatImageBubbleLayoutPolicy.kt \
  app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt \
  app/src/test/java/com/buyansong/im/chat/ChatImageBubbleLayoutPolicyTest.kt \
  app/src/test/java/com/buyansong/im/chat/ChatLocalThumbnailRequestTest.kt
git commit -m "perf: decode chat thumbnails at bubble bounds"
```

### Task 3: Replace fixed warmup batches with dynamic concurrency

**Files:**

- Modify: `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt:1-180`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt:8-22,76-146`

**Interfaces:**

- Produces: `internal suspend fun <T> forEachWithConcurrency(items: List<T>, maxConcurrency: Int, operation: suspend (T) -> Unit)`.
- Preserves: `suspend fun prewarmBeforeNavigation(context: Context, messages: List<ChatMessage>)`.
- Preserves: `suspend fun prewarmLocalThumbnails(context: Context, localThumbnailPaths: List<String>, timeoutMs: Long, maxConcurrency: Int)`.

- [ ] **Step 1: Add imports for coroutine scheduling tests**

In `ChatInitialImagePrewarmerTest.kt`, add:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
```

- [ ] **Step 2: Add a failing test for the six-image entry limit**

Add:

```kotlin
@Test
fun entryWarmupSelectsAtMostSixDistinctLocalThumbnails() {
    val messages = (1..8).map { index ->
        message(
            id = "image-$index",
            type = MessageType.IMAGE,
            localThumbnailPath = "/cache/$index.jpg"
        )
    }

    assertEquals(
        (1..6).map { "/cache/$it.jpg" },
        ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
            messages = messages,
            maxImages = ChatInitialImagePrewarmer.MAX_PREWARM_BEFORE_NAVIGATION_IMAGES
        )
    )
}
```

Expected against current source: compilation fails because the constant is private and still equals 12.

- [ ] **Step 3: Add a failing test that proves dynamic slot refill**

Add:

```kotlin
@Test
fun forEachWithConcurrencyStartsNextItemWhenOneSlotBecomesAvailable() = runTest {
    val releaseThird = CompletableDeferred<Unit>()
    val fourthStarted = CompletableDeferred<Unit>()

    val job = launch {
        ChatInitialImagePrewarmer.forEachWithConcurrency(
            items = listOf(1, 2, 3, 4),
            maxConcurrency = 3
        ) { item ->
            when (item) {
                3 -> releaseThird.await()
                4 -> fourthStarted.complete(Unit)
            }
        }
    }

    testScheduler.runCurrent()

    assertTrue(
        "Item 4 must start while item 3 is still running",
        fourthStarted.isCompleted
    )

    releaseThird.complete(Unit)
    job.join()
}
```

This encodes the required behavior that items 1 and 2 completing immediately free slots for item 4 without waiting for item 3.

- [ ] **Step 4: Add a failing test for the concurrency ceiling**

Add:

```kotlin
@Test
fun forEachWithConcurrencyNeverExceedsConfiguredLimit() = runTest {
    val release = CompletableDeferred<Unit>()
    val started = mutableListOf<Int>()

    val job = launch {
        ChatInitialImagePrewarmer.forEachWithConcurrency(
            items = listOf(1, 2, 3, 4),
            maxConcurrency = 3
        ) { item ->
            started += item
            release.await()
        }
    }

    testScheduler.runCurrent()

    assertEquals(listOf(1, 2, 3), started)

    release.complete(Unit)
    job.join()
}
```

- [ ] **Step 5: Run the prewarmer tests and confirm the new helper is missing**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest \
  --console=plain
```

Expected: compilation fails because `forEachWithConcurrency` and the accessible six-image constant do not exist.

- [ ] **Step 6: Set the bounded entry defaults**

In `ChatInitialImagePrewarmer`, replace the entry constants with:

```kotlin
internal const val PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 300L
internal const val MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 6
internal const val MAX_PREWARM_CONCURRENCY = 3
```

Keep the background `PREWARM_TIMEOUT_MS = 300L` unchanged.

- [ ] **Step 7: Add the dynamic concurrency helper**

Add imports:

```kotlin
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

Add inside `ChatInitialImagePrewarmer`:

```kotlin
internal suspend fun <T> forEachWithConcurrency(
    items: List<T>,
    maxConcurrency: Int,
    operation: suspend (T) -> Unit
) {
    if (items.isEmpty()) {
        return
    }
    val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    coroutineScope {
        items.map { item ->
            async {
                semaphore.withPermit {
                    operation(item)
                }
            }
        }.awaitAll()
    }
}
```

All item coroutines are eligible to start, but the semaphore allows at most three into `operation` at once. When one operation finishes, the next waiting item immediately acquires the released permit.

- [ ] **Step 8: Replace fixed batches with dynamic refill**

Replace the `chunked(...).forEach { batch -> ... }` block in `prewarmLocalThumbnails` with:

```kotlin
withTimeoutOrNull(timeoutMs) {
    withContext(Dispatchers.IO) {
        forEachWithConcurrency(
            items = thumbnailPaths,
            maxConcurrency = maxConcurrency
        ) { path ->
            prewarmLocalThumbnail(appContext, path)
        }
    }
}
```

Do not add `delay(300)` and do not manually inspect Coil's memory cache. `Coil.imageLoader(...).execute(request)` returns immediately on a valid memory-cache hit and performs decoding only on a miss.

- [ ] **Step 9: Run the prewarmer and viewport-policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest \
  --tests com.buyansong.im.chat.ChatThumbnailPrefetchPolicyTest \
  --tests com.buyansong.im.message.ReceivedThumbnailPrewarmPolicyTest \
  --tests com.buyansong.im.message.ThumbnailDownloadSchedulerTest \
  --console=plain
```

Expected: all four classes pass. The new tests prove dynamic refill and a maximum of three active operations; existing tests prove viewport and active-conversation selection remain unchanged.

- [ ] **Step 10: Commit dynamic entry warmup**

```bash
git add \
  app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt \
  app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt
git commit -m "perf: dynamically refill chat thumbnail warmup"
```

### Task 4: Synchronize current documentation and verify the complete path

**Files:**

- Modify: `docs/status/B2-single-chat.md:20-45`
- Modify: `docs/status/B11-image-message-design-status.md:118-198,640-650`
- Modify: `docs/bug/Fix-ChatImageGrayPlaceholderOnChatEntry.md:1-8`
- Modify: `docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md:1-8`

- [ ] **Step 1: Update the single-chat cache description**

Replace the current initial-history cache bullet in `docs/status/B2-single-chat.md` with:

```markdown
- Single-chat entry keeps a repository-level LRU containing the latest 20 display-ready messages for up to 10 conversations. Entry awaits `preloadInitialPage(conversationId)` with a 100 ms coroutine waiting budget; an LRU hit returns immediately, a miss queries SQLite on `Dispatchers.IO`, and no production `runBlocking` remains in this navigation path. Older history remains 20-row SQLite pagination triggered when the list approaches its history boundary; the repository does not cache a second 20-row page.
```

- [ ] **Step 2: Replace the B11 preload strategy with an explicit ownership contract**

In `docs/status/B11-image-message-design-status.md`, update `Thumbnail preload strategy` to state:

```markdown
Thumbnail cache and warmup strategy:

- SQLite stores message metadata, URLs, and `localThumbnailPath`; it does not store image bytes or Bitmap objects.
- `MessageRepository` caches only the latest 20 display-ready message metadata records for up to 10 conversations.
- `ChatThumbnailCache` owns encoded local thumbnail files under Android `cacheDir`.
- The singleton Coil `ImageLoader` owns decoded Bitmap memory. Repository eviction and Coil eviction are intentionally independent.
- `ChatLocalThumbnailRequest` is the only local-thumbnail request builder. It uses the local file as data, a path-based memory key, and a 220dp × 270dp maximum decode bound; it does not assign a Coil disk-cache key for an already-local file.
- Conversation entry selects at most six distinct local thumbnails from the initial 20 messages. It keeps at most three Coil requests active, refills a slot as soon as one request completes, and navigates when all selected requests finish or the 300 ms image budget expires.
- The 300 ms value is an upper bound, not a fixed delay. All memory-cache hits return immediately; if only two requests miss, entry waits only for those two decodes.
- Entry selection is a bounded recent-message warmup window, not an exact Compose viewport calculation.
- After navigation, `ChatScreen` performs actual visible-window prefetch only after `LazyListState` is idle for 160 ms, using a four-item margin, at most 10 images, a 250 ms timeout, and concurrency one.
- Receiver-side downloaded thumbnails are warmed before `localThumbnailPath` emission only for the active conversation, with at most five successful prewarms per scheduler drain. Background conversations retain local files but do not spend Coil Bitmap memory.
- Sender-side generated thumbnails are warmed before outgoing image rows enter chat state.
- Original images remain on demand in `ChatImagePreviewScreen`.
```

Update references to `Size.ORIGINAL`, 12 entry images, 700 ms, fixed batches, or “exact first screen” elsewhere in the current B11 status file.

- [ ] **Step 3: Mark historical bug documents as superseded**

Immediately below the title of both historical bug documents, add:

```markdown
> Historical implementation record. Its embedded “current code” snapshots are superseded by `docs/status/B11-image-message-design-status.md` and `docs/superpowers/plans/2026-07-28-chat-entry-preload-and-thumbnail-warmup.md`. Do not use the old `prewarmAsync`, `Size.ORIGINAL`, 12-image, 700 ms, fixed-batch, or `ChatImageBubble.LaunchedEffect` descriptions as the current contract.
```

Do not rewrite the rest of either historical document.

- [ ] **Step 4: Run the focused regression suite**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.buyansong.im.message.MessageRepositoryCacheTest \
  --tests com.buyansong.im.chat.ChatViewModelInitialCacheTest \
  --tests com.buyansong.im.chat.ChatImageBubbleLayoutPolicyTest \
  --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest \
  --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest \
  --tests com.buyansong.im.chat.ChatThumbnailPrefetchPolicyTest \
  --tests com.buyansong.im.message.ReceivedThumbnailPrewarmPolicyTest \
  --tests com.buyansong.im.message.ThumbnailDownloadSchedulerTest \
  --console=plain
```

Expected: all selected tests pass.

- [ ] **Step 5: Run the complete Android unit-test suite**

Run:

```bash
./gradlew :app:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Build the debug APK**

Run:

```bash
./gradlew :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Check source and documentation invariants**

Run:

```bash
rg -n "preloadInitialPageSync|INITIAL_PAGE_SYNC_TIMEOUT_MS|CACHED_HISTORY_WINDOW_SIZE|HISTORY_WINDOW_CACHE_SIZE|Size\\.ORIGINAL|PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 700L|MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 12|\\.chunked\\(" \
  app/src/main/java/com/buyansong/im \
  app/src/test/java/com/buyansong/im \
  docs/status/B2-single-chat.md \
  docs/status/B11-image-message-design-status.md
```

Expected: no output.

Run:

```bash
rg -n "INITIAL_PAGE_SIZE = 20|INITIAL_PAGE_CACHE_SIZE = 10|INITIAL_PAGE_PRELOAD_TIMEOUT_MS = 100L|PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 300L|MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 6|MAX_PREWARM_CONCURRENCY = 3" \
  app/src/main/java/com/buyansong/im
```

Expected: all six constants are present.

- [ ] **Step 8: Perform manual timing and cache-path checks**

Use a conversation whose latest 20 messages include at least six cached local thumbnails:

1. Open with all six already in Coil memory. Confirm navigation occurs immediately rather than after 300 ms.
2. Restart the process so Coil memory is cold while local thumbnail files remain. Open the conversation and confirm navigation occurs when decoding finishes or at approximately the 300 ms image budget.
3. Use debug timing logs or a profiler to confirm no more than three thumbnail decode requests are active simultaneously.
4. Make one of the first three requests artificially slower in a debug test build. Confirm a fourth request starts as soon as either of the other two slots becomes free.
5. Receive more than five images in the active conversation. Confirm the current per-drain active-receive budget remains five.
6. Receive an image in a background conversation. Confirm the local thumbnail file is cached without immediate Coil Bitmap warmup.
7. Scroll rapidly through an image-heavy history. Confirm viewport prefetch waits for idle and does not decode every pass-through row.
8. Pull toward older history. Confirm the next 20 messages load through SQLite before the user reaches the oldest item; no second-page Repository LRU is required.

- [ ] **Step 9: Append verification rows only after all commands pass**

Append to `docs/status/B2-single-chat.md`:

```markdown
| 2026-07-28 | Suspending 20-message chat entry preload | `./gradlew :app:testDebugUnitTest --console=plain`; `./gradlew :app:assembleDebug --console=plain` | Passed: the 10-conversation Repository LRU still stores only the latest 20 display-ready messages, cache misses suspend on IO within the 100 ms waiting budget, cache hits avoid duplicate DAO queries, and older history remains SQLite-backed 20-row pagination. |
```

Append to `docs/status/B11-image-message-design-status.md`:

```markdown
| 2026-07-28 | Bounded dynamic Coil entry warmup | `./gradlew :app:testDebugUnitTest --console=plain`; `./gradlew :app:assembleDebug --console=plain` | Passed: entry selects at most six local thumbnails, waits at most 300 ms, keeps at most three operations active with immediate slot refill, uses bubble-bound decoding and one shared memory-cache request, and preserves active-receive plus idle-viewport policies. |
```

- [ ] **Step 10: Commit documentation after verification**

```bash
git add \
  docs/status/B2-single-chat.md \
  docs/status/B11-image-message-design-status.md \
  docs/bug/Fix-ChatImageGrayPlaceholderOnChatEntry.md \
  docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md
git commit -m "docs: clarify chat thumbnail cache ownership"
```

## Acceptance Criteria

- Repository initial-page cache remains 20 messages for 10 conversations.
- No 40-message metadata window or second-page Repository LRU is introduced.
- Metadata preload is suspending and has a 100 ms waiting budget; production `runBlocking` is removed from the navigation path.
- Entry warmup receives only the initial 20 messages and selects at most six distinct `localThumbnailPath` values.
- Entry warmup has a 300 ms upper bound and never sleeps for a fixed duration.
- Six Coil memory hits complete and navigate immediately.
- If only two requests miss, navigation waits only for those two decodes unless the 300 ms budget expires.
- No more than three warmup operations execute concurrently.
- When a warmup operation finishes, the next waiting operation starts without waiting for the other two operations from its former group.
- Local thumbnail requests use `File`, one path-based Coil memory-cache key, and a 220dp × 270dp pixel decode bound.
- Local thumbnail requests do not set `diskCacheKey` or `Size.ORIGINAL`.
- Background receives do not prewarm Coil; active-conversation receive and viewport policies remain unchanged.
- Older history remains 20-row indexed SQLite pagination triggered near the top of the loaded list.
- Current status documents describe the implemented contract, and historical bug documents are clearly marked as superseded.
- Focused tests, the full Android unit suite, and debug APK assembly pass before documentation records success.

## Explicit Follow-ups Outside This Plan

- Detect when Android has removed a thumbnail file under `cacheDir`, clear or invalidate the stale `localThumbnailPath`, and re-enqueue thumbnail download.
- Replace the `createdAt`-only history cursor with a stable composite cursor and align the SQLite ordering index.
- Fix post-`LIMIT` display filtering that can underfill a page and set `hasMoreLocal` false too early.
- Add production metrics for metadata cache hit ratio, Coil data source, entry-warmup duration, navigation-to-first-frame latency, and timeout frequency before changing 100 ms or 300 ms again.
