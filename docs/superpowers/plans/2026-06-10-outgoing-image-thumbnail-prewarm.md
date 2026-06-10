# Outgoing Image Thumbnail Prewarm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the sender-side gray placeholder flash when sending one or more local images by ensuring each outgoing image bubble's local thumbnail is already in Coil memory cache before the message rows enter chat state.

**Architecture:** Keep the existing repository, upload, retry, and message persistence flow unchanged. Add a tiny chat-local request helper that builds the exact same Coil `ImageRequest` for local thumbnail prewarm and bubble rendering, then execute those requests after `ChatImageCompressor.prepareSelectedImage(...)` finishes and before `ChatViewModel.sendImages(...)` inserts local rows. Receiver-side thumbnail downloading and hidden-until-local-cache behavior remain unchanged.

**Tech Stack:** Android Kotlin, Jetpack Compose, Coil 2.7.0, JVM unit tests, Gradle.

---

## File Structure

### New files

- `app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt`
  - Single-purpose helper for local chat thumbnail Coil requests.
  - Exposes:
    - `ChatLocalThumbnailRequest.cacheKey(localThumbnailPath: String): String?`
    - `ChatLocalThumbnailRequest.build(context: Context, localThumbnailPath: String): ImageRequest?`

- `app/src/test/java/com/buyansong/im/chat/ChatLocalThumbnailRequestTest.kt`
  - JVM tests for path normalization and request key behavior that can be asserted without rendering Compose.

### Modified files

- `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt`
  - Use `ChatLocalThumbnailRequest.build(...)` for local thumbnail models.
  - Keep the existing gray background, no inline spinner, status overlay policy, and `thumbnailUrl` fallback behavior unchanged for now.

- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
  - Add a small suspend helper that executes `ChatLocalThumbnailRequest.build(...)` for every prepared outgoing image before calling `viewModel.sendImages(preparedImages)`.
  - Keep album selection order, compression, repository insert, upload, retry, and group/single routing unchanged.

## Task 1: Add Local Thumbnail Request Policy

**Files:**
- Create: `app/src/test/java/com/buyansong/im/chat/ChatLocalThumbnailRequestTest.kt`
- Create: `app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt`

- [x] **Step 1.1: Write failing request-policy tests**

Create `app/src/test/java/com/buyansong/im/chat/ChatLocalThumbnailRequestTest.kt`:

```kotlin
package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatLocalThumbnailRequestTest {

    @Test
    fun cacheKeyReturnsTrimmedLocalThumbnailPath() {
        assertEquals(
            "/data/user/0/com.buyansong.im/cache/chat-thumb-1.jpg",
            ChatLocalThumbnailRequest.cacheKey("  /data/user/0/com.buyansong.im/cache/chat-thumb-1.jpg  ")
        )
    }

    @Test
    fun cacheKeyReturnsNullForBlankPath() {
        assertNull(ChatLocalThumbnailRequest.cacheKey(""))
        assertNull(ChatLocalThumbnailRequest.cacheKey("   "))
    }
}
```

- [x] **Step 1.2: Run the failing test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest
```

Expected: FAIL with `Unresolved reference: ChatLocalThumbnailRequest`.

- [x] **Step 1.3: Implement the minimal request helper**

Create `app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt`:

```kotlin
package com.buyansong.im.chat

import android.content.Context
import coil.request.ImageRequest
import coil.size.Size

object ChatLocalThumbnailRequest {
    fun cacheKey(localThumbnailPath: String): String? {
        return localThumbnailPath.trim().takeIf { it.isNotEmpty() }
    }

    fun build(context: Context, localThumbnailPath: String): ImageRequest? {
        val key = cacheKey(localThumbnailPath) ?: return null
        return ImageRequest.Builder(context)
            .data(key)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .size(Size.ORIGINAL)
            .build()
    }
}
```

Rationale:
- `memoryCacheKey(key)` makes the prewarm request and bubble render request target the same memory-cache entry.
- `size(Size.ORIGINAL)` prevents Coil Compose from wrapping this local-thumbnail request with a different constraint-derived size resolver.
- Local thumbnail files are already bounded by `ChatImageCompressor`; rendering them at original thumbnail size and letting Compose scale/crop inside the bubble is acceptable.

- [x] **Step 1.4: Run the request-policy test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest
```

Expected: PASS.

## Task 2: Use the Shared Request in `ChatImageBubble`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt`

- [x] **Step 2.1: Replace the local thumbnail model construction**

In `ChatImageBubble`, keep `val context = LocalContext.current`, then replace:

```kotlin
val model = message.localThumbnailPath ?: message.thumbnailUrl
```

with:

```kotlin
val localThumbnailRequest = message.localThumbnailPath?.let { path ->
    ChatLocalThumbnailRequest.build(context, path)
}
val model = localThumbnailRequest ?: message.thumbnailUrl
```

Keep the existing `LaunchedEffect(message.localThumbnailPath)` block, but replace its internal `ImageRequest.Builder(...)` construction with the shared helper:

```kotlin
LaunchedEffect(message.localThumbnailPath) {
    val path = message.localThumbnailPath ?: return@LaunchedEffect
    val request = ChatLocalThumbnailRequest.build(context, path) ?: return@LaunchedEffect
    Coil.imageLoader(context).execute(request)
}
```

Important: do not change `ChatImageBubbleLoadingPolicy`, gray background, status overlay, long-press behavior, preview click behavior, or receiver-side fallback behavior in this task.

- [x] **Step 2.2: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

## Task 3: Prewarm Prepared Outgoing Thumbnails Before Row Insert

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`

- [x] **Step 3.1: Add Coil imports**

Add these imports to `ChatScreen.kt`:

```kotlin
import coil.Coil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

`Dispatchers` and `withContext` are used only for the prewarm helper. The actual Coil request still uses Coil's own dispatchers internally, but calling it from IO keeps this pre-send step off the main dispatcher.

- [x] **Step 3.2: Add a private prewarm helper**

Add this helper near other private chat-screen helpers:

```kotlin
private suspend fun prewarmOutgoingLocalThumbnails(
    context: android.content.Context,
    images: List<com.buyansong.im.message.SelectedChatImage>
) {
    withContext(Dispatchers.IO) {
        images.forEach { image ->
            val request = ChatLocalThumbnailRequest.build(context, image.localThumbnailPath) ?: return@forEach
            Coil.imageLoader(context).execute(request)
        }
    }
}
```

This helper intentionally ignores individual preload failures. If Coil cannot decode one thumbnail, the existing bubble path still falls back to normal local-file loading and existing error UI.

- [x] **Step 3.3: Call prewarm before `sendImages`**

In `ChatScreen`'s `AlbumPickerScreen(onSendSelected = ...)` block, replace:

```kotlin
if (preparedImages.isNotEmpty()) {
    viewModel.sendImages(preparedImages)
}
```

with:

```kotlin
if (preparedImages.isNotEmpty()) {
    prewarmOutgoingLocalThumbnails(context, preparedImages)
    viewModel.sendImages(preparedImages)
}
```

The ordering matters: prewarm first, then `sendImages(...)`, because `sendImages(...)` inserts local rows and refreshes chat state.

- [x] **Step 3.4: Compile**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

## Task 4: Verification

**Files:** none.

- [x] **Step 4.1: Run targeted JVM tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatLocalThumbnailRequestTest
```

Expected: PASS.

- [x] **Step 4.2: Run full unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 4.3: Compile debug Kotlin**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4.4: Manual emulator check**

Use the app normally:

1. Open a 1:1 chat or group chat.
2. Tap `+`, choose 9 images, tap Send.
3. Confirm the image rows appear with thumbnails already visible rather than a visible gray flash.
4. Confirm the left-side outgoing spinner still appears while each image is `UPLOADING` or `SENDING`.
5. Confirm image order remains selection order.
6. Confirm incoming image behavior is unchanged: incoming images still depend on receiver-side local thumbnail caching.

## Explicitly Out of Scope

- Do not change `MessageRepository`.
- Do not change `ChatViewModel`.
- Do not change SQLite schema or storage models.
- Do not change receiver-side thumbnail hiding/filtering policy.
- Do not introduce an in-memory bitmap field into `SelectedChatImage`.
- Do not change upload, retry, ACK, or group image send behavior.
- Do not run or create git commits for this task.
