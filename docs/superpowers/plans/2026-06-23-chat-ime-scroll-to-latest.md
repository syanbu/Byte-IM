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
- If keyboard height grows while the user was reading older history, animate to the latest message.
- If keyboard height is unchanged or collapsing, do nothing.
- Keep the existing first-load and new-message auto-scroll behavior unchanged.
- Keep the more-actions panel behavior unchanged.

## File Structure

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`
  - Add a typed IME action enum.
  - Add a policy function that returns the action for the current IME transition.
  - Keep `imeExpansionScrollDeltaPx(...)` as a compatibility helper if existing tests or callers still use it.

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
  - Replace the current `imeExpansionScrollDeltaPx(...)` call with action-based branching.
  - Execute `animateScrollBy(...)` for bottom anchoring.
  - Execute `animateScrollToItem(latestMessageIndex)` when keyboard expansion starts from history.

- Modify: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`
  - Add failing tests for the new action policy.
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
        lastVisibleIndexBeforeImeChange: Int
    ): ImeExpansionAction {
        if (messageCount <= 0) return ImeExpansionAction.None
        if (currentImeBottomPx <= previousImeBottomPx) return ImeExpansionAction.None

        val latestIndex = scrollToLatestIndex(messageCount)
        return if (lastVisibleIndexBeforeImeChange >= latestIndex) {
            ImeExpansionAction.ScrollByImeDelta(currentImeBottomPx - previousImeBottomPx)
        } else {
            ImeExpansionAction.ScrollToLatest
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
                lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeExpansion
            )
        ) {
            ChatAutoScrollPolicy.ImeExpansionAction.None -> Unit
            is ChatAutoScrollPolicy.ImeExpansionAction.ScrollByImeDelta -> {
                listState.animateScrollBy(imeExpansionAction.deltaPx.toFloat())
            }
            ChatAutoScrollPolicy.ImeExpansionAction.ScrollToLatest -> {
                listState.animateScrollToItem(latestMessageIndex)
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

- `animateScrollToItem(latestMessageIndex)` may conflict visually with initial-load `requestScrollToItem(...)` only if IME opens before messages finish loading. The policy returns `None` when `messageCount <= 0`, so this should be safe.
- Programmatic keyboard opens, such as after selecting a group mention, will also use this policy. That is acceptable because the composer is active and the user is composing a message.
- More-actions panel expansion is intentionally unchanged; it already uses `moreActionsExpansionScrollDeltaPx(...)`.

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

- Spec coverage: The plan covers keyboard expansion at bottom, keyboard expansion from history, keyboard collapse, empty message lists, existing bottom anchoring, and the non-goal of changing more-actions behavior.
- Placeholder scan: No placeholder tasks are left; all code edits and commands are explicit.
- Type consistency: `ImeExpansionAction`, `imeExpansionAction(...)`, `ScrollByImeDelta`, `ScrollToLatest`, and `None` are consistently named across test, policy, and `ChatScreen` wiring.
