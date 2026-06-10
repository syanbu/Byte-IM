# First Message Anchored To Top Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user sends messages in a conversation whose local history is empty (or has only one message), the messages stack from the **top of the page downward** (first message at the top, second message just below the first, third below the second, …). Once the message list grows beyond a single screen, the latest message still gets scrolled into view at the bottom of the list, just above the input bar.

**Architecture:** Drop `reverseLayout = true` on the message `LazyColumn` and switch to a normal top-to-bottom layout. In a normal layout, item `0` (oldest) renders at the visual top, and the latest item renders near the visual bottom. The auto-scroll policy in `ChatAutoScrollPolicy` is extended with a pure helper `scrollToLatestIndex(messageCount)` that returns the index the `LazyListState` should animate to when a new message arrives (always the last item). The `history-loader` item, which currently sits at the visual *top* of the column (because of `reverseLayout = true`), is moved to **before** `itemsIndexed` so it still renders at the visual top in the new normal layout. The `ChatFirstMessageAnchorPolicy` helper and its associated `Spacer` injection are no longer needed and are removed.

**Crucially, the underlying message list must also be reordered.** The data layer (`MessageDao`, `AndroidMessageDao`, `MessageOrderingPolicy.sortNewestFirst`) currently returns messages in newest-first order — that was the right shape when the `LazyColumn` used `reverseLayout = true` (item 0 = newest = visual bottom). With the new top-down `LazyColumn` and no `reverseLayout`, that ordering puts the newest message at the visual *top*, which is the opposite of what we want. The plan therefore adds a sibling `MessageOrderingPolicy.sortOldestFirst(messages)` and switches `ChatViewModel.mergeMessages` to use it, so `state.messages[0]` is the **oldest** message (visual top) and `state.messages.last()` is the **newest** (visual bottom). The "latest message" and "oldest message" lookups in `ChatScreen` (the auto-scroll `latestMessageId` and the `history-loader` time) are updated to match the new convention.

That oldest-first flip also affects any helper that used to assume "the first matching message is the latest one". Group-read receipt selection is one such place: the helper that finds the current user's latest eligible sent group message must iterate from the end (or use `lastOrNull`) so that only the **newest** eligible own message can carry the `X 人已读` indicator. If this is not updated together with the layout/data-ordering change, group chats will incorrectly keep the indicator on an older own message after newer messages are sent.

**Tech Stack:** Android Kotlin, Jetpack Compose (`LazyColumn` / `LazyListState`), JUnit 4 (existing test framework, plain JUnit — no Robolectric).

---

## Why the previous Spacer-based approach fails

The earlier draft of this plan prepended a tall `Spacer` as the first item of the `LazyColumn` while `messageCount <= 1`. With `reverseLayout = true`, that `Spacer` rendered at the visual bottom and pushed the lone message (item 1) up into the top 15% of the column — which is exactly the "first message anchored to top" behavior the spec asks for.

The moment a second message arrived, `messageCount > 1` was true, the conditional `Spacer` was dropped, and the two messages re-occupied the visual bottom of the column in `reverseLayout` order (latest = item 0 at the bottom, older = item 1 just above). The user-visible result was that the first message lost its top-anchor as soon as a second message appeared — the very regression this plan revision fixes.

The clean way to keep messages stacked from the top is to abandon `reverseLayout = true` entirely. The `Spacer` is then unnecessary; the layout itself is top-down.

## Why data ordering must change too

Switching the `LazyColumn` to a normal top-to-bottom layout is only half of the fix. The chat's data layer still produces messages in **newest-first** order (because the SQL query uses `ORDER BY created_at DESC`, and `MessageOrderingPolicy.sortNewestFirst` is the only ordering helper the ViewModel knows about). With the old `reverseLayout = true` layout that was fine — item 0 (newest) was the visual bottom, and the user saw the conversation as a normal top-to-bottom timeline.

After flipping `reverseLayout` to `false`, the same newest-first data now puts the newest message at the visual **top**, with older messages scrolling downward — the conversation appears to flow backward in time. The plan therefore adds `MessageOrderingPolicy.sortOldestFirst` (a symmetric mirror of `sortNewestFirst`) and uses it in `ChatViewModel.mergeMessages` so that `state.messages[0]` is the oldest message and `state.messages.last()` is the newest. The "latest message id" and "oldest message time" lookups in `ChatScreen` are updated to follow the new convention.

`AndroidMessageDao` and `MessageDao` still call `sortNewestFirst` — they are the data layer and continue to return newest-first for any other consumer that wants it. Only the chat screen needs oldest-first.

---

## File Map

| File | Responsibility |
|---|---|
| `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt` | Add a pure helper `scrollToLatestIndex(messageCount): Int` returning the index the chat list should scroll to when a new message arrives. Keep `shouldLoadEarlierHistory` untouched. |
| `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` | Switch `reverseLayout = true` to `reverseLayout = false`. Move the `history-loader` `item { ... }` block from after `itemsIndexed(...)` to before it. Change `listState.animateScrollToItem(0)` to `listState.animateScrollToItem(ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size))`. Remove the `ChatFirstMessageAnchorPolicy` import and the conditional top-anchor `Spacer` block. Update `state.messages.firstOrNull()?.messageId` → `state.messages.lastOrNull()?.messageId` and `state.messages.last().createdAt` → `state.messages.first().createdAt` to match the new data order. |
| `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` | Switch `mergeMessages` from `MessageOrderingPolicy.sortNewestFirst` to `MessageOrderingPolicy.sortOldestFirst`. |
| `app/src/main/java/com/buyansong/im/chat/GroupReadReceiptPolicy.kt` | Update latest-own-message selection to match oldest-first chat state: the newest eligible own group message is now found from the tail of the list, not the head. |
| `app/src/main/java/com/buyansong/im/storage/MessageOrderingPolicy.kt` | Add `sortOldestFirst(messages)` plus the supporting `localOldestFirst` / `serverSequencedOldestFirst` / `mergeLocalTimelineByCreateTimeOldestFirst` helpers. Keep `sortNewestFirst` and the `newestFirst` comparator unchanged — other consumers (`AndroidMessageDao`, `MessageDao`) still rely on them. |
| `app/src/main/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicy.kt` | **Delete** this file. Its job was to inject a temporary `Spacer` while `messageCount <= 1`; with the new normal layout the policy is no longer needed. |
| `app/src/test/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicyTest.kt` | **Delete** this file. It covered the removed policy. |
| `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt` | New plain JUnit 4 test class covering the new `scrollToLatestIndex` helper and the existing `shouldScrollToLatest` boolean. |
| `app/src/test/java/com/buyansong/im/chat/GroupReadReceiptPolicyTest.kt` | Update tests so "latest eligible own group message" is verified against the new oldest-first chat-state ordering. |
| `app/src/test/java/com/buyansong/im/storage/MessageOrderingPolicyTest.kt` | New plain JUnit 4 test class covering `sortOldestFirst`: oldest message is at index 0, newest at `last()`, server-sequenced and local-timeline messages are interleaved by `createdAt`, and active-local (uploading/sending) messages are pinned to the *end* of the list (the visual bottom, near the input bar). |
| `docs/status/B2-single-chat.md` | Append a "First-message anchor" note describing the new layout choice. |

> **Why keep `shouldScrollToLatest` and add a new helper?** `shouldScrollToLatest(prev, latest)` is the existing boolean that detects "the latest message id changed → we should auto-scroll". The new `scrollToLatestIndex(messageCount)` returns the target index to scroll to. They serve different jobs and the `LaunchedEffect` in `ChatScreen` composes them: only when the boolean is true does the screen call `animateScrollToItem(scrollToLatestIndex(...))`. This keeps the *decision* (should we scroll?) decoupled from the *target* (where to scroll to).

---

## Task 1: Extend `ChatAutoScrollPolicy` with `scrollToLatestIndex`

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt`

- [ ] **Step 1.1: Add the `scrollToLatestIndex` function**

Open `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt` and add the following function inside the `object ChatAutoScrollPolicy` block (placement: right after `shouldScrollToLatest`, before `shouldLoadEarlierHistory`):

```kotlin
    /**
     * Returns the index the message `LazyColumn` should animate to when
     * a new message arrives and the caller has already determined (via
     * [shouldScrollToLatest]) that an auto-scroll is needed.
     *
     * The chat list uses a *normal* top-to-bottom layout (item 0 = oldest
     * = visual top; last item = newest = visual bottom). The "scroll to
     * latest" target is therefore the last index of the list. The caller
     * is responsible for not invoking this when the list is empty — the
     * `LaunchedEffect` in `ChatScreen` checks [shouldScrollToLatest]
     * first, which returns `false` whenever `latestMessageId` is null.
     */
    fun scrollToLatestIndex(messageCount: Int): Int {
        // Defensive: if the list is empty, return 0; the caller's
        // `shouldScrollToLatest` guard should make this unreachable, but
        // returning 0 keeps `animateScrollToItem` safe even if it slips
        // through.
        if (messageCount <= 0) return 0
        return messageCount - 1
    }
```

- [ ] **Step 1.2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 1.3: Commit**

```bash
git add app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt
git commit -m "feat(chat): add scrollToLatestIndex helper for top-down layout"
```

---

## Task 2: Switch `ChatScreen` to the top-down layout

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`

- [ ] **Step 2.1: Remove the now-unused `ChatFirstMessageAnchorPolicy` import**

Locate the import block at the top of `ChatScreen.kt` and remove the line that imports the helper:

```kotlin
import com.buyansong.im.chat.ChatFirstMessageAnchorPolicy
```

(Only delete this line; do not touch the other imports.)

- [ ] **Step 2.2: Remove the conditional top-anchor `Spacer` block**

Open `ChatScreen.kt` and find the conditional block we added in the previous (now-superseded) revision:

```kotlin
if (ChatFirstMessageAnchorPolicy.shouldAnchorFirstMessageToTop(state.messages.size)) {
    item(key = "first-message-top-anchor") {
        // ... Spacer with fillParentMaxHeight(0.85f) ...
    }
}
```

Delete this block in its entirety. The `itemsIndexed(state.messages, ...)` call that follows it must remain intact and is the first item block of the `LazyColumn` content after this deletion.

- [ ] **Step 2.3: Switch `reverseLayout = true` to `reverseLayout = false`**

In the `LazyColumn(...)` declaration, change the `reverseLayout = true` argument to `reverseLayout = false`. The other arguments of `LazyColumn` (`state`, `modifier`, `contentPadding`) are unchanged.

- [ ] **Step 2.4: Move the `history-loader` block from after `itemsIndexed` to before it**

Find the existing `history-loader` block, which currently sits at the end of the `LazyColumn` content (currently the last child of the `LazyColumn { ... }`):

```kotlin
if (state.messages.isNotEmpty()) {
    item(key = "history-loader") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(top = 16.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChatHistoryTopTime(
                text = ChatDisplayPolicy.topTimelineTimeText(state.messages.last().createdAt)
            )
            ChatDisplayPolicy.historyStatusText(state)?.let { statusText ->
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = ByteImColors.TextSecondary
                )
            }
        }
    }
}
```

Cut this entire `if (state.messages.isNotEmpty()) { item(key = "history-loader") { ... } }` block from its current position and paste it **immediately before** the existing `itemsIndexed(state.messages, key = { _, msg -> msg.messageId }) { index, message ->` call. The new order inside the `LazyColumn { ... }` content is:

```kotlin
if (state.messages.isNotEmpty()) {
    item(key = "history-loader") {
        // ... contents unchanged ...
    }
}
itemsIndexed(state.messages, key = { _, msg -> msg.messageId }) { index, message ->
    // ... contents unchanged ...
}
```

- [ ] **Step 2.5: Update the auto-scroll target**

Find the `LaunchedEffect` that calls `animateScrollToItem`:

```kotlin
LaunchedEffect(latestMessageId) {
    if (ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId, latestMessageId)) {
        listState.animateScrollToItem(0)
    }
    previousLatestMessageId = latestMessageId
}
```

Replace `listState.animateScrollToItem(0)` with:

```kotlin
listState.animateScrollToItem(
    ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size)
)
```

The resulting `LaunchedEffect` body reads:

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

The `state.messages.size` key is added to the `LaunchedEffect` so the effect re-runs (and recomputes the target index) when the message count changes, even if `latestMessageId` was the same as the previous frame for some reason.

- [ ] **Step 2.6: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. There must be no remaining references to `ChatFirstMessageAnchorPolicy` in the project.

- [ ] **Step 2.7: Commit**

```bash
git add app/src/main/java/com/buyansong/im/chat/ChatScreen.kt
git commit -m "feat(chat): stack messages from top with auto-scroll to latest"
```

---

## Task 3: Delete the now-unused `ChatFirstMessageAnchorPolicy` files

**Files:**
- Delete: `app/src/main/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicy.kt`
- Delete: `app/src/test/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicyTest.kt`

- [ ] **Step 3.1: Remove the production policy file**

Run:

```bash
rm app/src/main/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicy.kt
```

Expected: file removed. Confirm with `ls app/src/main/java/com/buyansong/im/chat/ | grep -i FirstMessage` returning empty output.

- [ ] **Step 3.2: Remove the test file**

Run:

```bash
rm app/src/test/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicyTest.kt
```

Expected: file removed. Confirm with `ls app/src/test/java/com/buyansong/im/chat/ | grep -i FirstMessage` returning empty output.

- [ ] **Step 3.3: Verify the project still compiles**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. No compile errors referencing the removed files.

- [ ] **Step 3.4: Commit**

```bash
git add -A app/src/main/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicy.kt \
        app/src/test/java/com/buyansong/im/chat/ChatFirstMessageAnchorPolicyTest.kt
git commit -m "chore(chat): drop unused first-message anchor policy"
```

---

## Task 4: Add unit tests for the new `scrollToLatestIndex` helper

**Files:**
- Create: `app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt`

- [ ] **Step 4.1: Write the failing test class**

```kotlin
package com.buyansong.im.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAutoScrollPolicyTest {

    @Test
    fun shouldScrollToLatest_noLatest_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = null,
                latestMessageId = null
            )
        )
    }

    @Test
    fun shouldScrollToLatest_sameLatest_returnsFalse() {
        assertFalse(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = "msg-1",
                latestMessageId = "msg-1"
            )
        )
    }

    @Test
    fun shouldScrollToLatest_newLatest_returnsTrue() {
        assertTrue(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = "msg-1",
                latestMessageId = "msg-2"
            )
        )
        assertTrue(
            ChatAutoScrollPolicy.shouldScrollToLatest(
                previousLatestMessageId = null,
                latestMessageId = "msg-1"
            )
        )
    }

    @Test
    fun scrollToLatestIndex_emptyList_returnsZero() {
        assertEquals(0, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 0))
    }

    @Test
    fun scrollToLatestIndex_singleMessage_returnsZero() {
        assertEquals(0, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 1))
    }

    @Test
    fun scrollToLatestIndex_multipleMessages_returnsLastIndex() {
        assertEquals(1, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 2))
        assertEquals(9, ChatAutoScrollPolicy.scrollToLatestIndex(messageCount = 10))
    }
}
```

- [ ] **Step 4.2: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.chat.ChatAutoScrollPolicyTest"`
Expected: 6 tests, all PASS.

- [ ] **Step 4.3: Commit**

```bash
git add app/src/test/java/com/buyansong/im/chat/ChatAutoScrollPolicyTest.kt
git commit -m "test(chat): cover ChatAutoScrollPolicy scrollToLatestIndex"
```

---

## Task 5: Update `B2-single-chat` status note

**Files:**
- Modify: `docs/status/B2-single-chat.md`

- [ ] **Step 5.1: Replace any pre-existing "First-message anchor" section with the corrected description**

If `docs/status/B2-single-chat.md` already contains a "First-message anchor" section from the previous (superseded) revision, remove it. Then append the following updated section to the end of the file:

```markdown
## First-message anchor

In the chat screen, messages are laid out **top-to-bottom** (the message
`LazyColumn` uses `reverseLayout = false`). The first message of a
brand-new conversation therefore appears at the top of the visible area,
and subsequent messages stack immediately below it. As soon as the
conversation has more messages than fit on one screen, the latest
message is scrolled into view at the bottom of the list, just above the
input bar.

This is driven by
[`ChatAutoScrollPolicy`](../../app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt):

- [`shouldScrollToLatest(previousLatestMessageId, latestMessageId)`](../../app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt) — the existing boolean that returns
  `true` whenever the latest message id changes (including the very
  first message arriving in an empty conversation).
- [`scrollToLatestIndex(messageCount)`](../../app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt) — the new helper that returns the last index of the
  list. `ChatScreen` calls
  `listState.animateScrollToItem(ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size))`
  whenever `shouldScrollToLatest` is `true`, so the latest message is
  always in view at the bottom of the list.

The `history-loader` block (which displays the time of the oldest
message and any "no more history" status) is rendered as the *first*
item of the `LazyColumn` content, before `itemsIndexed`, so it stays
at the visual top of the column in the new top-to-bottom layout.
```

- [ ] **Step 5.2: Commit**

```bash
git add docs/status/B2-single-chat.md
git commit -m "docs(chat): document top-down message layout"
```

---

## Task 6: Reorder messages oldest-first in the data layer

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/MessageOrderingPolicy.kt`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`
- Create: `app/src/test/java/com/buyansong/im/storage/MessageOrderingPolicyTest.kt`

This task switches the chat's internal data order from newest-first to oldest-first so the new top-to-bottom `LazyColumn` renders messages in chronological order (oldest at the visual top, newest at the visual bottom).

- [ ] **Step 6.1: Add `sortOldestFirst` and its helpers to `MessageOrderingPolicy`**

Open `app/src/main/java/com/buyansong/im/storage/MessageOrderingPolicy.kt` and add the following function alongside the existing `sortNewestFirst` (placement: immediately after the existing `sortNewestFirst` function):

```kotlin
    /**
     * Returns the messages in oldest-first order. Used by the chat
     * screen, which renders messages top-to-bottom in a normal
     * `LazyColumn` (item 0 at the visual top, last item at the visual
     * bottom), so the oldest message sits at the top of the list and
     * the newest message sits at the bottom. The newest message is
     * therefore `result.last()`, not `result.first()`.
     *
     * "Active local" (uploading/sending) messages are still pinned to
     * the *end* of the list (i.e. the visual bottom) so the user
     * keeps seeing their in-flight message even if other timeline
     * changes happen around it.
     */
    fun sortOldestFirst(messages: Iterable<ChatMessage>): List<ChatMessage> {
        val activeLocal = mutableListOf<ChatMessage>()
        val serverSequenced = mutableListOf<ChatMessage>()
        val localTimeline = mutableListOf<ChatMessage>()

        messages.forEach { message ->
            when {
                isActiveLocal(message) -> activeLocal += message
                message.serverSeq != null -> serverSequenced += message
                else -> localTimeline += message
            }
        }

        val sortedServerSequenced = serverSequenced.sortedWith(serverSequencedOldestFirst)
        val sortedLocalTimeline = localTimeline.sortedWith(localOldestFirst)
        // For oldest-first we want timeline messages in chronological
        // order at the start of the list, then active-local at the end
        // (the visual bottom) so the user keeps seeing their in-flight
        // message.
        val timelineOldestFirst = mergeLocalTimelineByCreateTimeOldestFirst(
            serverMessages = sortedServerSequenced,
            localMessages = sortedLocalTimeline
        )
        return timelineOldestFirst + activeLocal.sortedWith(localOldestFirst)
    }
```

Then add the supporting comparators and the merge helper. Place them right after the existing `localNewestFirst` comparator, the existing `mergeLocalTimelineByCreateTime` function, and the existing `compareNullableLongNewestFirst` function respectively:

```kotlin
    private val serverSequencedOldestFirst = Comparator<ChatMessage> { left, right ->
        val serverSeqOrder = compareNullableLongOldestFirst(left.serverSeq, right.serverSeq)
        if (serverSeqOrder != 0) {
            serverSeqOrder
        } else {
            compareLocalOldestFirst(left, right)
        }
    }

    private val localOldestFirst = Comparator<ChatMessage> { left, right ->
        compareLocalOldestFirst(left, right)
    }
```

```kotlin
    /**
     * Like [mergeLocalTimelineByCreateTime] but produces an
     * oldest-first merge: local messages with smaller `createdAt` come
     * first, then the corresponding server message, then the next
     * server message, etc. The `serverMessages` and `localMessages`
     * inputs must already be sorted in their respective oldest-first
     * orders.
     */
    private fun mergeLocalTimelineByCreateTimeOldestFirst(
        serverMessages: List<ChatMessage>,
        localMessages: List<ChatMessage>
    ): List<ChatMessage> {
        if (serverMessages.isEmpty()) {
            return localMessages
        }
        val merged = mutableListOf<ChatMessage>()
        var localIndex = 0
        serverMessages.forEach { serverMessage ->
            while (
                localIndex < localMessages.size &&
                localMessages[localIndex].createdAt < serverMessage.createdAt
            ) {
                merged += localMessages[localIndex]
                localIndex += 1
            }
            merged += serverMessage
        }
        while (localIndex < localMessages.size) {
            merged += localMessages[localIndex]
            localIndex += 1
        }
        return merged
    }
```

```kotlin
    private fun compareLocalOldestFirst(left: ChatMessage, right: ChatMessage): Int {
        val createdAtOrder = left.createdAt.compareTo(right.createdAt)
        if (createdAtOrder != 0) {
            return createdAtOrder
        }
        val serverSeqOrder = compareNullableLongOldestFirst(left.serverSeq, right.serverSeq)
        if (serverSeqOrder != 0) {
            return serverSeqOrder
        }
        val clientSeqOrder = left.clientSeq.compareTo(right.clientSeq)
        if (clientSeqOrder != 0) {
            return clientSeqOrder
        }
        return left.messageId.compareTo(right.messageId)
    }

    private fun compareNullableLongOldestFirst(left: Long?, right: Long?): Int {
        return when {
            left != null && right != null -> left.compareTo(right)
            left != null -> -1
            right != null -> 1
            else -> 0
        }
    }
```

The existing `sortNewestFirst`, `newestFirst` comparator, `serverSequencedNewestFirst`, `localNewestFirst`, `compareLocalNewestFirst`, `compareNullableLongNewestFirst`, `mergeLocalTimelineByCreateTime`, and `isActiveLocal` are kept as-is. Other callers (`AndroidMessageDao`, `MessageDao`) still depend on them.

- [ ] **Step 6.2: Switch `ChatViewModel.mergeMessages` to use `sortOldestFirst`**

In `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`, find the `mergeMessages` function and change the `MessageOrderingPolicy.sortNewestFirst(it)` call to `MessageOrderingPolicy.sortOldestFirst(it)`. The resulting function reads:

```kotlin
    private fun mergeMessages(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        return (current + incoming)
            .associateBy { it.messageId }
            .values
            .let { MessageOrderingPolicy.sortOldestFirst(it) }
            .take(MAX_RETAINED_MESSAGES)
    }
```

- [ ] **Step 6.3: Update `ChatScreen` first/last references to match the new data order**

In `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`, find the auto-scroll "latest message id" lookup:

```kotlin
    val latestMessageId = state.messages.firstOrNull()?.messageId
```

Change `firstOrNull()` to `lastOrNull()`:

```kotlin
    val latestMessageId = state.messages.lastOrNull()?.messageId
```

The newest message now lives at the end of the list (the visual bottom), so the auto-scroll detection must look there.

Then find the `history-loader` time lookup, which currently shows the time of the *oldest* loaded message:

```kotlin
                                text = ChatDisplayPolicy.topTimelineTimeText(state.messages.last().createdAt)
```

Change `state.messages.last()` to `state.messages.first()`:

```kotlin
                                text = ChatDisplayPolicy.topTimelineTimeText(state.messages.first().createdAt)
```

The oldest message is now at the start of the list (the visual top), which is exactly what the `history-loader` block (rendered as the first item of the `LazyColumn` content) is positioned to label.

- [ ] **Step 6.4: Add a unit test class for `sortOldestFirst`**

Create `app/src/test/java/com/buyansong/im/storage/MessageOrderingPolicyTest.kt` with the following contents. The test follows the plain JUnit 4 style used by the other `storage` test files in this project (e.g. `InMemoryGroupReadCursorDaoTest`); no Robolectric is required.

```kotlin
package com.buyansong.im.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOrderingPolicyTest {

    private fun msg(
        id: String,
        createdAt: Long,
        serverSeq: Long? = null,
        status: MessageStatus = MessageStatus.SENT
    ) = ChatMessage(
        messageId = id,
        conversationId = "single:u1:u2",
        senderId = "u1",
        receiverId = "u2",
        clientSeq = 0L,
        serverSeq = serverSeq,
        content = id,
        status = status,
        direction = MessageDirection.OUTGOING,
        createdAt = createdAt,
        updatedAt = createdAt,
        type = MessageType.TEXT,
        conversationType = ConversationType.SINGLE
    )

    @Test
    fun sortOldestFirst_emptyInput_returnsEmpty() {
        val result = MessageOrderingPolicy.sortOldestFirst(emptyList())
        assertEquals(emptyList<ChatMessage>(), result)
    }

    @Test
    fun sortOldestFirst_serverSequencedOnly_putsOldestAtIndexZero() {
        val a = msg("a", createdAt = 1_000L, serverSeq = 1L)
        val b = msg("b", createdAt = 2_000L, serverSeq = 2L)
        val c = msg("c", createdAt = 3_000L, serverSeq = 3L)
        val result = MessageOrderingPolicy.sortOldestFirst(listOf(c, a, b))
        assertEquals(listOf("a", "b", "c"), result.map { it.messageId })
    }

    @Test
    fun sortOldestFirst_localTimelineOnly_putsOldestAtIndexZero() {
        val a = msg("a", createdAt = 1_000L)
        val b = msg("b", createdAt = 2_000L)
        val c = msg("c", createdAt = 3_000L)
        val result = MessageOrderingPolicy.sortOldestFirst(listOf(c, a, b))
        assertEquals(listOf("a", "b", "c"), result.map { it.messageId })
    }

    @Test
    fun sortOldestFirst_mixedServerAndLocalTimeline_interleavesByCreatedAt() {
        val localA = msg("localA", createdAt = 1_000L)
        val localB = msg("localB", createdAt = 3_000L)
        val localC = msg("localC", createdAt = 5_000L)
        val serverB = msg("serverB", createdAt = 2_000L, serverSeq = 2L)
        val serverD = msg("serverD", createdAt = 4_000L, serverSeq = 4L)
        val result = MessageOrderingPolicy.sortOldestFirst(
            listOf(localC, serverD, localA, serverB, localB)
        )
        assertEquals(
            listOf("localA", "serverB", "localB", "serverD", "localC"),
            result.map { it.messageId }
        )
    }

    @Test
    fun sortOldestFirst_activeLocalMessages_pinnedToEnd() {
        val a = msg("a", createdAt = 1_000L, serverSeq = 1L)
        val b = msg("b", createdAt = 2_000L, serverSeq = 2L)
        val inFlight = msg(
            id = "inFlight",
            createdAt = 3_000L,
            status = MessageStatus.SENDING
        )
        val result = MessageOrderingPolicy.sortOldestFirst(listOf(inFlight, b, a))
        // 'a' and 'b' are timeline; 'inFlight' is active-local and goes to the END.
        assertEquals(listOf("a", "b", "inFlight"), result.map { it.messageId })
    }
}
```

- [ ] **Step 6.5: Run the new tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.buyansong.im.storage.MessageOrderingPolicyTest"`
Expected: 5 tests, all PASS.

- [ ] **Step 6.6: Verify the project still builds**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. All existing tests continue to pass; the new `MessageOrderingPolicyTest` (5 tests) and the `ChatAutoScrollPolicyTest` (6 tests) all pass.

---

## Task 7: Manual verification on a real device or emulator

- [ ] **Step 7.1: Install the debug build**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL and the APK is installed.

- [ ] **Step 7.2: Verify the empty → first message flow**

1. Launch the app, sign in as a user with at least one known peer (e.g. the mock-server default users `15000000000` and `15000000001`).
2. Open the conversation list, tap a peer that has no local history (a peer you have never chatted with, or clear app data first).
3. Confirm the chat page renders an empty area below the top bar.
4. Type any text and tap the green "发送" button.
5. Expected: the message bubble appears at the **top** of the page, just below the top bar (with the `history-loader` time/status above it). The message is not floating next to the input bar.

- [ ] **Step 7.3: Verify the second message flow (the regression that the data-ordering fix in Task 6 corrects)**

1. Continuing from Step 7.2, type a second message and tap "发送".
2. Expected: the second message appears **immediately below the first message** (both stacked from the top in chronological order). The first message does **not** snap back to the bottom of the column. As long as the two messages fit on one screen, both are visible at the top, and the empty area (plus the input bar) is below them.

- [ ] **Step 7.4: Verify the "more than a screen" flow**

1. Send enough messages to overflow the chat area (typically 10+ on a phone).
2. Expected: the list scrolls so the latest message is visible at the bottom of the column, just above the input bar. Older messages are above it, in chronological order from top to bottom (oldest at the top, newest at the bottom). This is the standard chat-app behavior.

- [ ] **Step 7.5: Verify the existing-message flow is unchanged**

1. Back out to the conversation list, reopen the same conversation.
2. Expected: the list scrolls to the most recent message (visual bottom of the column, just above the input bar). The user does **not** see the oldest messages first.

- [ ] **Step 7.6: Verify the group-chat path is unchanged**

1. Open a group conversation that already has history.
2. Send a new message.
3. Expected: the new message appears at the bottom (just above the input bar), and the list scrolls to keep it in view. The `history-loader` block remains at the very top of the column.

- [ ] **Step 7.7: Verify the chronological order is correct (regression check for the data-ordering change)**

1. With the same peer as in Step 7.6, send three messages in quick succession with distinct contents (e.g. "first", "second", "third").
2. Expected: the three messages appear top-to-bottom in the order **first → second → third** (the order they were sent). The newest message ("third") is at the visual bottom, just above the input bar. Earlier messages are above it in chronological order.
3. If the messages instead appear as **third → second → first** (newest at the top), the data-ordering change has not taken effect — re-verify that `ChatViewModel.mergeMessages` calls `sortOldestFirst` and that `ChatScreen` reads `state.messages.lastOrNull()?.messageId`.
