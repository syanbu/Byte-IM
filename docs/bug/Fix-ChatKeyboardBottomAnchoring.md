# Bug Plan: Chat Keyboard Bottom Anchoring

> **For agentic workers:** Implement this plan task-by-task. Do not use `git` commands while executing this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the keyboard opens in single chat or group chat, the chat timeline should shrink and keep the bottom conversation area visible above the composer, instead of looking covered by the composer and keyboard.

**Architecture:** Keep the existing keyboard strategy from `Fix-HonorKeyboardImeOverlay.md`: `adjustNothing` at the Activity level plus Compose-side IME handling. Add a small auto-scroll policy for IME height changes, then wire `ChatScreen` so keyboard expansion can re-anchor the list to the latest message only when the user was already near the bottom.

**Tech Stack:** Android Kotlin, Jetpack Compose `LazyColumn`, Compose `WindowInsets.ime`, Kotlin/JVM unit tests.

---

## Status

- Status: Implemented; automated verification passed; manual device verification pending
- Created on: 2026-06-10
- Implemented on: 2026-06-10
- Scope: shared `ChatScreen` used by both single conversations and group conversations.

## Observed Symptoms

Known behavior from manual testing:

1. Open a single chat or group chat.
2. Tap the text input field.
3. The keyboard opens.
4. The composer moves up with the keyboard.
5. The chat content above the composer does not visually re-anchor, so the composer and keyboard look like they cover the conversation area.

Expected behavior:

- Keyboard opens: the usable chat area becomes shorter and the visible timeline shifts upward like WeChat.
- Keyboard closes: the usable chat area returns to its normal height.
- The behavior applies to both single chat and group chat because both use `ChatScreen`.
- If the user is reading older history, keyboard opening should not force-jump them to the latest message.

## Related Bug Record

- `docs/bug/Fix-HonorKeyboardImeOverlay.md`
  - Previous fix addressed the composer being covered by the keyboard.
  - It intentionally used `android:windowSoftInputMode="adjustNothing"` and `Modifier.imePadding()`.
  - That fix did not add a policy for preserving the chat list's bottom anchor when the IME changes the available viewport.

## Code Evidence

- `app/src/main/AndroidManifest.xml`
  - `MainActivity` uses `android:windowSoftInputMode="adjustNothing"`.

- `app/src/main/java/com/buyansong/im/MainActivity.kt`
  - `WindowCompat.setDecorFitsSystemWindows(window, false)` enables edge-to-edge layout.

- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
  - Root layout uses `.imePadding()`, so the composer moves above the keyboard.
  - `LazyColumn` uses `Modifier.weight(1f)` and `reverseLayout = false`.
  - Auto-scroll currently depends on `latestMessageId` and `state.messages.size`.
  - Keyboard open/close does not change either value, so no scroll correction runs when the IME changes the viewport.

- `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
  - Existing policy distinguishes initial anchoring from later new-message animation.
  - It does not expose a rule for IME expansion.

## Root Cause

The current layout only handles the composer position during IME changes. The message list keeps its existing `LazyListState` scroll offset when the keyboard opens. Because no effect is keyed on IME height or IME visibility, the latest visible chat content is not re-anchored above the composer.

This makes the UI look like the composer and keyboard cover the chat content even though the composer itself is moving correctly.

## Follow-Up Finding From Manual Verification

The first implementation used `animateScrollToItem(latestMessageIndex)` when the keyboard expanded. That works for short text rows, but it is not enough for tall image messages. A tall latest image can still be clipped by the shortened list viewport because scrolling to the latest item is item-based, not bottom-edge based.

The durable behavior is bottom-anchor preservation: when the IME bottom inset grows by `N` pixels and the user was already at the latest message, scroll the `LazyColumn` by the same `N` pixels. This keeps the message bottom aligned above the composer instead of merely trying to bring the latest item into view.

## Non-Goals

- Do not change the Activity keyboard mode back to `adjustResize`.
- Do not split single-chat and group-chat code paths.
- Do not change message ordering.
- Do not force-scroll to latest when the user is reading older history.
- Do not redesign the composer UI.

## Implementation Plan

### Task 1: Add IME Expansion Scroll Policy Tests

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [x] **Step 1: Write failing tests**

Add tests for a new policy method named `shouldAnchorLatestAfterImeExpansion(...)`, then add delta tests for `imeExpansionScrollDeltaPx(...)`.

Required behavior:

```kotlin
@Test
fun shouldAnchorLatestAfterImeExpansion_whenKeyboardExpandsAndUserWasAtBottom_returnsTrue() {
    assertTrue(
        ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
            previousImeBottomPx = 0,
            currentImeBottomPx = 720,
            messageCount = 12,
            lastVisibleIndexBeforeImeChange = 11
        )
    )
}

@Test
fun shouldAnchorLatestAfterImeExpansion_whenKeyboardCollapses_returnsFalse() {
    assertFalse(
        ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
            previousImeBottomPx = 720,
            currentImeBottomPx = 0,
            messageCount = 12,
            lastVisibleIndexBeforeImeChange = 11
        )
    )
}

@Test
fun shouldAnchorLatestAfterImeExpansion_whenUserReadsHistory_returnsFalse() {
    assertFalse(
        ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
            previousImeBottomPx = 0,
            currentImeBottomPx = 720,
            messageCount = 12,
            lastVisibleIndexBeforeImeChange = 6
        )
    )
}

@Test
fun shouldAnchorLatestAfterImeExpansion_whenNoMessages_returnsFalse() {
    assertFalse(
        ChatAutoScrollPolicy.shouldAnchorLatestAfterImeExpansion(
            previousImeBottomPx = 0,
            currentImeBottomPx = 720,
            messageCount = 0,
            lastVisibleIndexBeforeImeChange = -1
        )
    )
}

@Test
fun imeExpansionScrollDelta_whenKeyboardExpandsAndUserWasAtBottom_returnsKeyboardDelta() {
    assertEquals(
        720,
        ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
            previousImeBottomPx = 0,
            currentImeBottomPx = 720,
            messageCount = 12,
            lastVisibleIndexBeforeImeChange = 11
        )
    )
}
```

- [x] **Step 2: Run test to verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected: compilation fails because `shouldAnchorLatestAfterImeExpansion` does not exist yet.

- [x] **Step 3: Implement the policy**

Add a method to `ChatAutoScrollPolicy`:

```kotlin
fun shouldAnchorLatestAfterImeExpansion(
    previousImeBottomPx: Int,
    currentImeBottomPx: Int,
    messageCount: Int,
        lastVisibleIndexBeforeImeChange: Int
): Boolean {
    if (messageCount <= 0) return false
    if (currentImeBottomPx <= previousImeBottomPx) return false
    val latestIndex = scrollToLatestIndex(messageCount)
    return lastVisibleIndexBeforeImeChange >= latestIndex
}

fun imeExpansionScrollDeltaPx(
    previousImeBottomPx: Int,
    currentImeBottomPx: Int,
    messageCount: Int,
    lastVisibleIndexBeforeImeChange: Int
): Int {
    if (
        !shouldAnchorLatestAfterImeExpansion(
            previousImeBottomPx = previousImeBottomPx,
            currentImeBottomPx = currentImeBottomPx,
            messageCount = messageCount,
            lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeChange
        )
    ) {
        return 0
    }
    return currentImeBottomPx - previousImeBottomPx
}
```

- [x] **Step 4: Run test to verify GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

### Task 2: Wire IME Height Changes Into ChatScreen

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- Test: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [x] **Step 1: Import IME inset APIs**

Add imports:

```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.gestures.animateScrollBy
```

- [x] **Step 2: Track current IME bottom**

Inside `ChatScreen`, after `val context = LocalContext.current`, add:

```kotlin
val density = LocalDensity.current
val imeBottomPx = WindowInsets.ime.getBottom(density)
var previousImeBottomPx by remember { mutableStateOf(imeBottomPx) }
val lastVisibleMessageIndex by remember(listState) {
    derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
    }
}
var lastVisibleIndexBeforeImeExpansion by remember { mutableStateOf(lastVisibleMessageIndex) }
```

- [x] **Step 3: Re-anchor when IME expands and the user is already at latest**

Add an effect after the existing latest-message auto-scroll effect:

```kotlin
LaunchedEffect(imeBottomPx, lastVisibleMessageIndex) {
    if (imeBottomPx == 0) {
        lastVisibleIndexBeforeImeExpansion = lastVisibleMessageIndex
    }
}

LaunchedEffect(imeBottomPx, state.messages.size) {
    val imeScrollDeltaPx = ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
        previousImeBottomPx = previousImeBottomPx,
        currentImeBottomPx = imeBottomPx,
        messageCount = state.messages.size,
        lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeExpansion
    )
    if (imeScrollDeltaPx > 0) {
        listState.animateScrollBy(imeScrollDeltaPx.toFloat())
    }
    previousImeBottomPx = imeBottomPx
}
```

- [x] **Step 4: Run targeted tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

### Task 3: Verify Broader Chat Tests

**Files:**

- No source edits expected.

- [x] **Step 1: Run chat unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.* --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Manual device verification**

Manual checks:

1. Open a single chat at the latest message.
2. Tap the composer.
3. Confirm the composer moves above the keyboard and the latest chat content remains visible above the composer.
4. Close the keyboard.
5. Confirm the composer returns to the bottom.
6. Repeat the same flow in a group chat.
7. Scroll upward to older history, tap the composer, and confirm the screen does not force-jump to the latest message.

## Verification Notes

- Automated tests protect the scroll decision policy.
- Manual device verification is still required because IME animation and inset dispatch are platform behavior.
- No `git` command should be used while executing this plan.

## Verification Result

Automated verification completed on 2026-06-10:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.* --console=plain
./gradlew :app:testDebugUnitTest --console=plain
```

All commands completed with `BUILD SUCCESSFUL`.

Manual device verification is still pending.
