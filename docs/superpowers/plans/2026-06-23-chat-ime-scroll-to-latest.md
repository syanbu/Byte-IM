# Chat IME Scroll To Latest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user focuses the chat composer while reading older messages, keyboard expansion should scroll the chat to the latest message instead of leaving the historical viewport visually anchored.

**Architecture:** Keep keyboard scroll policy in `ChatAutoScrollPolicy` and keep `ChatScreen` as the executor of UI scroll actions. Replace the IME-specific "return pixel delta or zero" decision with a typed action that can represent no-op, incremental bottom anchoring, or scroll-to-latest.

**Tech Stack:** Android, Kotlin, Jetpack Compose, `LazyListState`, JUnit unit tests, Gradle.

---

## Current Behavior

`ChatScreen` reads `WindowInsets.ime.getBottom(density)` and applies `Modifier.imePadding()` to the root chat `Box`. This makes the internal content area shorter when the keyboard opens.

The message list only actively scrolls during IME expansion when the latest message was already visible before the keyboard opened. The decision is currently encoded in `ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(...)`, which returns `0` when `lastVisibleIndexBeforeImeChange` is not the latest message index.

That preserves history-reading position, but it conflicts with the intended composer behavior: once the user taps the input and opens the keyboard, the app should treat that as intent to send a new message and move to the latest message.

## Intended Behavior

- If keyboard height grows and there are no messages, do nothing.
- If keyboard height grows while the latest message was visible before IME expansion, keep the current bottom-anchoring behavior by scrolling by the IME height delta.
- If keyboard height first grows from closed while the user was reading older history, animate to the latest message once and bottom-align it with the message list viewport so tall image bubbles are not hidden behind the composer.
- If keyboard height continues growing during the same IME expansion after the history-to-latest scroll has run, keep bottom anchoring by scrolling by the IME height delta instead of restarting the scroll-to-latest animation.
- If keyboard height is unchanged or collapsing, do nothing.
- Keep the existing first-load and new-message auto-scroll behavior unchanged.
- Keep the more-actions panel behavior unchanged.

## File Structure

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
  - Add a typed IME action enum.
  - Add a policy function that returns the action for the current IME transition.
  - Keep `imeExpansionScrollDeltaPx(...)` as a compatibility helper if existing tests or callers still use it.
  - Add a pure helper that calculates the extra scroll delta needed to align the latest item bottom with the viewport bottom after a scroll-to-latest action.

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
  - Replace the current `imeExpansionScrollDeltaPx(...)` call with action-based branching.
  - Execute `animateScrollBy(...)` for bottom anchoring.
  - Execute a scroll-to-latest helper when keyboard expansion starts from history; the helper first scrolls to the latest item, waits for layout, then applies any remaining bottom-alignment delta.

- Modify: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`
  - Add failing tests for the new action policy.
  - Add tests for the bottom-alignment delta calculation.
  - Update the old history-reading IME delta test only if it conflicts with the new desired behavior.

---

### Task 1: Add Failing Tests For IME Expansion Actions

**Files:**
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [ ] **Step 1: Add tests for the desired IME action policy**

Insert these tests after `imeExpansionScrollDelta_whenUserReadsHistory_returnsZero`:

```kotlin
    @Test
    fun imeExpansionAction_whenKeyboardExpandsAndUserWasAtBottom_returnsScrollByImeDelta() {
        assertEquals(
            ChatAutoScrollPolicy.ImeExpansionAction.ScrollByImeDelta(deltaPx = 720),
            ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 11
            )
        )
    }

    @Test
    fun imeExpansionAction_whenKeyboardExpandsAndUserReadsHistory_returnsScrollToLatest() {
        assertEquals(
            ChatAutoScrollPolicy.ImeExpansionAction.ScrollToLatest,
            ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 6
            )
        )
    }

    @Test
    fun imeExpansionAction_whenKeyboardContinuesExpandingAndUserReadsHistory_returnsNone() {
        assertEquals(
            ChatAutoScrollPolicy.ImeExpansionAction.None,
            ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = 120,
                currentImeBottomPx = 240,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 6
            )
        )
    }

    @Test
    fun imeExpansionAction_whenKeyboardContinuesExpandingAfterScrollToLatest_returnsScrollByImeDelta() {
        assertEquals(
            ChatAutoScrollPolicy.ImeExpansionAction.ScrollByImeDelta(deltaPx = 120),
            ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = 120,
                currentImeBottomPx = 240,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 6,
                didScrollToLatestDuringImeExpansion = true
            )
        )
    }

    @Test
    fun imeExpansionAction_whenKeyboardCollapses_returnsNone() {
        assertEquals(
            ChatAutoScrollPolicy.ImeExpansionAction.None,
            ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = 720,
                currentImeBottomPx = 0,
                messageCount = 12,
                lastVisibleIndexBeforeImeChange = 11
            )
        )
    }

    @Test
    fun imeExpansionAction_whenNoMessages_returnsNone() {
        assertEquals(
            ChatAutoScrollPolicy.ImeExpansionAction.None,
            ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = 0,
                currentImeBottomPx = 720,
                messageCount = 0,
                lastVisibleIndexBeforeImeChange = -1
            )
        )
    }
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected result:

```text
FAILED
Unresolved reference: ImeExpansionAction
Unresolved reference: imeExpansionAction
```

If the failure is a syntax error in the test, fix the test before touching production code.

---

### Task 2: Add The IME Action Policy

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`

- [ ] **Step 1: Add the action type**

Add this enum-like sealed interface inside `object ChatAutoScrollPolicy`, after `enum class ScrollAction`:

```kotlin
    sealed interface ImeExpansionAction {
        data object None : ImeExpansionAction
        data class ScrollByImeDelta(val deltaPx: Int) : ImeExpansionAction
        data object ScrollToLatest : ImeExpansionAction
    }
```

- [ ] **Step 2: Add the policy function**

Add this function before `imeExpansionScrollDeltaPx(...)`:

```kotlin
    fun imeExpansionAction(
        previousImeBottomPx: Int,
        currentImeBottomPx: Int,
        messageCount: Int,
        lastVisibleIndexBeforeImeChange: Int,
        didScrollToLatestDuringImeExpansion: Boolean = false
    ): ImeExpansionAction {
        if (messageCount <= 0) return ImeExpansionAction.None
        if (currentImeBottomPx <= previousImeBottomPx) return ImeExpansionAction.None

        val latestIndex = scrollToLatestIndex(messageCount)
        return if (lastVisibleIndexBeforeImeChange >= latestIndex || didScrollToLatestDuringImeExpansion) {
            ImeExpansionAction.ScrollByImeDelta(currentImeBottomPx - previousImeBottomPx)
        } else if (previousImeBottomPx == 0) {
            ImeExpansionAction.ScrollToLatest
        } else {
            ImeExpansionAction.None
        }
    }
```

- [ ] **Step 3: Keep the existing delta helper compatible**

Replace the body of `imeExpansionScrollDeltaPx(...)` with:

```kotlin
    fun imeExpansionScrollDeltaPx(
        previousImeBottomPx: Int,
        currentImeBottomPx: Int,
        messageCount: Int,
        lastVisibleIndexBeforeImeChange: Int
    ): Int {
        return when (
            val action = imeExpansionAction(
                previousImeBottomPx = previousImeBottomPx,
                currentImeBottomPx = currentImeBottomPx,
                messageCount = messageCount,
                lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeChange
            )
        ) {
            ImeExpansionAction.None -> 0
            is ImeExpansionAction.ScrollByImeDelta -> action.deltaPx
            ImeExpansionAction.ScrollToLatest -> 0
        }
    }
```

This preserves the old helper's numeric contract while allowing `ChatScreen` to use the richer action.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit the policy change**

Run:

```bash
git add app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt
git commit -m "test: cover chat ime scroll actions"
```

---

### Task 3: Wire The Policy Into ChatScreen

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`

- [ ] **Step 1: Replace the IME scroll effect body**

Find the current effect:

```kotlin
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

Replace it with:

```kotlin
    LaunchedEffect(imeBottomPx, state.messages.size) {
        when (
            val imeExpansionAction = ChatAutoScrollPolicy.imeExpansionAction(
                previousImeBottomPx = previousImeBottomPx,
                currentImeBottomPx = imeBottomPx,
                messageCount = state.messages.size,
                lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeExpansion,
                didScrollToLatestDuringImeExpansion = didScrollToLatestDuringImeExpansion
            )
        ) {
            ChatAutoScrollPolicy.ImeExpansionAction.None -> {
                if (imeBottomPx == 0) {
                    didScrollToLatestDuringImeExpansion = false
                }
            }
            is ChatAutoScrollPolicy.ImeExpansionAction.ScrollByImeDelta -> {
                listState.animateScrollBy(imeExpansionAction.deltaPx.toFloat())
            }
            ChatAutoScrollPolicy.ImeExpansionAction.ScrollToLatest -> {
                listState.animateScrollToLatestBottomAligned(latestMessageIndex)
                didScrollToLatestDuringImeExpansion = true
            }
        }
        previousImeBottomPx = imeBottomPx
    }
```

- [ ] **Step 2: Run focused unit tests**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Build the app**

Run:

```bash
./gradlew :app:assembleDebug --console=plain
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit the ChatScreen wiring**

Run:

```bash
git add app/src/main/java/com/buyansong/im/chat/ChatScreen.kt
git commit -m "feat: scroll chat to latest on keyboard open"
```

---

### Task 3.5: Bottom-Align Tall Latest Items After ScrollToLatest

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [ ] **Step 1: Add failing tests for bottom-alignment delta**

Add pure policy tests for the extra delta needed after the latest item is visible:

```kotlin
    @Test
    fun bottomAlignmentScrollDelta_whenItemBottomExceedsViewport_returnsOverflow() {
        assertEquals(
            120,
            ChatAutoScrollPolicy.bottomAlignmentScrollDeltaPx(
                itemOffsetPx = 460,
                itemSizePx = 360,
                viewportEndOffsetPx = 700
            )
        )
    }

    @Test
    fun bottomAlignmentScrollDelta_whenItemFitsViewport_returnsZero() {
        assertEquals(
            0,
            ChatAutoScrollPolicy.bottomAlignmentScrollDeltaPx(
                itemOffsetPx = 280,
                itemSizePx = 120,
                viewportEndOffsetPx = 700
            )
        )
    }
```

- [ ] **Step 2: Add the bottom-alignment policy helper**

Add this helper to `ChatAutoScrollPolicy`:

```kotlin
    fun bottomAlignmentScrollDeltaPx(
        itemOffsetPx: Int,
        itemSizePx: Int,
        viewportEndOffsetPx: Int
    ): Int {
        return (itemOffsetPx + itemSizePx - viewportEndOffsetPx).coerceAtLeast(0)
    }
```

- [ ] **Step 3: Add a ChatScreen LazyListState helper**

Add a private suspend helper near `ChatScreen`:

```kotlin
private suspend fun LazyListState.animateScrollToLatestBottomAligned(index: Int) {
    animateScrollToItem(index)
    withFrameNanos { }

    val layoutInfo = layoutInfo
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val bottomAlignmentDeltaPx = ChatAutoScrollPolicy.bottomAlignmentScrollDeltaPx(
        itemOffsetPx = latestItem.offset,
        itemSizePx = latestItem.size,
        viewportEndOffsetPx = layoutInfo.viewportEndOffset
    )
    if (bottomAlignmentDeltaPx > 0) {
        animateScrollBy(bottomAlignmentDeltaPx.toFloat())
    }
}
```

Then replace the `ScrollToLatest` branch with:

```kotlin
            ChatAutoScrollPolicy.ImeExpansionAction.ScrollToLatest -> {
                listState.animateScrollToLatestBottomAligned(latestMessageIndex)
            }
```

- [ ] **Step 4: Verify focused tests and build**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
./gradlew :app:assembleDebug --console=plain
```

Expected result for both:

```text
BUILD SUCCESSFUL
```

---

### Task 4: Manual Runtime Verification

**Files:**
- No code changes.

- [ ] **Step 1: Install or run the debug app**

Use the project's normal debug deployment path. If using Gradle:

```bash
./gradlew :app:assembleDebug --console=plain
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Verify keyboard open from bottom**

Manual path:

1. Open a chat with enough messages to scroll.
2. Scroll to the newest message at the bottom.
3. Tap the composer text field.

Expected result:

```text
The keyboard opens.
The latest message remains visible above the composer.
The list moves by the IME height delta, matching the existing behavior.
```

- [ ] **Step 3: Verify keyboard open from history**

Manual path:

1. Open a chat with enough messages to scroll.
2. Scroll upward into older history so the latest message is not visible.
3. Tap the composer text field.

Expected result:

```text
The keyboard opens.
The chat animates to the latest message.
If the latest message is a tall image bubble, its bottom edge remains visible above the composer.
The composer remains above the keyboard.
The user is ready to type a new message at the bottom of the conversation.
```

- [ ] **Step 4: Verify keyboard close**

Manual path:

1. With the keyboard open, press Back to close the keyboard.

Expected result:

```text
The keyboard closes.
The chat does not jump to another historical position.
No extra scroll-to-latest action runs during IME collapse.
```

- [ ] **Step 5: Verify no-message chat**

Manual path:

1. Open or create a chat with no messages.
2. Tap the composer text field.

Expected result:

```text
The keyboard opens.
The screen does not crash.
No invalid list index scroll is attempted.
```

---

## Regression Risks

- `animateScrollToLatestBottomAligned(latestMessageIndex)` may conflict visually with initial-load `requestScrollToItem(...)` only if IME opens before messages finish loading. The policy returns `None` when `messageCount <= 0`, so this should be safe.
- The bottom-alignment helper depends on `LazyListState.layoutInfo` after the IME-applied layout pass. The `withFrameNanos { }` wait is included so the latest viewport dimensions are used before computing the extra delta.
- `ScrollToLatest` is intentionally one-shot for the closed-to-opening IME transition (`previousImeBottomPx == 0`). After it runs, continuing IME expansion frames use `ScrollByImeDelta` so the latest item remains bottom-anchored as the viewport keeps shrinking.
- Programmatic keyboard opens, such as after selecting a group mention, will also use this policy. That is acceptable because the composer is active and the user is composing a message.
- More-actions panel expansion is intentionally unchanged; it already uses `moreActionsExpansionScrollDeltaPx(...)`.

## Index Space Alignment (Leading Header Offset)

**Problem found after the initial wiring:** the `ScrollToLatest` action landed on the *second-to-last* message, not the latest, so a tall last image bubble stayed obscured behind the composer even though the `didScrollToLatestDuringImeExpansion` follow-up logic was correct.

**Root cause:** the chat `LazyColumn` renders a leading `history-loader` header item (a top timeline + history status) before the first message whenever the conversation has messages. That shifts every message's real LazyColumn index up by one, so the latest message lives at LazyColumn index `messages.size` (not `messages.size - 1`). `ChatAutoScrollPolicy` is defined in *message-space* (index 0 = first message, last index = `messages.size - 1`), so:

- The scroll target `ChatAutoScrollPolicy.scrollToLatestIndex(messages.size)` returned the message-space last index (`size - 1`). `ChatScreen` passed it straight to `animateScrollToItem(...)` / `requestScrollToItem(...)`, landing one item short of the latest.
- `lastVisibleMessageIndex` was the *real* LazyColumn index (header included), but it was fed into a policy that compares against message-space indices. The two spaces never agreed, so "is the latest already visible?" / "should we scroll to latest?" were answered against mismatched indices — the semantic was never actually synced.

**Fix (boundary translation, policy stays pure):** keep `ChatAutoScrollPolicy` in message-space (all 30 unit tests are message-space) and translate at the `ChatScreen` boundary using `leadingHeaderItemCount` (`1` when the list has messages, else `0`):

- `lastVisibleMessageIndex = maxVisibleLazyColumnIndex - leadingHeaderItemCount` → message-space before feeding the policy.
- `latestMessageIndex = scrollToLatestIndex(messages.size) + leadingHeaderItemCount` → LazyColumn-space before passing to scroll APIs.

This corrects all three scroll entry points — initial-load `requestScrollToItem`, new-message `animateScrollToItem`, and IME `animateScrollToLatestBottomAligned` — and also fixes the "exactly one item short of the bottom" edge case in the at-bottom detection.

## Final Verification

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatAutoScrollPolicyTest --console=plain
./gradlew :app:assembleDebug --console=plain
```

Expected result for both:

```text
BUILD SUCCESSFUL
```

Then complete the manual runtime checks in Task 4 before claiming the behavior is done.

## Self-Review

- Spec coverage: The plan covers keyboard expansion at bottom, one-shot keyboard expansion from history, tall latest image bubbles, keyboard collapse, empty message lists, existing bottom anchoring, and the non-goal of changing more-actions behavior.
- Index-space alignment: `ChatAutoScrollPolicy` is message-space; `ChatScreen` translates to/from LazyColumn-space via `leadingHeaderItemCount` (see "Index Space Alignment"). The visible index fed to the policy and the scroll target index are now in the same space, which is what made the tall-last-image fix actually take effect.
- Placeholder scan: No placeholder tasks are left; all code edits and commands are explicit.
- Type consistency: `ImeExpansionAction`, `imeExpansionAction(...)`, `ScrollByImeDelta`, `ScrollToLatest`, `None`, `bottomAlignmentScrollDeltaPx(...)`, and `animateScrollToLatestBottomAligned(...)` are consistently named across test, policy, and `ChatScreen` wiring.
