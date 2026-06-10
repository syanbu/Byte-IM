# Image Bubble Instant Thumbnail (WeChat-like) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make outgoing image bubbles render the local thumbnail instantly (no gray-placeholder flash) and keep the existing left-side spinner until the server ACKs the send. Make incoming image bubbles stay on the gray placeholder until the local thumbnail download finishes (no in-bubble network fetch).

**Architecture:**
- **Sender path** — extend `SelectedChatImage` to carry the already-decoded `Bitmap` produced by `ChatImageCompressor`; pre-warm Coil's `MemoryCache` with that `Bitmap` (keyed by `localThumbnailPath`) inside `ChatViewModel.sendImages` / `sendImage` / `retryImageMessage` **before** the new message row is committed and emitted to Compose, so the bubble's first composition hits the cache.
- **Receiver path** — drop the `?: message.thumbnailUrl` fallback inside `ChatImageBubble`, so when `localThumbnailPath` is null the bubble shows only the existing `Color(0xFFE5E2E1)` gray container. The existing `ThumbnailDownloadScheduler` continues to populate `localThumbnailPath`, triggering a re-render once the local file is ready.
- Existing surfaces left untouched: `OutgoingMessageStatus` (left-side spinner), `ChatImageBubbleLoadingPolicy` (still all-false), `AndroidChatThumbnailCache`, `ThumbnailDownloadScheduler`, `MessageRepository.createLocalImageMessages`.

**Tech Stack:** Android Kotlin, Jetpack Compose, Coil 2.7.0 (`io.coil-kt:coil-compose`), SQLite DAO layer, JUnit / Coroutines test.

---

## File Structure

### New files
- `app/src/main/java/com/buyansong/im/message/ChatImageMemoryPreloader.kt` — single-responsibility cache pre-warm seam. `interface ChatImageMemoryPreloader { fun preload(cacheKey: String, bitmap: Bitmap?, bytes: ByteArray) }` + `object NoopChatImageMemoryPreloader` (JVM/unit-test wiring) + `class CoilImageMemoryPreloader(context: Context)` (production wiring; writes to Coil's `MemoryCache`).
- `app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloader.kt` — test fake that records `preload(cacheKey, ...)` calls in order, used by ViewModel tests to assert preload happens before insert.

### Modified files
- `app/src/main/java/com/buyansong/im/message/ImageUploadModels.kt` — add `thumbnailBitmap: android.graphics.Bitmap? = null` to `SelectedChatImage`; exclude it from `equals`/`hashCode` so existing tests that compare `SelectedChatImage` instances stay valid.
- `app/src/main/java/com/buyansong/im/message/ChatImageCompressor.kt` — stop recycling `thumbnailBitmap` in `prepareSelectedImage`; pass it into `SelectedChatImage`. In `resolve(message)` (retry path), additionally decode the thumbnail file into a `Bitmap` and pass it.
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` — add constructor parameter `imagePreloader: ChatImageMemoryPreloader = NoopChatImageMemoryPreloader`. Call `imagePreloader.preload(...)` at the start of `sendImages`, `sendImage`, and `retryImageMessage`'s `UPLOAD_FAILED` branch — inside the `withContext(dispatcher)` block but before the insert / `markImageUploading`.
- `app/src/main/java/com/buyansong/im/MainActivity.kt` — instantiate `CoilImageMemoryPreloader(applicationContext)` next to the existing `AndroidChatThumbnailCache(this)` site (line 144) and pass it through the existing wiring all the way down to the `ChatViewModel(...)` constructor at line 650.
- `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt` — change the model expression at line 39 from `message.localThumbnailPath ?: message.thumbnailUrl` to `message.localThumbnailPath`. No other change in this file.
- `app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt` — new tests (see Task 5/6) and a small fixture update to pass `FakeChatImageMemoryPreloader` into existing `ChatViewModel(...)` test builders.

### Files explicitly NOT modified
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` — `OutgoingMessageStatus` (`CircularProgressIndicator` on the left of the bubble for `UPLOADING` / `SENDING`) stays exactly as it is.
- `app/src/main/java/com/buyansong/im/chat/ChatImageBubbleLoadingPolicy.kt` — `showInlineProgress()` and `showBubbleStatusProgress()` stay `false`.
- `app/src/main/java/com/buyansong/im/message/ChatThumbnailCache.kt` — unchanged.
- `app/src/main/java/com/buyansong/im/message/ThumbnailDownloadScheduler.kt` — unchanged; it still drives the receiver-side download and calls `updateLocalThumbnailPath` once a thumbnail lands on disk.
- `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` — `createLocalImageMessages`, `createLocalImageMessage`, `createLocalGroupImageMessage`, `markImageUploading`, `requeueImageMessageSend` are all untouched.

---

## Task 1: Add `ChatImageMemoryPreloader` seam (interface + Noop + Coil impl)

**Files:**
- Create: `app/src/main/java/com/buyansong/im/message/ChatImageMemoryPreloader.kt`
- Create: `app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloader.kt`

- [ ] **Step 1.1: Write the failing test for `FakeChatImageMemoryPreloader` recording**

Create `app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloaderTest.kt`:

```kotlin
package com.buyansong.im.message

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeChatImageMemoryPreloaderTest {

    @Test
    fun recordsPreloadCallsInOrder() {
        val fake = FakeChatImageMemoryPreloader()
        fake.preload("/cache/thumb-a.jpg", bitmap = null, bytes = byteArrayOf(1, 2))
        fake.preload("/cache/thumb-b.jpg", bitmap = null, bytes = byteArrayOf(3, 4))

        assertEquals(
            listOf("/cache/thumb-a.jpg", "/cache/thumb-b.jpg"),
            fake.preloadedKeys
        )
        assertEquals(2, fake.preloadCount)
    }
}
```

- [ ] **Step 1.2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.FakeChatImageMemoryPreloaderTest`
Expected: FAIL with `Unresolved reference: FakeChatImageMemoryPreloader`.

- [ ] **Step 1.3: Implement `ChatImageMemoryPreloader.kt`**

Create `app/src/main/java/com/buyansong/im/message/ChatImageMemoryPreloader.kt`:

```kotlin
package com.buyansong.im.message

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.Coil
import coil.memory.MemoryCache

/**
 * Pre-warms an in-memory image cache so that the first composition of an image
 * bubble for a freshly-sent message hits a cached bitmap and never shows the
 * underlying gray placeholder. Keyed by the absolute path of the local
 * thumbnail file so it matches the `model` Coil resolves inside
 * `ChatImageBubble`.
 */
interface ChatImageMemoryPreloader {
    /**
     * @param cacheKey usually the absolute path of the local thumbnail file
     *   (same value used as `model` in `SubcomposeAsyncImage` for this bubble).
     * @param bitmap an already-decoded thumbnail bitmap. When non-null this is
     *   used directly with zero decode overhead. When null the implementation
     *   falls back to decoding `bytes`.
     * @param bytes raw JPEG/PNG bytes of the thumbnail; used only when
     *   `bitmap` is null.
     */
    fun preload(cacheKey: String, bitmap: Bitmap?, bytes: ByteArray)
}

object NoopChatImageMemoryPreloader : ChatImageMemoryPreloader {
    override fun preload(cacheKey: String, bitmap: Bitmap?, bytes: ByteArray) = Unit
}

class CoilImageMemoryPreloader(private val context: Context) : ChatImageMemoryPreloader {
    override fun preload(cacheKey: String, bitmap: Bitmap?, bytes: ByteArray) {
        val trimmedKey = cacheKey.trim().takeIf { it.isNotEmpty() } ?: return
        val cache = Coil.imageLoader(context).memoryCache ?: return
        val resolvedBitmap = bitmap
            ?: runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return
        cache[MemoryCache.Key(trimmedKey)] = MemoryCache.Value(resolvedBitmap)
    }
}
```

- [ ] **Step 1.4: Implement `FakeChatImageMemoryPreloader.kt`**

Create `app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloader.kt`:

```kotlin
package com.buyansong.im.message

import android.graphics.Bitmap

class FakeChatImageMemoryPreloader : ChatImageMemoryPreloader {

    private val mutablePreloadedKeys = mutableListOf<String>()
    val preloadedKeys: List<String> get() = mutablePreloadedKeys.toList()
    val preloadCount: Int get() = mutablePreloadedKeys.size

    override fun preload(cacheKey: String, bitmap: Bitmap?, bytes: ByteArray) {
        mutablePreloadedKeys += cacheKey
    }
}
```

- [ ] **Step 1.5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.message.FakeChatImageMemoryPreloaderTest`
Expected: PASS.

- [ ] **Step 1.6: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/ChatImageMemoryPreloader.kt \
        app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloader.kt \
        app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloaderTest.kt
git commit -m "feat(chat): add ChatImageMemoryPreloader seam for instant image bubble render"
```

---

## Task 2: Carry decoded thumbnail Bitmap through `SelectedChatImage`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/ImageUploadModels.kt`

- [ ] **Step 2.1: Add `thumbnailBitmap` field to `SelectedChatImage`**

In `app/src/main/java/com/buyansong/im/message/ImageUploadModels.kt`, replace lines 28-37 (the `data class SelectedChatImage` declaration) with:

```kotlin
data class SelectedChatImage(
    val originalBytes: ByteArray,
    val thumbnailBytes: ByteArray,
    val localOriginalPath: String,
    val localThumbnailPath: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val selectionOrder: Int = 0,
    // Already-decoded thumbnail bitmap produced by ChatImageCompressor. When
    // present, ChatImageMemoryPreloader can warm Coil's memory cache without a
    // second decode. Excluded from equals/hashCode so existing fixtures that
    // construct SelectedChatImage without a bitmap remain comparable.
    val thumbnailBitmap: android.graphics.Bitmap? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelectedChatImage) return false
        if (!originalBytes.contentEquals(other.originalBytes)) return false
        if (!thumbnailBytes.contentEquals(other.thumbnailBytes)) return false
        if (localOriginalPath != other.localOriginalPath) return false
        if (localThumbnailPath != other.localThumbnailPath) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (mimeType != other.mimeType) return false
        if (selectionOrder != other.selectionOrder) return false
        return true
    }

    override fun hashCode(): Int {
        var result = originalBytes.contentHashCode()
        result = 31 * result + thumbnailBytes.contentHashCode()
        result = 31 * result + localOriginalPath.hashCode()
        result = 31 * result + localThumbnailPath.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + selectionOrder
        return result
    }
}
```

Note: the manual `equals`/`hashCode` override is required because the auto-generated ones for `data class` would include `thumbnailBitmap` and break any existing test that compares two `SelectedChatImage` instances where one has a bitmap and one does not. `ByteArray` comparison is also already required by the existing fields, so we keep the same manual semantics.

- [ ] **Step 2.2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If any existing call site of `SelectedChatImage(...)` named-args breaks, it will fail here; fix by leaving the new arg unset (default `null`).

- [ ] **Step 2.3: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/ImageUploadModels.kt
git commit -m "feat(chat): carry decoded thumbnail Bitmap through SelectedChatImage"
```

---

## Task 3: Stop recycling the thumbnail Bitmap in `ChatImageCompressor`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/ChatImageCompressor.kt`

- [ ] **Step 3.1: Update `prepareSelectedImage` to pass the Bitmap into `SelectedChatImage`**

In `app/src/main/java/com/buyansong/im/message/ChatImageCompressor.kt`, replace the body of `prepareSelectedImage` (lines 21-53) with:

```kotlin
    suspend fun prepareSelectedImage(
        context: Context,
        contentResolver: ContentResolver,
        uri: Uri
    ): SelectedChatImage? {
        return withContext(Dispatchers.IO) {
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            try {
                val originalBytes = compress(source, ORIGINAL_QUALITY) ?: return@withContext null
                val thumbnailBitmap = scaleToThumbnail(source)
                val thumbnailBytes = compress(thumbnailBitmap, THUMBNAIL_QUALITY) ?: run {
                    if (thumbnailBitmap !== source) {
                        thumbnailBitmap.recycle()
                    }
                    return@withContext null
                }
                val originalFile = writeCacheFile(context, originalBytes, "chat-origin", ".jpg")
                val thumbnailFile = writeCacheFile(context, thumbnailBytes, "chat-thumb", ".jpg")
                // Intentionally do NOT recycle thumbnailBitmap here. It is
                // handed off to SelectedChatImage so ChatImageMemoryPreloader
                // can install it directly into Coil's MemoryCache without a
                // second BitmapFactory.decodeByteArray pass. Coil's LruCache
                // will eventually evict it; until then GC is held off by the
                // cache reference itself. We still recycle if it is the same
                // instance as source so we don't double-free.
                SelectedChatImage(
                    originalBytes = originalBytes,
                    thumbnailBytes = thumbnailBytes,
                    localOriginalPath = originalFile.absolutePath,
                    localThumbnailPath = thumbnailFile.absolutePath,
                    width = source.width,
                    height = source.height,
                    mimeType = JPEG_CONTENT_TYPE,
                    thumbnailBitmap = if (thumbnailBitmap === source) null else thumbnailBitmap,
                )
            } finally {
                source.recycle()
            }
        }
    }
```

The change set:
- The `try { ... } finally { if (thumbnailBitmap !== source) thumbnailBitmap.recycle() }` is removed. The bitmap is now owned by `SelectedChatImage`.
- If `scaleToThumbnail` returned `source` directly (image already smaller than 480px), we cannot hand the bitmap to `SelectedChatImage` because the outer `finally` recycles `source`. In that case we set `thumbnailBitmap = null` and the preloader will decode from `thumbnailBytes`. This keeps the existing `source.recycle()` invariant.
- On the failure path (`compress` returns null) we still recycle the scaled thumbnail to avoid leaking.

- [ ] **Step 3.2: Update `resolve` (retry path) to also decode the thumbnail Bitmap**

Replace the body of `resolve` (lines 55-74) with:

```kotlin
    override suspend fun resolve(message: ChatMessage): SelectedChatImage? {
        return withContext(Dispatchers.IO) {
            val originalPath = message.localOriginalPath ?: return@withContext null
            val thumbnailPath = message.localThumbnailPath ?: return@withContext null
            val originalFile = File(originalPath)
            val thumbnailFile = File(thumbnailPath)
            if (!originalFile.isFile || !thumbnailFile.isFile) {
                return@withContext null
            }
            val thumbnailBytes = thumbnailFile.readBytes()
            val thumbnailBitmap = runCatching {
                BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
            }.getOrNull()
            SelectedChatImage(
                originalBytes = originalFile.readBytes(),
                thumbnailBytes = thumbnailBytes,
                localOriginalPath = originalPath,
                localThumbnailPath = thumbnailPath,
                width = message.imageWidth ?: return@withContext null,
                height = message.imageHeight ?: return@withContext null,
                mimeType = message.mimeType ?: JPEG_CONTENT_TYPE,
                thumbnailBitmap = thumbnailBitmap,
            )
        }
    }
```

- [ ] **Step 3.3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.4: Run the full existing test module to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All existing tests pass. `ChatImageCompressor` is an Android-coupled class (uses `BitmapFactory`/`Bitmap`) and is not directly unit-tested today (codegraph confirms `no covering tests found`), so the risk is downstream tests that build `SelectedChatImage` literals — those keep working because `thumbnailBitmap` defaults to `null`.

- [ ] **Step 3.5: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/ChatImageCompressor.kt
git commit -m "feat(chat): hand decoded thumbnail Bitmap off to SelectedChatImage"
```

---

## Task 4: Inject `ChatImageMemoryPreloader` into `ChatViewModel` and call it at send time

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`

- [ ] **Step 4.1: Add the constructor parameter**

In `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`, find the `ChatViewModel(...)` primary constructor at lines 55-67. Add a new parameter `imagePreloader` after `imageResolver`. Replace lines 55-67 with:

```kotlin
class ChatViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val profileRepository: ProfileRepository,
    private val groupRepository: GroupRepository? = null,
    initialPeerId: String = "",
    private val imageUploadApi: ImageUploadApi = DisabledImageUploadApi,
    private val imageResolver: SelectedChatImageResolver = ChatImageCompressor,
    private val imagePreloader: com.buyansong.im.message.ChatImageMemoryPreloader =
        com.buyansong.im.message.NoopChatImageMemoryPreloader,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
```

(Using a fully-qualified type for the new parameter avoids touching the import block in this micro-step; the next step adds the import.)

- [ ] **Step 4.2: Add imports**

In the import block at the top of `ChatViewModel.kt` (alongside the other `com.buyansong.im.message.*` imports around lines 7-13), add:

```kotlin
import com.buyansong.im.message.ChatImageMemoryPreloader
import com.buyansong.im.message.NoopChatImageMemoryPreloader
```

Then replace `com.buyansong.im.message.ChatImageMemoryPreloader` and `com.buyansong.im.message.NoopChatImageMemoryPreloader` in the constructor with the unqualified `ChatImageMemoryPreloader` / `NoopChatImageMemoryPreloader`.

- [ ] **Step 4.3: Add a private `preloadThumbnails` helper**

Below `clearErrorMessage()` (around line 337) and above `uploadImageAndQueueSend` (around line 339), insert:

```kotlin
    private fun preloadThumbnails(selectedImages: List<SelectedChatImage>) {
        selectedImages.forEach { image ->
            val key = image.localThumbnailPath
            if (key.isNotBlank()) {
                imagePreloader.preload(
                    cacheKey = key,
                    bitmap = image.thumbnailBitmap,
                    bytes = image.thumbnailBytes
                )
            }
        }
    }
```

This is a single seam used by both batch and single-image paths so the preload behavior cannot diverge.

- [ ] **Step 4.4: Call `preloadThumbnails` at the top of `sendImages` (batch)**

In `sendImages` (lines 250-297), insert the preload call inside the existing `withContext(dispatcher)` block at line 267, **before** `repository.createLocalImageMessages(...)`. Replace lines 265-275 with:

```kotlin
        // Phase 1+2: serialize id allocation + batch insert on the IO dispatcher,
        // then trigger a single refresh so all N UPLOADING rows are visible at once.
        val inserted: List<ChatMessage> = withContext(dispatcher) {
            // Pre-warm Coil's memory cache with the already-decoded thumbnail
            // bitmaps before any row is committed. By the time
            // refreshKeepingHistory() emits a new state below, the bubbles'
            // first composition will hit the cache and skip the gray-placeholder
            // flash entirely.
            preloadThumbnails(ordered)
            repository.createLocalImageMessages(
                senderId = session.userId,
                receiverId = receiverId,
                groupId = groupId,
                selectedImages = ordered,
                nowBase = batchNow
            )
        }
```

- [ ] **Step 4.5: Call `preloadThumbnails` at the top of `sendImage` (single, legacy)**

In `sendImage` (lines 215-248), insert the preload at the top of the `withContext(dispatcher)` block. Replace lines 219-244 with:

```kotlin
        withContext(dispatcher) {
            preloadThumbnails(listOf(selectedImage))
            val targetId = mutableState.value.peerId
            val localMessage = if (targetId.isGroupConversationId()) {
                repository.createLocalGroupImageMessage(
                    senderId = session.userId,
                    groupId = targetId.removePrefix("group:"),
                    localOriginalPath = selectedImage.localOriginalPath,
                    localThumbnailPath = selectedImage.localThumbnailPath,
                    imageWidth = selectedImage.width,
                    imageHeight = selectedImage.height,
                    mimeType = selectedImage.mimeType,
                    now = now
                )
            } else {
                repository.createLocalImageMessage(
                    senderId = session.userId,
                    receiverId = targetId,
                    localOriginalPath = selectedImage.localOriginalPath,
                    localThumbnailPath = selectedImage.localThumbnailPath,
                    imageWidth = selectedImage.width,
                    imageHeight = selectedImage.height,
                    mimeType = selectedImage.mimeType,
                    now = now
                )
            }
            refreshKeepingHistory()

            uploadImageAndQueueSend(localMessage.messageId, selectedImage)
        }
```

- [ ] **Step 4.6: Call `preloadThumbnails` in the `retryImageMessage` UPLOAD_FAILED branch**

In `retryImageMessage` (lines 299-324), in the `MessageStatus.UPLOAD_FAILED` branch, insert the preload call after `selectedImage` is resolved and before `markImageUploading`. Replace lines 303-312 with:

```kotlin
                MessageStatus.UPLOAD_FAILED -> {
                    val selectedImage = imageResolver.resolve(message)
                    if (selectedImage == null) {
                        mutableState.value = mutableState.value.copy(errorMessage = "Image retry failed: local image cache missing")
                        return@withContext
                    }
                    preloadThumbnails(listOf(selectedImage))
                    repository.markImageUploading(messageId, now)
                    refreshKeepingHistory()
                    uploadImageAndQueueSend(messageId, selectedImage)
                }
```

(The `FAILED` branch — upload already succeeded, only the IM send failed — does not need preload; the original local thumbnail file is still on disk and the bubble was already cached during the original `sendImages`/`sendImage` call. If the user killed and restarted the app between the original send and the retry, the `LaunchedEffect(message.localThumbnailPath)` in `ChatImageBubble.kt:50-59` still warms the cache on bubble composition as the cold-path fallback.)

- [ ] **Step 4.7: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.8: Commit**

```bash
git add app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt
git commit -m "feat(chat): pre-warm Coil cache before image bubble enters chat state"
```

---

## Task 5: Tests — preload happens before insert, for every send path

**Files:**
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt`

Background: `ChatViewModelTest` already builds a `ChatViewModel(...)` via a helper (likely `buildViewModel`/`createViewModel`/inline) and exercises `sendImages` etc. We add a `FakeChatImageMemoryPreloader` to every test that constructs the ViewModel and assert ordering.

- [ ] **Step 5.1: Locate the ViewModel construction site(s) in the test file**

Run: `grep -n "ChatViewModel(" app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt`

For each site that constructs `ChatViewModel(...)`, capture the current named-arg list. Confirm none currently pass `imagePreloader`; the new constructor parameter defaults to `NoopChatImageMemoryPreloader`, so existing tests still compile without changes.

- [ ] **Step 5.2: Write the failing batch-preload-ordering test**

Add to `ChatViewModelTest.kt`:

```kotlin
    @Test
    fun sendImagesPreloadsAllThumbnailsBeforeInsertingRows() = runTest {
        val preloader = FakeChatImageMemoryPreloader()
        val recordingRepository = RecordingMessageRepository(messageRepository)
        val viewModel = buildViewModelForImages(
            repository = recordingRepository,
            imagePreloader = preloader
        )
        viewModel.selectPeer("peer-9001")
        runCurrent()

        val images = listOf(
            fakeSelectedImage(path = "/cache/thumb-a.jpg", order = 0),
            fakeSelectedImage(path = "/cache/thumb-b.jpg", order = 1),
            fakeSelectedImage(path = "/cache/thumb-c.jpg", order = 2),
        )
        viewModel.sendImages(images)
        runCurrent()

        assertEquals(
            listOf("/cache/thumb-a.jpg", "/cache/thumb-b.jpg", "/cache/thumb-c.jpg"),
            preloader.preloadedKeys
        )
        assertTrue(
            "preload must happen before createLocalImageMessages",
            recordingRepository.firstCreateLocalImagesAt > preloader.lastPreloadAt
        )
    }
```

Supporting test infrastructure (add to the same file in a `// region image preload helpers` section):

```kotlin
    // region image preload helpers

    private fun fakeSelectedImage(path: String, order: Int): SelectedChatImage {
        return SelectedChatImage(
            originalBytes = byteArrayOf(0x01, 0x02, 0x03),
            thumbnailBytes = byteArrayOf(0x04, 0x05),
            localOriginalPath = path.replace("thumb", "origin"),
            localThumbnailPath = path,
            width = 480,
            height = 480,
            mimeType = "image/jpeg",
            selectionOrder = order,
            thumbnailBitmap = null,
        )
    }

    private class RecordingMessageRepository(
        private val delegate: MessageRepository
    ) : MessageRepository by delegate {
        @Volatile var firstCreateLocalImagesAt: Long = Long.MAX_VALUE
            private set

        override fun createLocalImageMessages(
            senderId: String,
            receiverId: String?,
            groupId: String?,
            selectedImages: List<SelectedChatImage>,
            nowBase: Long
        ): List<ChatMessage> {
            firstCreateLocalImagesAt = monotonicTickerNow()
            return delegate.createLocalImageMessages(
                senderId = senderId,
                receiverId = receiverId,
                groupId = groupId,
                selectedImages = selectedImages,
                nowBase = nowBase
            )
        }
    }

    private fun buildViewModelForImages(
        repository: MessageRepository,
        imagePreloader: ChatImageMemoryPreloader
    ): ChatViewModel { /* mirror the existing buildViewModel helper, plus
        imagePreloader = imagePreloader, plus repository override */ }

    // endregion
```

Also extend `FakeChatImageMemoryPreloader` (created in Task 1) to track call time:

```kotlin
    @Volatile var lastPreloadAt: Long = 0L
        private set

    override fun preload(cacheKey: String, bitmap: Bitmap?, bytes: ByteArray) {
        mutablePreloadedKeys += cacheKey
        lastPreloadAt = monotonicTickerNow()
    }
```

And add at file scope (in both production-test packages that need it) a tiny ticker:

```kotlin
internal fun monotonicTickerNow(): Long = System.nanoTime()
```

(Both `FakeChatImageMemoryPreloader` and `RecordingMessageRepository` need the same ticker source. `System.nanoTime()` is acceptable in tests; it does not depend on `Date.now()` semantics and is monotonic per process.)

Note on `buildViewModelForImages`: the existing `ChatViewModelTest` already builds a `ChatViewModel` for image tests — locate that helper (likely named `buildViewModel`, `createViewModel`, or an inline construction) and either add `imagePreloader` as an optional parameter there, or duplicate its body verbatim with the two extra wiring lines. Do not write `// like existing buildViewModel` — paste the full code.

- [ ] **Step 5.3: Run the new test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest.sendImagesPreloadsAllThumbnailsBeforeInsertingRows`
Expected: FAIL — `preloader.preloadedKeys` is empty before Task 4 is applied, or the assertion order check fails.

- [ ] **Step 5.4: Confirm it now passes after Task 4**

Tasks 1-4 should already be committed. Re-run:

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest.sendImagesPreloadsAllThumbnailsBeforeInsertingRows`
Expected: PASS.

- [ ] **Step 5.5: Add the single-image preload test**

Add to `ChatViewModelTest.kt`:

```kotlin
    @Test
    fun sendImagePreloadsThumbnailBeforeInsertingRow() = runTest {
        val preloader = FakeChatImageMemoryPreloader()
        val viewModel = buildViewModelForImages(
            repository = messageRepository,
            imagePreloader = preloader
        )
        viewModel.selectPeer("peer-9002")
        runCurrent()

        viewModel.sendImage(fakeSelectedImage(path = "/cache/thumb-single.jpg", order = 0))
        runCurrent()

        assertEquals(listOf("/cache/thumb-single.jpg"), preloader.preloadedKeys)
    }
```

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest.sendImagePreloadsThumbnailBeforeInsertingRow`
Expected: PASS.

- [ ] **Step 5.6: Add the retry preload test**

Add to `ChatViewModelTest.kt`:

```kotlin
    @Test
    fun retryImageMessagePreloadsThumbnailWhenUploadFailedBranchRuns() = runTest {
        val preloader = FakeChatImageMemoryPreloader()
        val resolver = object : SelectedChatImageResolver {
            override suspend fun resolve(message: ChatMessage): SelectedChatImage? {
                return fakeSelectedImage(path = message.localThumbnailPath ?: "/cache/thumb-retry.jpg", order = 0)
            }
        }
        val viewModel = buildViewModelForImages(
            repository = messageRepository,
            imagePreloader = preloader,
            imageResolver = resolver
        )
        viewModel.selectPeer("peer-9003")
        runCurrent()

        // Seed: insert an UPLOAD_FAILED image row directly via the repository
        // (mirrors the standard test pattern in this file for seeding rows).
        val failedRow = seedUploadFailedImageRow(
            conversationId = "peer-9003",
            localThumbnailPath = "/cache/thumb-retry.jpg"
        )
        runCurrent()
        preloader.reset() // clear any prior calls from seeding

        viewModel.retryImageMessage(failedRow.messageId)
        runCurrent()

        assertEquals(listOf("/cache/thumb-retry.jpg"), preloader.preloadedKeys)
    }
```

This requires `FakeChatImageMemoryPreloader.reset()`:

```kotlin
    fun reset() {
        mutablePreloadedKeys.clear()
        lastPreloadAt = 0L
    }
```

And `seedUploadFailedImageRow(conversationId, localThumbnailPath)`: a helper that calls the same DAO/Repository methods the test file already uses to seed `UPLOAD_FAILED` rows for the existing retry tests (e.g., `sendImagesContinuesAfterOneUploadFailure`). Locate the existing seed pattern in `ChatViewModelTest.kt` (search for `UPLOAD_FAILED`) and lift it into a helper. Do not stub — use the same repository write path the existing retry tests use.

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest.retryImageMessagePreloadsThumbnailWhenUploadFailedBranchRuns`
Expected: PASS.

- [ ] **Step 5.7: Run the full ViewModel test suite to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest`
Expected: All tests pass, including pre-existing ones (`sendImagesContinuesAfterOneUploadFailure`, `sendImagesOrdersBySelectionOrderNotInputOrder`, `sendImagesLimitsBatchToNineImages`, `sendImagesKeepsSelectionOrderWhenUploadsFinishOutOfOrder`, etc.) since the new constructor parameter defaults to `NoopChatImageMemoryPreloader`.

- [ ] **Step 5.8: Commit**

```bash
git add app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt \
        app/src/test/java/com/buyansong/im/message/FakeChatImageMemoryPreloader.kt
git commit -m "test(chat): cover preload-before-insert for sendImages/sendImage/retry"
```

---

## Task 6: Drop receiver-side `thumbnailUrl` fallback in `ChatImageBubble`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt`

- [ ] **Step 6.1: Replace the `model` expression to remove the network fallback**

In `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt`, replace line 39:

```kotlin
    val model = message.localThumbnailPath ?: message.thumbnailUrl
```

with:

```kotlin
    // Intentionally do NOT fall back to message.thumbnailUrl. For INCOMING
    // image messages the gray placeholder Box behind this composable is the
    // correct "still downloading" affordance — ThumbnailDownloadScheduler
    // populates message.localThumbnailPath via updateLocalThumbnailPath once
    // the file lands on disk, which triggers a recomposition that renders
    // from local. For OUTGOING messages localThumbnailPath is always set at
    // creation time by ChatImageCompressor.
    val model = message.localThumbnailPath
```

The rest of the file (`Box(...) { ... }` with `Color(0xFFE5E2E1)` background, `LaunchedEffect(message.localThumbnailPath)` preload, the `if (model != null) SubcomposeAsyncImage(...)` branch, and the status overlay) stays exactly the same.

- [ ] **Step 6.2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6.3: Manual sanity check via existing tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All tests pass. `ChatImageBubble` has no unit tests today (codegraph confirms `no covering tests found`); the change does not affect any test fixture.

- [ ] **Step 6.4: Commit**

```bash
git add app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt
git commit -m "feat(chat): keep gray placeholder for incoming images until local thumb downloads"
```

---

## Task 7: Wire `CoilImageMemoryPreloader` in `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`

- [ ] **Step 7.1: Add the import and construct the preloader**

In `app/src/main/java/com/buyansong/im/MainActivity.kt`, in the import block (next to line 92 `import com.buyansong.im.message.AndroidChatThumbnailCache`), add:

```kotlin
import com.buyansong.im.message.ChatImageMemoryPreloader
import com.buyansong.im.message.CoilImageMemoryPreloader
```

Then in `onCreate(...)` (line 144 area), insert immediately after `val thumbnailCache = AndroidChatThumbnailCache(this)`:

```kotlin
        val thumbnailCache = AndroidChatThumbnailCache(this)
        val imagePreloader: ChatImageMemoryPreloader = CoilImageMemoryPreloader(applicationContext)
```

And extend the `SelfHostedImApp(...)` call below it (currently `loginViewModel = …, validSessionProvider = …, connection = …, httpBaseUrl = …, thumbnailCache = thumbnailCache`) with:

```kotlin
        setContent {
            val loginViewModel = remember { LoginViewModel(repository) }
            SelfHostedImApp(
                loginViewModel = loginViewModel,
                validSessionProvider = repository::ensureValidSession,
                connection = connection,
                httpBaseUrl = mockServerConfig.httpBaseUrl,
                thumbnailCache = thumbnailCache,
                imagePreloader = imagePreloader,
            )
        }
```

- [ ] **Step 7.2: Add `imagePreloader` to `SelfHostedImApp`'s signature**

Find the `SelfHostedImApp(...)` composable at line 215. Add a parameter (mirroring `thumbnailCache` at line 221):

```kotlin
fun SelfHostedImApp(
    loginViewModel: LoginViewModel? = null,
    validSessionProvider: ValidSessionProvider? = null,
    connection: ImConnection? = null,
    httpBaseUrl: String? = null,
    thumbnailCache: AndroidChatThumbnailCache? = null,
    imagePreloader: ChatImageMemoryPreloader? = null,
)
```

- [ ] **Step 7.3: Thread `imagePreloader` down to the Chat composable**

The current `thumbnailCache` is already passed down the composable tree (referenced at line 313). Mirror the same threading for `imagePreloader`. Specifically, locate the inner composable that constructs `ChatViewModel(...)` at line 650. Add the parameter to the enclosing composable (search bottom-up from line 650 to find the parent that has `thumbnailCache: AndroidChatThumbnailCache` in its signature) and the call sites that lead to it; for every such composable, mirror the existing `thumbnailCache` plumbing line-for-line for `imagePreloader: ChatImageMemoryPreloader?`.

Then at the `ChatViewModel(...)` construction site (line 650), pass it:

```kotlin
                val chatViewModel = remember(session.userId, conversationId) {
                    ChatViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        profileRepository = profileRepository,
                        groupRepository = groupRepository,
                        initialPeerId = peerUserId,
                        imageUploadApi = imageUploadApi,
                        imagePreloader = imagePreloader
                            ?: com.buyansong.im.message.NoopChatImageMemoryPreloader,
                        validSessionProvider = validSessionProvider
                    )
                }
```

The `?:` keeps `SelfHostedImApp` callable from `@Preview` composables (which pass `null` for `imagePreloader`) without breaking preview rendering.

- [ ] **Step 7.4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If any intermediate composable signature was missed, the compile error will pinpoint it; add the parameter there and re-compile.

- [ ] **Step 7.5: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7.6: Commit**

```bash
git add app/src/main/java/com/buyansong/im/MainActivity.kt
git commit -m "feat(chat): wire CoilImageMemoryPreloader into MainActivity"
```

---

## Task 8: Manual emulator verification

**Files:** none (verification only).

This step does not modify source code. It confirms the user-facing behavior end-to-end.

- [ ] **Step 8.1: Install the debug APK on an emulator or a physical device**

Run: `./gradlew :app:installDebug`
Expected: APK installs successfully.

- [ ] **Step 8.2: Outgoing — single image, fast network**

Open any 1:1 chat. Tap the "+" composer button, tap the photo icon, pick 1 image, tap Send.

Expected:
- The album sheet dismisses. The bubble appears with the thumbnail **immediately visible** (no perceptible gray frame).
- A `CircularProgressIndicator` is visible to the **left** of the bubble for the brief period until SENT.
- The spinner replaces with the existing check mark on ACK.

- [ ] **Step 8.3: Outgoing — 9 images, throttled network**

In the emulator extended controls, set network speed to 3G or worse. Open the album, select 9 images, tap Send.

Expected:
- All 9 bubbles appear in the chat list within the same frame and **all of them show their thumbnail immediately**. There is no gray-flash phase visible to the naked eye.
- Each bubble shows its own left-side `CircularProgressIndicator`.
- Spinners disappear one by one in completion order as each upload + ACK lands.
- No regression on selection-order in the final visual layout — bubbles remain in selection order top-to-bottom from oldest to newest (still using the existing two-step phase-3 send pipeline).

- [ ] **Step 8.4: Outgoing — retry after `UPLOAD_FAILED`**

While Step 8.3 is in-flight, force one upload to fail (revoke the OSS URL or kill the network for ~5s then restore). After the red "!" appears, tap it.

Expected:
- The bubble's thumbnail is still visible (no gray flash on retry; `imageResolver.resolve` decoded and preloaded the bitmap before `markImageUploading`).
- The left-side spinner returns; on success it becomes the check mark.

- [ ] **Step 8.5: Incoming — receive an image from peer**

From a second account (or via `mock-test/seed_local_messages.py`), have peer A send an image to the current user B.

Expected:
- The incoming bubble appears with the gray placeholder (`Color(0xFFE5E2E1)`) **only**.
- Coil makes no network request for `thumbnailUrl` from inside the bubble — verify via `adb shell` `dumpsys connectivity` or Chrome dev-tools' equivalent on `mock-server` logs.
- `ThumbnailDownloadScheduler` triggers `AndroidChatThumbnailCache.cacheThumbnail`, the file lands on disk, `updateLocalThumbnailPath` writes the path, and the bubble re-renders showing the thumbnail.
- No "gray → blurry network-rendered thumbnail → sharp local thumbnail" double-paint is visible.

- [ ] **Step 8.6: Incoming — open chat with historic incoming images that still lack `localThumbnailPath`**

Backfill: enter a chat that previously contained incoming image messages received before this change (or wipe `local_thumbnail_path` for a row via `adb shell run-as com.buyansong.im sqlite3 databases/im.db "UPDATE messages SET local_thumbnail_path = NULL WHERE message_id = '<id>'"`).

Expected:
- The bubble shows gray placeholder.
- `ThumbnailDownloadScheduler.scheduleMissingThumbnailRetries(immediate = true)` (called from `start()` line 106 and `selectPeer(...)` line 148) fires; once downloaded, the bubble re-renders.

- [ ] **Step 8.7: Cold-path fallback — kill app mid-upload, reopen**

Send 3 images, kill the app while the spinner is still visible on at least one. Reopen the chat.

Expected:
- The remaining UPLOADING bubbles still show their thumbnails (since `LaunchedEffect(message.localThumbnailPath)` in `ChatImageBubble.kt:50-59` re-warms Coil's cache on bubble composition).
- A brief gray flash on cold-open is acceptable here and out of scope for this change; the user-facing complaint targeted the hot send path which is now fixed.

---

## Reused functions and utilities

- `Coil.imageLoader(context).memoryCache` — Coil 2.7.0 in-memory LRU bitmap cache. Already used in read mode by `ChatImageBubble.LaunchedEffect`; `CoilImageMemoryPreloader` writes to it.
- `MemoryCache.Key(path)` / `MemoryCache.Value(bitmap)` — Coil 2 cache key/value wrappers.
- `ChatImageCompressor.scaleToThumbnail` / `compress` / `writeCacheFile` — unchanged internal helpers.
- `MessageRepository.createLocalImageMessages` — unchanged batch insert that the preload runs in front of.
- `MessageRepository.markImageUploading` — unchanged transition used in the retry path.
- `SelectedChatImageResolver.resolve` — extended to also decode the thumbnail `Bitmap` for the retry path; the interface signature is unchanged.
- `ChatImageBubble.LaunchedEffect(localThumbnailPath)` — kept as cold-path fallback (cache warm on bubble first-composition after process restart).
- `OutgoingMessageStatus` (`ChatScreen.kt` lines 905-945) — unchanged spinner-on-left for `UPLOADING` / `SENDING`.
- `ChatImageBubbleLoadingPolicy.showInlineProgress()` / `showBubbleStatusProgress()` — unchanged (`false`).

## Risks and trade-offs

- **Extra memory held by `SelectedChatImage.thumbnailBitmap`** until the preload completes and Coil's LRU evicts. For 9 × 480×480 ARGB_8888 bitmaps ≈ 8 MB peak. This is well within mobile heap budgets and is released exactly the same way Coil's existing cache entries are released. If a future profile spike pinpoints this, switching to `RGB_565` for the thumbnail decode would halve memory; out of scope here.
- **`source.recycle()` interaction.** When `scaleToThumbnail` returns `source` directly (thumbnail size ≥ original), the bitmap is owned by the outer `try/finally` that recycles `source`. We deliberately set `thumbnailBitmap = null` in that case so the preloader falls back to bytes decoding and we do not hand the about-to-be-recycled bitmap to Coil. This avoids "RuntimeException: trying to use a recycled bitmap".
- **`SelectedChatImage.equals/hashCode` manual override.** Required because the auto-generated data-class implementations would include `thumbnailBitmap` and break existing fixtures. We preserve the existing field-by-field comparison semantics; new tests should not compare bitmaps for equality.
- **Coil's `MemoryCache` is per `ImageLoader`.** `Coil.imageLoader(context)` returns the app-singleton loader (since no custom `ImageLoaderFactory` is registered in this project; verify by grepping `setImageLoader` / `ImageLoaderFactory` in `app/src/main/`). Preloads and reads both target this single instance.
- **Coil pinning behavior**: `MemoryCache.Value(bitmap)` stores a strong reference; LRU eviction can drop it any time. Worst case after preload, the cache is evicted before the bubble composes (extremely fast send path → not realistic), and `SubcomposeAsyncImage` falls through to disk decode. The user still sees the gray flash in that pathological case; for the realistic flow the cache is hit.
- **Receiver gray placeholder duration depends on `ThumbnailDownloadScheduler` latency.** On slow networks the user may stare at a gray box for several seconds. This is the explicit, requested behavior (no half-loaded network image in the bubble); UX trade-off is accepted.
- **`NoopChatImageMemoryPreloader` is the default constructor argument.** Any future test or preview composable that constructs `ChatViewModel` directly continues to work; only production callers (`MainActivity`) opt into `CoilImageMemoryPreloader`.

## Explicitly out of scope

- Receiver-side download retry policy (`ThumbnailDownloadScheduler`, `THUMBNAIL_RETRY_DELAYS_MS` in `ChatViewModel.kt:728`) — unchanged.
- `AndroidChatThumbnailCache` — unchanged.
- `ChatImagePreviewScreen` (full-resolution preview) — unchanged. Preview still loads from `imageUrl` if `localOriginalPath` is missing.
- `MessageRepository.createLocalImageMessages` / `createLocalImageMessage` / `createLocalGroupImageMessage` — unchanged.
- `OutgoingMessageStatus` — unchanged.
- `ChatImageBubbleLoadingPolicy` — unchanged.
- `MAX_IMAGES_PER_SEND = 9` — unchanged.
- Generator thread-safety (`MessageIdGenerator`, `SeqGenerator`) — unchanged (already addressed in plan `2026-06-06-multi-image-parallel-uploads.md`).
- Switching thumbnail decoding to `RGB_565` for memory savings — deferred.
- iOS / server / web parity — out of scope.

## Implementation order (single session, ~2-3 hours)

1. Task 1 — `ChatImageMemoryPreloader` interface, Noop, Coil impl, and `FakeChatImageMemoryPreloader`.
2. Task 2 — extend `SelectedChatImage` with `thumbnailBitmap`.
3. Task 3 — stop recycling thumbnail in `ChatImageCompressor`.
4. Task 4 — call `preloadThumbnails` in `ChatViewModel.sendImages` / `sendImage` / `retryImageMessage`.
5. Task 5 — add the three ordering tests + run the full ViewModel suite.
6. Task 6 — drop the `?: message.thumbnailUrl` fallback in `ChatImageBubble`.
7. Task 7 — wire `CoilImageMemoryPreloader` through `MainActivity` and `SelfHostedImApp`.
8. Task 8 — manual emulator pass (outgoing fast, outgoing throttled, retry, incoming fresh, incoming backfill, cold-path).
