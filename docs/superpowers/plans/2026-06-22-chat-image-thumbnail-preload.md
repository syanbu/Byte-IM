# Chat Image Thumbnail Preload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Version check:** Updated on 2026-06-22 after the chat image stack changed. The older draft tried to reintroduce `ChatThumbnailPreloader` and to pass it through `ChatImageCompressor`/`ChatViewModel`; that is now stale. The current code already has `ChatLocalThumbnailRequest`, sender-side `prewarmOutgoingLocalThumbnails(...)`, and navigation-time `ChatInitialImagePrewarmer.prewarmBeforeNavigation(...)`. This plan extends those existing abstractions instead of adding a second preloader layer.

**Goal:** Make image-message thumbnails feel instant and smooth in chat lists by removing the bubble-level preload race, reusing one Coil request/cache-key path, prewarming receiver-downloaded thumbnails before they are emitted to UI, and adding viewport-based local-thumbnail prefetch.

**Architecture:** Keep chat bubbles presentation-only. `ChatLocalThumbnailRequest` remains the single source of truth for local-thumbnail Coil requests. `ChatInitialImagePrewarmer` becomes the shared local-thumbnail warm-up utility for navigation, receiver cache completion, and viewport prefetch. Sender-side prewarm stays in `ChatScreen` before `ChatViewModel.sendImages(...)`, because that path already guarantees thumbnails are decoded before local outgoing rows enter Compose state. `ChatImageBubble` switches from `SubcomposeAsyncImage` plus per-bubble `LaunchedEffect` to a lightweight `AsyncImage` that receives the existing `ChatLocalThumbnailRequest` model.

**Tech Stack:** Kotlin, Jetpack Compose, Coil Compose 2.7.0, Coroutines, SQLite-backed chat models, JUnit4.

---

## Current Code Facts

- `app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt` already centralizes local thumbnail `ImageRequest` creation and cache keys.
- `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt` already performs bounded Coil `execute(...)` warm-up before navigation and has unit coverage.
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` already calls `prewarmOutgoingLocalThumbnails(context, preparedImages)` before `viewModel.sendImages(preparedImages)`.
- `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt` still has the stale per-bubble `LaunchedEffect` and still uses `SubcomposeAsyncImage`.
- `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt` caches receiver thumbnails but does not decode-warm them before `onCached(...)` writes `localThumbnailPath`.
- `app/src/main/java/com/buyansong/im/MainActivity.kt` already waits for `ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)` before navigating from conversation-like entry points.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt` | Modify | Add reusable single-path and list-path local thumbnail prewarm APIs that reuse `ChatLocalThumbnailRequest`. |
| `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt` | Modify | Cover path filtering, viewport-window selection, and max-count behavior. |
| `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt` | Modify | Prewarm receiver-downloaded local thumbnails before `onCached(...)` emits them to repository/UI state. |
| `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` | Verify/Modify if needed | Keep default scheduler construction compiling after the scheduler gains a prewarm callback. |
| `app/src/main/java/com/buyansong/im/MainActivity.kt` | Modify | Wire receiver scheduler prewarm with `ChatInitialImagePrewarmer.prewarmLocalThumbnail(...)`. |
| `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt` | Modify | Replace `SubcomposeAsyncImage` and per-bubble `LaunchedEffect` with lightweight `AsyncImage`. |
| `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` | Modify | Add viewport-based prefetch around `LazyListState.layoutInfo.visibleItemsInfo`. Keep existing outgoing prewarm unchanged. |
| `docs/status/B11-image-message-design-status.md` | Modify | Record the new preload strategy and remaining emulator/device verification risk. |

---

## Task 1: Extend the Existing Initial Image Prewarmer

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt`

- [ ] **Step 1: Add failing tests for bounded path selection**

In `ChatInitialImagePrewarmerTest`, add coverage for:

- blank `localThumbnailPath` values are ignored
- `thumbnailPathsToPrewarm(messages, visibleMinIndex, visibleMaxIndex, margin, maxImages)` includes the visible window plus margin
- the viewport selector clamps to list bounds
- duplicate paths are removed before the `maxImages` limit is applied

Use the existing `message(...)` helper and keep these as pure unit tests.

- [ ] **Step 2: Run the focused tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest
```

Expected: FAIL because the viewport overload does not exist and blank paths are not yet filtered.

- [ ] **Step 3: Implement reusable path helpers and prewarm APIs**

In `ChatInitialImagePrewarmer.kt`:

- update `thumbnailPathsToPrewarm(messages)` to use `ChatLocalThumbnailRequest.cacheKey(...)` so blank paths are ignored consistently
- add:

```kotlin
fun thumbnailPathsToPrewarm(
    messages: List<ChatMessage>,
    visibleMinIndex: Int,
    visibleMaxIndex: Int,
    margin: Int,
    maxImages: Int
): List<String>
```

Implementation notes:

- return `emptyList()` when `messages` is empty, either visible index is negative, or `maxImages <= 0`
- use `minOf(visibleMinIndex, visibleMaxIndex)` and `maxOf(...)`
- clamp `(start - margin)` to `0` and `(end + margin)` to `messages.lastIndex`
- filter image messages only, map through `ChatLocalThumbnailRequest.cacheKey(...)`, `distinct()`, then `take(maxImages)`

Also add a reusable suspend warm-up API:

```kotlin
suspend fun prewarmLocalThumbnail(context: Context, localThumbnailPath: String): Boolean
```

and a bounded list API:

```kotlin
suspend fun prewarmLocalThumbnails(
    context: Context,
    localThumbnailPaths: List<String>,
    timeoutMs: Long,
    maxConcurrency: Int
)
```

Both must use `context.applicationContext`, `Dispatchers.IO`, `Coil.imageLoader(appContext).execute(...)`, and `ChatLocalThumbnailRequest.build(...)`. `prewarmBeforeNavigation(...)` should delegate to `prewarmLocalThumbnails(...)` after selecting its paths.

- [ ] **Step 4: Run focused tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest
```

Expected: PASS.

- [ ] **Step 5: Compile-check**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt
git commit -m "Extend chat thumbnail prewarmer"
```

---

## Task 2: Prewarm Receiver-Downloaded Thumbnails Before UI Refresh

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt`
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` if constructor defaults require adjustment
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`

- [ ] **Step 1: Add a prewarm callback dependency to schedulers**

In `ThumbnailDownloadScheduler.kt`, add a dependency instead of introducing a new `ChatThumbnailPreloader` type:

```kotlin
private val prewarmLocalThumbnail: suspend (String) -> Unit = {}
```

Apply it to both `ImmediateThumbnailDownloadScheduler` and `CoroutineThumbnailDownloadScheduler`.

Implementation notes:

- `CoroutineThumbnailDownloadScheduler` already launches its worker on the injected `thumbnailDownloadScope`, which is `Dispatchers.IO` in `MainActivity`, so it can call the suspend callback directly from `drainQueue`.
- If Kotlin requires `drainQueue` to become `suspend`, update the `scope.launch { drainQueue() }` call accordingly.
- For `ImmediateThumbnailDownloadScheduler`, keep the default callback no-op so existing tests and repository defaults remain simple.

- [ ] **Step 2: Prewarm after cache write and before `onCached(...)`**

Update both scheduler implementations so the order is:

1. `thumbnailCache.cacheThumbnail(...)`
2. `prewarmLocalThumbnail(localPath)`
3. `onCached(messageId, localPath)`

This preserves the strict receiver-side display rule: incoming image rows still become visible only after the local thumbnail file exists, but now the first emitted UI state can hit a warm Coil memory cache when possible.

- [ ] **Step 3: Wire the callback from `MainActivity`**

In `AccountScopedRepositories.create(...)`, pass:

```kotlin
prewarmLocalThumbnail = { localPath ->
    ChatInitialImagePrewarmer.prewarmLocalThumbnail(context, localPath)
}
```

to `CoroutineThumbnailDownloadScheduler`.

Do not add `CoilChatThumbnailPreloader`, and do not change `SelfHostedImApp(...)` parameters for this. `AccountScopedRepositories.create(...)` already receives `context`, which is enough.

- [ ] **Step 4: Compile-check**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run message/cache tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryCacheTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt app/src/main/java/com/buyansong/im/message/MessageRepository.kt app/src/main/java/com/buyansong/im/MainActivity.kt
git commit -m "Prewarm received chat thumbnails"
```

---

## Task 3: Make `ChatImageBubble` Presentation-Only

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt`

- [ ] **Step 1: Replace imports**

Remove:

```kotlin
import androidx.compose.runtime.LaunchedEffect
import coil.Coil
import coil.compose.SubcomposeAsyncImage
```

Add:

```kotlin
import coil.compose.AsyncImage
```

Keep `LocalContext`, because the bubble still builds a local `ImageRequest` model through `ChatLocalThumbnailRequest`.

- [ ] **Step 2: Delete the per-bubble preload side effect**

Remove the whole `LaunchedEffect(message.localThumbnailPath)` block. The current comments describe a race-prone fallback; after Tasks 1-2 and the existing navigation/send prewarm paths, the bubble should only render the model it receives.

- [ ] **Step 3: Render with `AsyncImage`**

Replace the `SubcomposeAsyncImage(...)` block with:

```kotlin
AsyncImage(
    model = model,
    contentDescription = message.content,
    contentScale = ContentScale.Crop,
    modifier = Modifier.fillMaxSize()
)
```

Do not re-add inline loading or error UI inside the bubble. Existing row-level upload/send/failure status remains the user-facing status surface.

- [ ] **Step 4: Make gray fallback conditional**

Change:

```kotlin
.background(Color(0xFFE5E2E1))
```

to:

```kotlin
.background(if (model == null) Color(0xFFE5E2E1) else Color.Transparent)
```

This removes the stable gray paint from the normal loaded-model path while still preserving a fallback surface when no thumbnail model exists.

- [ ] **Step 5: Compile-check bubble rendering**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt
git commit -m "Use lightweight chat image bubbles"
```

---

## Task 4: Add Viewport-Based Thumbnail Prefetch in `ChatScreen`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt` if viewport path tests were not added in Task 1

- [ ] **Step 1: Add state for prefetch de-duplication**

In `ChatScreen.kt`, add:

```kotlin
import androidx.compose.runtime.mutableStateListOf
```

Near `val context = LocalContext.current`, add:

```kotlin
val prefetchedThumbnailPaths = remember(state.peerId) { mutableStateListOf<String>() }
```

- [ ] **Step 2: Derive prefetch paths from `LazyListState`**

After `shouldLoadEarlierHistory`, add:

```kotlin
val thumbnailPrefetchPaths by remember(
    listState,
    state.messages,
    prefetchedThumbnailPaths
) {
    derivedStateOf {
        val visibleMinIndex = listState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: -1
        val visibleMaxIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
        ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
            messages = state.messages,
            visibleMinIndex = visibleMinIndex,
            visibleMaxIndex = visibleMaxIndex,
            margin = CHAT_THUMBNAIL_PREFETCH_MARGIN,
            maxImages = CHAT_THUMBNAIL_PREFETCH_MAX_IMAGES
        ).filterNot { it in prefetchedThumbnailPaths }
    }
}
```

- [ ] **Step 3: Execute viewport prefetch off the UI thread**

After the existing `LaunchedEffect(shouldLoadEarlierHistory)` block, add:

```kotlin
LaunchedEffect(thumbnailPrefetchPaths) {
    val paths = thumbnailPrefetchPaths
    if (paths.isEmpty()) return@LaunchedEffect
    prefetchedThumbnailPaths += paths
    ChatInitialImagePrewarmer.prewarmLocalThumbnails(
        context = context,
        localThumbnailPaths = paths,
        timeoutMs = CHAT_THUMBNAIL_PREFETCH_TIMEOUT_MS,
        maxConcurrency = CHAT_THUMBNAIL_PREFETCH_CONCURRENCY
    )
}
```

- [ ] **Step 4: Add prefetch constants**

Near `private const val CHAT_ERROR_TOAST_DURATION_MS = 2_000L`, add:

```kotlin
private const val CHAT_THUMBNAIL_PREFETCH_MARGIN = 10
private const val CHAT_THUMBNAIL_PREFETCH_MAX_IMAGES = 24
private const val CHAT_THUMBNAIL_PREFETCH_TIMEOUT_MS = 300L
private const val CHAT_THUMBNAIL_PREFETCH_CONCURRENCY = 3
```

- [ ] **Step 5: Keep outgoing prewarm unchanged**

Do not modify `ChatImageCompressor.prepareSelectedImage(...)`, `SelectedChatImageResolver`, or `ChatViewModel` for sender prewarm. The current send path already does:

```kotlin
prewarmOutgoingLocalThumbnails(context, preparedImages)
viewModel.sendImages(preparedImages)
```

That ordering is intentional and should remain.

- [ ] **Step 6: Compile-check viewport prefetch**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run focused tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/java/com/buyansong/im/chat/ChatScreen.kt app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt
git commit -m "Prefetch visible chat thumbnails"
```

---

## Task 5: Update B11 Image Message Status Documentation

**Files:**
- Modify: `docs/status/B11-image-message-design-status.md`

- [ ] **Step 1: Update the top status note**

Add a new `Last modified` note for 2026-06-22 describing:

- `ChatImageBubble` is presentation-only and no longer starts its own `LaunchedEffect` preload
- receiver thumbnail downloads now warm the local thumbnail through `ChatInitialImagePrewarmer` before `localThumbnailPath` is emitted
- `ChatScreen` prefetches local thumbnails around the current viewport
- sender-side hot-send prewarm remains before `ChatViewModel.sendImages(...)`

- [ ] **Step 2: Replace the stale per-bubble preload section**

Replace `Per-bubble self-preload (replaces conversation-list-to-chat preload):` with:

```markdown
Thumbnail preload strategy:

- `ChatLocalThumbnailRequest` is the single local-thumbnail Coil request builder and cache-key source.
- `ChatImageBubble` is presentation-only and no longer starts its own `LaunchedEffect` preload.
- Sender-side local thumbnails are prewarmed in `ChatScreen` after image compression and before `ChatViewModel.sendImages(...)` creates local outgoing rows.
- Receiver-side downloaded thumbnails are prewarmed after `ThumbnailDownloadScheduler` caches the file and before `localThumbnailPath` is written to the message row.
- Conversation entry waits briefly on `ChatInitialImagePrewarmer.prewarmBeforeNavigation(...)` for the initial local thumbnail set.
- `ChatScreen` prefetches local thumbnails around the current `LazyListState` viewport so fast-scroll targets can warm before composition.
- Chat-list image requests use the thumbnail resource (`localThumbnailPath ?: thumbnailUrl`); original images remain on-demand in `ChatImagePreviewScreen`.
```

- [ ] **Step 3: Update stale receiver-side notes**

In the `Current optimized receiver-side strategy` area, replace the line saying the only visible-thumbnail warm-up is the per-bubble `LaunchedEffect`. The new text should say receiver cache completion, navigation-time warm-up, and viewport prefetch are the warm-up paths; none of them should fetch originals.

- [ ] **Step 4: Add a verification risk note**

Under `Current Risks`, add:

```markdown
- Fast-scroll image smoothness still needs emulator or device verification with a conversation containing 50-200 cached image thumbnails. Unit tests cover path/window selection, but frame timing and memory behavior must be observed on Android.
```

- [ ] **Step 5: Commit**

```powershell
git add docs/status/B11-image-message-design-status.md
git commit -m "Document chat thumbnail preload strategy"
```

---

## Final Verification

- [ ] **Step 1: Run compile**

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run focused unit tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --tests com.buyansong.im.message.MessageRepositoryCacheTest
```

Expected: PASS.

- [ ] **Step 3: Run broader debug unit tests if the workspace is not blocked**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: PASS if no pre-existing unrelated test compile blockers exist. If it fails, inspect the first failing test and record whether it is related to the thumbnail preload changes.

- [ ] **Step 4: Manual emulator/device checks**

1. Send 9 local images in one batch. Expected: each image bubble shows its thumbnail with no obvious gray flash, and row-level spinner remains outside the bubble.
2. Receive image messages on another device or emulator. Expected: incoming image appears only after thumbnail cache exists, and first display has no obvious gray flash.
3. Open a conversation from the conversation list or notification with cached local image thumbnails. Expected: initial visible image rows are already warm when navigation completes, subject to the 700 ms cap.
4. Open a conversation with 50-200 cached image messages and rapidly scroll upward/downward. Expected: thumbnails appear smoothly, no original images load in the list, and no repeated jank clusters around image rows.
5. Tap an image. Expected: `ChatImagePreviewScreen` still loads `localOriginalPath ?: imageUrl ?: localThumbnailPath ?: thumbnailUrl`.

---

## Self-Review Notes

- Stale draft removal: This plan intentionally does not create `ChatThumbnailPreloader`, `CoilChatThumbnailPreloader`, or `ChatThumbnailPrefetchPolicy`; the existing `ChatLocalThumbnailRequest` and `ChatInitialImagePrewarmer` cover those responsibilities with less duplication.
- Sender consistency: Sender-side hot-send prewarm is already implemented in `ChatScreen` and should not move into `ChatImageCompressor`, because the current placement makes the ordering relative to `sendImages(...)` explicit.
- Receiver consistency: The receiver scheduler callback warms a local file only after `thumbnailCache.cacheThumbnail(...)` succeeds. It never performs network fetches or loads originals.
- UI boundary: `ChatImageBubble` becomes a pure renderer. Warm-up happens before the model reaches the bubble or near the viewport, not from inside the bubble composition.
- Scope control: Receiver download parallelism is intentionally not included. It remains a follow-up only if 200-image receive-time caching is too slow after this plan.
