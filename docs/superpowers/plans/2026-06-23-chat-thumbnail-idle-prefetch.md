# Chat Thumbnail Idle Prefetch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce wasted thumbnail decode work and Coil memory-cache churn during fast chat flings by making viewport thumbnail prefetch run only after scrolling settles.

**Architecture:** Keep sender-side prewarm, receiver-side prewarm, navigation prewarm, and `ChatLocalThumbnailRequest.size(Size.ORIGINAL)` unchanged. Add a small pure policy for viewport thumbnail prefetch eligibility and path selection, then have `ChatScreen` observe `LazyListState.isScrollInProgress`; when scrolling becomes idle, debounce briefly, compute the current visible window, and prewarm a smaller nearby set.

**Tech Stack:** Kotlin, Jetpack Compose `LazyListState`, Coil Compose 2.7.0, Coroutines, JUnit4.

---

## Current Code Facts

- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` currently derives `thumbnailPrefetchPaths` directly from `LazyListState.layoutInfo.visibleItemsInfo`.
- Current viewport constants are aggressive for fling: `CHAT_THUMBNAIL_PREFETCH_MARGIN = 10`, `CHAT_THUMBNAIL_PREFETCH_MAX_IMAGES = 24`, `CHAT_THUMBNAIL_PREFETCH_CONCURRENCY = 3`, `CHAT_THUMBNAIL_PREFETCH_TIMEOUT_MS = 300L`.
- `ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(...)` already filters image messages, clamps viewport bounds, removes duplicate paths, and applies `maxImages`.
- Sender-side `prewarmOutgoingLocalThumbnails(...)` runs before `ChatViewModel.sendImages(...)`; this plan must not change it.
- Receiver-side `ThumbnailDownloadScheduler` prewarms after thumbnail cache write and before `localThumbnailPath` emission; this plan must not change it.
- `ChatLocalThumbnailRequest` currently uses `Size.ORIGINAL`; this plan intentionally does not change decode size.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/buyansong/im/chat/ChatThumbnailPrefetchPolicy.kt` | Create | Pure policy for idle-only viewport prefetch path selection. |
| `app/src/test/java/com/buyansong/im/chat/ChatThumbnailPrefetchPolicyTest.kt` | Create | Unit tests for scrolling suppression, idle selection, de-duplication, and invalid visible windows. |
| `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` | Modify | Replace eager derived prefetch with idle-debounced prefetch and conservative constants. |
| `docs/status/B11-image-message-design-status.md` | Modify | Record that viewport prefetch is now idle-only and conservative. |

## Non-Goals

- Do not change `ChatLocalThumbnailRequest.size(Size.ORIGINAL)`.
- Do not change Coil global memory-cache sizing.
- Do not change sender-side prewarm before `sendImages(...)`.
- Do not change receiver-side prewarm before `localThumbnailPath` emission.
- Do not add scroll velocity calculation in the first pass.

---

## Task 1: Add Idle Prefetch Policy

**Files:**
- Create: `app/src/main/java/com/buyansong/im/chat/ChatThumbnailPrefetchPolicy.kt`
- Create: `app/src/test/java/com/buyansong/im/chat/ChatThumbnailPrefetchPolicyTest.kt`

- [ ] **Step 1: Write failing policy tests**

Create `app/src/test/java/com/buyansong/im/chat/ChatThumbnailPrefetchPolicyTest.kt` with:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatThumbnailPrefetchPolicyTest {

    @Test
    fun pathsForIdleViewportReturnsEmptyWhileScrolling() {
        val messages = listOf(
            message("image-0", localThumbnailPath = "/cache/0.jpg"),
            message("image-1", localThumbnailPath = "/cache/1.jpg")
        )

        assertEquals(
            emptyList<String>(),
            ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = messages,
                visibleMinIndex = 0,
                visibleMaxIndex = 1,
                isScrollInProgress = true,
                alreadyPrefetchedPaths = emptySet(),
                margin = 4,
                maxImages = 10
            )
        )
    }

    @Test
    fun pathsForIdleViewportSelectsUnprefetchedLocalImagePathsWhenIdle() {
        val messages = listOf(
            message("image-0", localThumbnailPath = "/cache/0.jpg"),
            textMessage("text-1"),
            message("image-2", localThumbnailPath = "/cache/2.jpg"),
            message("image-3", localThumbnailPath = "/cache/3.jpg")
        )

        assertEquals(
            listOf("/cache/2.jpg", "/cache/3.jpg"),
            ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = messages,
                visibleMinIndex = 1,
                visibleMaxIndex = 2,
                isScrollInProgress = false,
                alreadyPrefetchedPaths = setOf("/cache/0.jpg"),
                margin = 1,
                maxImages = 10
            )
        )
    }

    @Test
    fun pathsForIdleViewportReturnsEmptyForInvalidVisibleWindow() {
        val messages = listOf(
            message("image-0", localThumbnailPath = "/cache/0.jpg")
        )

        assertEquals(
            emptyList<String>(),
            ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = messages,
                visibleMinIndex = -1,
                visibleMaxIndex = 0,
                isScrollInProgress = false,
                alreadyPrefetchedPaths = emptySet(),
                margin = 4,
                maxImages = 10
            )
        )
    }

    private fun textMessage(id: String): ChatMessage {
        return message(id, type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg")
    }

    private fun message(
        id: String,
        type: MessageType = MessageType.IMAGE,
        localThumbnailPath: String?
    ): ChatMessage {
        return ChatMessage(
            messageId = id,
            conversationId = "single:u_a:u_b",
            senderId = "u_a",
            receiverId = "u_b",
            clientSeq = 1L,
            serverSeq = 1L,
            content = id,
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = 1L,
            updatedAt = 1L,
            type = type,
            localThumbnailPath = localThumbnailPath
        )
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatThumbnailPrefetchPolicyTest
```

Expected: FAIL because `ChatThumbnailPrefetchPolicy` does not exist.

- [ ] **Step 3: Implement the policy**

Create `app/src/main/java/com/buyansong/im/chat/ChatThumbnailPrefetchPolicy.kt`:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.ChatMessage

object ChatThumbnailPrefetchPolicy {
    fun pathsForIdleViewport(
        messages: List<ChatMessage>,
        visibleMinIndex: Int,
        visibleMaxIndex: Int,
        isScrollInProgress: Boolean,
        alreadyPrefetchedPaths: Set<String>,
        margin: Int,
        maxImages: Int
    ): List<String> {
        if (isScrollInProgress) {
            return emptyList()
        }
        return ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(
            messages = messages,
            visibleMinIndex = visibleMinIndex,
            visibleMaxIndex = visibleMaxIndex,
            margin = margin,
            maxImages = maxImages
        ).filterNot { it in alreadyPrefetchedPaths }
    }
}
```

- [ ] **Step 4: Run policy tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatThumbnailPrefetchPolicyTest
```

Expected: PASS.

---

## Task 2: Make ChatScreen Prefetch Idle-Only and Conservative

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`

- [ ] **Step 1: Replace eager derived prefetch**

Remove the current `thumbnailPrefetchPaths` `derivedStateOf` block from `ChatScreen.kt`.

- [ ] **Step 2: Add idle-debounced prefetch effect**

Add this import:

```kotlin
import androidx.compose.runtime.snapshotFlow
```

After the existing `LaunchedEffect(shouldLoadEarlierHistory)` block, add:

```kotlin
LaunchedEffect(listState, state.messages, prefetchedThumbnailPaths) {
    snapshotFlow { listState.isScrollInProgress }
        .collect { isScrollInProgress ->
            if (isScrollInProgress) return@collect
            delay(CHAT_THUMBNAIL_PREFETCH_IDLE_DELAY_MS)
            val visibleMinIndex = listState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: -1
            val visibleMaxIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            val paths = ChatThumbnailPrefetchPolicy.pathsForIdleViewport(
                messages = state.messages,
                visibleMinIndex = visibleMinIndex,
                visibleMaxIndex = visibleMaxIndex,
                isScrollInProgress = listState.isScrollInProgress,
                alreadyPrefetchedPaths = prefetchedThumbnailPaths.toSet(),
                margin = CHAT_THUMBNAIL_PREFETCH_MARGIN,
                maxImages = CHAT_THUMBNAIL_PREFETCH_MAX_IMAGES
            )
            if (paths.isEmpty()) return@collect
            prefetchedThumbnailPaths += paths
            ChatInitialImagePrewarmer.prewarmLocalThumbnails(
                context = context,
                localThumbnailPaths = paths,
                timeoutMs = CHAT_THUMBNAIL_PREFETCH_TIMEOUT_MS,
                maxConcurrency = CHAT_THUMBNAIL_PREFETCH_CONCURRENCY
            )
        }
}
```

This keeps prefetch out of fling frames and re-checks `listState.isScrollInProgress` after the idle delay.

- [ ] **Step 3: Make prefetch constants conservative**

Change the constants near `CHAT_ERROR_TOAST_DURATION_MS` to:

```kotlin
private const val CHAT_THUMBNAIL_PREFETCH_MARGIN = 4
private const val CHAT_THUMBNAIL_PREFETCH_MAX_IMAGES = 10
private const val CHAT_THUMBNAIL_PREFETCH_TIMEOUT_MS = 250L
private const val CHAT_THUMBNAIL_PREFETCH_CONCURRENCY = 1
private const val CHAT_THUMBNAIL_PREFETCH_IDLE_DELAY_MS = 160L
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatThumbnailPrefetchPolicyTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest
```

Expected: PASS.

- [ ] **Step 5: Compile-check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

## Task 3: Update B11 Status Documentation

**Files:**
- Modify: `docs/status/B11-image-message-design-status.md`

- [ ] **Step 1: Update thumbnail preload strategy**

In the `Thumbnail preload strategy:` list, replace:

```markdown
- `ChatScreen` prefetches local thumbnails around the current `LazyListState` viewport so fast-scroll targets can warm before composition.
```

with:

```markdown
- `ChatScreen` prefetches local thumbnails only after `LazyListState` becomes idle, using a small viewport margin, so fling gestures do not decode large numbers of pass-through thumbnails.
```

- [ ] **Step 2: Update current risk note**

Replace the current fast-scroll risk note with:

```markdown
- Fast-scroll image smoothness and memory behavior still need emulator or device verification with a conversation containing 50-200 cached image thumbnails. Unit tests cover path/window selection and idle-only prefetch policy, but frame timing and Coil memory-cache churn must be observed on Android.
```

---

## Final Verification

- [ ] **Step 1: Run focused tests**

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatThumbnailPrefetchPolicyTest --tests com.buyansong.im.chat.ChatInitialImagePrewarmerTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest
```

Expected: PASS.

- [ ] **Step 2: Run full debug unit tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Manual emulator/device checks**

1. Open a conversation with 50-200 cached image thumbnails.
2. Fling upward and downward quickly. Expected: fling remains smooth; thumbnails that only pass through during the fling may load normally through `AsyncImage`, but no large prewarm burst should occur.
3. Stop scrolling on a section with several image messages. Expected: after a short delay, nearby thumbnails render without repeated gray flashes.
4. Send up to 9 images. Expected: outgoing image rows still use sender-side prewarm before entering UI.
5. Receive image messages. Expected: incoming images still appear only after local thumbnail cache exists and receiver-side prewarm has run.

## Self-Review Notes

- This plan addresses only fast-scroll memory/decode waste from viewport prefetch.
- Sender-side and receiver-side prewarm are intentionally preserved because they warm images immediately before UI emission.
- Decode-size optimization is deferred; `ChatLocalThumbnailRequest` continues to use `Size.ORIGINAL`.
