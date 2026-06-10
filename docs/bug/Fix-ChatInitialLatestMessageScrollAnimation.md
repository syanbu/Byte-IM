# Bug Plan: Chat Initial Latest Message Scroll Animation

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opening a single chat or group chat from the conversation list should land directly on the latest loaded message without visibly animating from the oldest image/message to the newest one.

**Architecture:** Keep the current shared `ChatScreen` path for single and group conversations, but split chat auto-scroll into two explicit behaviors: initial history anchoring uses pre-measure `requestScrollToItem`, while later latest-message changes may keep using `animateScrollToItem`. The fix belongs in `ChatAutoScrollPolicy` plus the `ChatScreen` effect that consumes it, so the same behavior applies to both `single:*` and `group:*` conversations.

**Tech Stack:** Android Kotlin, Jetpack Compose `LazyColumn`, Kotlin/JVM unit tests.

---

## Status

- Status: Implemented; automated verification passed; manual device verification pending
- Created on: 2026-06-10
- Implemented on: 2026-06-10
- Branch: current working branch
- Scope: shared chat message list used by both single chat and group chat.

## Observed Symptoms

When the peer sends 9 image messages while user A is outside the chat:

1. User A opens the conversation from the conversation list.
2. The chat screen first renders at the oldest loaded message, visually showing the first image.
3. The screen then automatically slides down to the latest loaded message, visually ending at the ninth image.

Expected behavior:

- Opening the chat should immediately land on the latest loaded message.
- The initial landing should not look like an animated scroll through the image sequence.
- The behavior should be identical for single chat and group chat, because both use the same `ChatScreen`.

## Pre-Fix Code Evidence

- `app/src/main/java/com/buyansong/im/MainActivity.kt`
  - Conversation list opens `SelfHostedImRoute.Chat.createRoute(conversationId)`.
  - The `Chat` destination creates one `ChatViewModel` and renders one shared `ChatScreen`.
  - `initialPeerId` is either a single-chat peer id or the original `group:*` conversation id.

- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`
  - `refreshInitialPage()` loads the latest local history page.
  - `historyPage(...)` returns `MessageOrderingPolicy.sortOldestFirst(page)`.
  - Therefore `state.messages.first()` is the oldest loaded message and `state.messages.last()` is the newest loaded message.

- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
  - `LazyColumn(reverseLayout = false)` renders item `0` at the visual top.
  - `latestMessageId = state.messages.lastOrNull()?.messageId`.
  - `previousLatestMessageId` starts as `null`.
  - `LaunchedEffect(latestMessageId, state.messages.size)` calls `listState.animateScrollToItem(...)` when `ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId, latestMessageId)` returns `true`.

- `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
  - `shouldScrollToLatest(previousLatestMessageId = null, latestMessageId = "msg-9")` currently returns `true`.
  - `scrollToLatestIndex(9)` returns `8`.

This means the first non-empty history load is treated the same as a new incoming latest message. The UI starts from the default list position (`index = 0`, the first image), then animates to `index = 8` (the ninth image).

## Root Cause

The root cause is not image ordering and not group-specific navigation.

The root cause is that `ChatScreen` has only one "scroll to latest" path:

- Initial history load: `previousLatestMessageId == null`, `latestMessageId == newestLoadedMessageId`.
- Later new message arrival: `previousLatestMessageId == oldNewestMessageId`, `latestMessageId == newNewestMessageId`.

Both cases currently call `animateScrollToItem`. Initial history load should be a silent anchor to the latest loaded message, while later message arrival may be an animated scroll.

## Follow-Up Finding From Manual Verification

The first implementation changed the initial path from `animateScrollToItem(...)` to suspend `scrollToItem(...)`. That removed the visible smooth animation but did not remove the first wrong frame: `LaunchedEffect` still runs after the first composition/frame, so the `LazyColumn` can briefly paint its default position at `index = 0` before the non-animated jump to the latest item.

The durable UI fix is therefore not "scroll faster after first paint". The initial anchor must be requested before list measurement/draw. Compose foundation 1.7.5 provides `LazyListState.requestScrollToItem(index, offset)`, which requests the target item for the next remeasure and avoids the post-frame correction path.

## Non-Goals

- Do not change message ordering in `MessageOrderingPolicy`.
- Do not change multi-image send order.
- Do not split single-chat and group-chat UI paths.
- Do not add a "scroll only if user is near bottom" behavior in this pass. That is a separate UX decision and should not be bundled with this bug fix.

## Implementation Plan (Executed)

### Task 1: Add an Explicit Chat Scroll Action Policy

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [x] **Step 1: Write failing tests for initial anchor vs later animation**

Add tests to `ChatAutoScrollPolicyTest`:

```kotlin
@Test
fun scrollAction_noLatest_returnsNone() {
    assertEquals(
        ChatAutoScrollPolicy.ScrollAction.NONE,
        ChatAutoScrollPolicy.scrollAction(
            previousLatestMessageId = null,
            latestMessageId = null
        )
    )
}

@Test
fun scrollAction_firstLoadedLatest_returnsPreMeasureAnchor() {
    assertEquals(
        ChatAutoScrollPolicy.ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST,
        ChatAutoScrollPolicy.scrollAction(
            previousLatestMessageId = null,
            latestMessageId = "msg-9"
        )
    )
}

@Test
fun scrollAction_sameLatest_returnsNone() {
    assertEquals(
        ChatAutoScrollPolicy.ScrollAction.NONE,
        ChatAutoScrollPolicy.scrollAction(
            previousLatestMessageId = "msg-9",
            latestMessageId = "msg-9"
        )
    )
}

@Test
fun scrollAction_latestChangedAfterInitialLoad_returnsAnimate() {
    assertEquals(
        ChatAutoScrollPolicy.ScrollAction.ANIMATE_TO_LATEST,
        ChatAutoScrollPolicy.scrollAction(
            previousLatestMessageId = "msg-8",
            latestMessageId = "msg-9"
        )
    )
}
```

Update or remove the old assertion that `shouldScrollToLatest(null, "msg-1")` returns `true`, because that behavior is the bug.

- [x] **Step 2: Run the failing policy test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected before implementation:

- The new tests fail because `ScrollAction` and `scrollAction(...)` do not exist yet.

- [x] **Step 3: Implement the scroll action policy**

Update `ChatAutoScrollPolicy.kt`:

```kotlin
object ChatAutoScrollPolicy {
    private const val LOAD_EARLIER_THRESHOLD_ITEMS = 6
    private const val MAX_RETAINED_MESSAGES = 2_000

    enum class ScrollAction {
        NONE,
        PRE_MEASURE_ANCHOR_TO_LATEST,
        ANIMATE_TO_LATEST
    }

    fun scrollAction(previousLatestMessageId: String?, latestMessageId: String?): ScrollAction {
        return when {
            latestMessageId == null -> ScrollAction.NONE
            previousLatestMessageId == null -> ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST
            previousLatestMessageId == latestMessageId -> ScrollAction.NONE
            else -> ScrollAction.ANIMATE_TO_LATEST
        }
    }

    fun shouldScrollToLatest(previousLatestMessageId: String?, latestMessageId: String?): Boolean {
        return scrollAction(previousLatestMessageId, latestMessageId) != ScrollAction.NONE
    }

    fun scrollToLatestIndex(messageCount: Int): Int {
        if (messageCount <= 0) return 0
        return messageCount - 1
    }

    fun shouldLoadEarlierHistory(
        visibleMaxIndex: Int,
        messageCount: Int,
        hasMoreLocal: Boolean,
        isLoadingMore: Boolean
    ): Boolean {
        if (messageCount == 0 || messageCount >= MAX_RETAINED_MESSAGES || !hasMoreLocal || isLoadingMore) {
            return false
        }
        val triggerIndex = maxOf(0, messageCount - LOAD_EARLIER_THRESHOLD_ITEMS)
        return visibleMaxIndex >= triggerIndex
    }
}
```

Keeping `shouldScrollToLatest(...)` as a compatibility wrapper is acceptable if no call sites outside `ChatScreen` need to know the difference. The important new contract is `scrollAction(...)`.

- [x] **Step 4: Run the policy test until it passes**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected after implementation:

- `BUILD SUCCESSFUL`.

### Task 2: Use Pre-Measure Initial Anchoring in ChatScreen

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- Test: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [x] **Step 1: Replace the single animate path in `ChatScreen`**

Update the current effect:

```kotlin
LaunchedEffect(latestMessageId, state.messages.size) {
    if (ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId, latestMessageId)) {
        listState.animateScrollToItem(
            ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size)
        )
    }
    previousLatestMessageId = latestMessageId
}
```

with:

```kotlin
val latestMessageIndex = ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size)
val autoScrollAction = ChatAutoScrollPolicy.scrollAction(previousLatestMessageId, latestMessageId)
SideEffect {
    if (autoScrollAction == ChatAutoScrollPolicy.ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST) {
        listState.requestScrollToItem(latestMessageIndex)
    }
}

LaunchedEffect(latestMessageId, state.messages.size) {
    when (autoScrollAction) {
        ChatAutoScrollPolicy.ScrollAction.NONE -> Unit
        ChatAutoScrollPolicy.ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST -> Unit
        ChatAutoScrollPolicy.ScrollAction.ANIMATE_TO_LATEST -> {
            listState.animateScrollToItem(latestMessageIndex)
        }
    }
    previousLatestMessageId = latestMessageId
}
```

Why this is the minimal fix:

- Initial history load uses `requestScrollToItem`, so the list measures at the latest item instead of first painting index 0 and correcting later.
- Later latest-message changes still use `animateScrollToItem`, preserving the existing live-message behavior.
- The target index still comes from `scrollToLatestIndex(...)`, so the normal-layout list contract remains centralized.

- [x] **Step 2: Run targeted tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected:

- `BUILD SUCCESSFUL`.

- [x] **Step 3: Run the full JVM unit suite**

Run:

```bash
./gradlew :app:testDebugUnitTest --console=plain
```

Expected:

- `BUILD SUCCESSFUL`.

### Task 3: Manual Device Verification

**Files:**

- No production file changes in this task.

- [ ] **Step 1: Verify single chat with 9 incoming images**

Manual steps:

1. Use account B to send 9 image messages to account A while A is on the conversation list or outside the target chat.
2. On account A, tap the conversation row.
3. Observe the first frame and the first second after entering chat.

Expected:

- The chat opens already anchored at the latest loaded image/message.
- There is no visible animated slide from image 1 to image 9.
- The latest image is reachable as the visual bottom/newest message.

- [ ] **Step 2: Verify group chat with 9 incoming images**

Manual steps:

1. Use another group member to send 9 image messages in a group while account A is on the conversation list or outside the target chat.
2. On account A, tap the group conversation row.
3. Observe the first frame and the first second after entering chat.

Expected:

- The group chat opens directly at the latest loaded image/message.
- There is no visible animated slide from image 1 to image 9.
- Sender avatars/profiles still render normally after profile refresh completes.

- [ ] **Step 3: Verify later live-message animation still works**

Manual steps:

1. Keep account A inside the chat after the initial landing.
2. Send one more text or image message from the peer/group.

Expected:

- The newest message becomes visible at the bottom.
- If an auto-scroll occurs, it is only for this later message arrival, not for the initial history landing.

## Related Risk to Watch

`ChatAutoScrollPolicy.shouldLoadEarlierHistory(...)` currently triggers from the high visible index side of the normal-layout list. Because `LazyColumn(reverseLayout = false)` means older messages are near index `0`, history pagination direction should be rechecked separately before changing it. This plan does not include that fix because the reported 9-image entry bug is caused by initial auto-scroll animation, and bundling pagination direction changes would increase regression risk.

## Implementation Result

Implemented on 2026-06-10:

- Added `ChatAutoScrollPolicy.ScrollAction`.
- Added `ChatAutoScrollPolicy.scrollAction(...)`.
- Updated `ChatScreen` so the first loaded latest message uses `listState.requestScrollToItem(...)` from `SideEffect`.
- Kept later latest-message changes on `listState.animateScrollToItem(...)`.
- Updated `ChatAutoScrollPolicyTest` to distinguish initial history anchoring from later latest-message animation.
- Follow-up manual screenshot verification showed the first `scrollToItem(...)` fix still painted the oldest image for a few frames; the implementation was revised to pre-measure anchoring via `requestScrollToItem(...)`.

Automated verification:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:assembleDebug --console=plain
```

Result:

- `BUILD SUCCESSFUL`
- Targeted `ChatAutoScrollPolicyTest` passed all 10 tests.
- Full JVM unit suite passed.
- Debug APK build passed.

Manual verification still recommended on a device/emulator for both single chat and group chat with 9 incoming images.

## Verification Commands

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:assembleDebug --console=plain
```

## Completion Criteria

- `ChatAutoScrollPolicyTest` distinguishes first history load from later latest-message changes.
- `ChatScreen` uses `requestScrollToItem` for `PRE_MEASURE_ANCHOR_TO_LATEST`.
- `ChatScreen` uses `animateScrollToItem` only for `ANIMATE_TO_LATEST`.
- Single-chat manual verification with 9 incoming images shows no initial slide.
- Group-chat manual verification with 9 incoming images shows no initial slide.
