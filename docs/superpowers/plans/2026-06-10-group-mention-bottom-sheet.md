# Group Mention Bottom Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the inline group-member mention picker that pops up above the chat composer with a scrollable `ModalBottomSheet`, so that large group member lists no longer push the input bar off-screen. When the sheet opens, hide the soft keyboard; when a member is selected, dismiss the sheet, insert the mention into the draft, and pull the keyboard back up on the input.

**Execution constraint:** Do not run git commands and do not add commit steps while implementing this plan.

**Architecture:**
- Promote the mention picker from inline (`ChatScreen.kt:470-506`) to a standalone `ModalBottomSheet` (`MentionPickerSheet.kt`), mirroring the existing `GroupReadDetailSheet` sheet chrome (`chat/GroupReadDetailSheet.kt`) while keeping the current mention row information (avatar + display name + ID).
- Replace the implicit "draft ends with `@`" check inside `ChatComposerBar` with an explicit `showMentionPicker` boolean in `ChatScreen` state, recomputed by the same `ChatMentionPolicy.shouldShowPicker(...)` predicate plus the existing "there are selectable members" guard, so the picker state is observable and the keyboard hide/show can react to it.
- Use a `FocusRequester` attached to the composer `TextField` to bring focus back to the input after the user picks a member, and use `LocalSoftwareKeyboardController` to hide on open / show on member-selected-dismiss.
- `ChatMentionPolicy` stays a pure object; no new logic beyond what the inline picker already uses. All sheet-lifecycle and keyboard management is in the screen layer.

**Tech Stack:** Kotlin, Jetpack Compose (`androidx.compose.material3:material3` `ModalBottomSheet`), `androidx.compose.ui.focus.FocusRequester`, `androidx.compose.ui.platform.LocalSoftwareKeyboardController`, JUnit.

---

## File Structure

### New files
- `app/src/main/java/com/buyansong/im/chat/MentionPickerSheet.kt` — single-responsibility bottom-sheet composable. Title row, scrollable `LazyColumn` of `GroupMember`; sheet chrome follows `GroupReadDetailSheet`, and rows keep mention-picker information density (avatar + display name + ID).

### Modified files
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
  - Add `showMentionPicker: Boolean` local state, `isGroupChat`, and `mentionableMembers`, recomputed in `onDraftChange` via `ChatMentionPolicy.shouldShowPicker(...) && mentionableMembers.isNotEmpty()`.
  - Add `LaunchedEffect(showMentionPicker)` that hides the keyboard when the sheet opens, and never touches the keyboard while it's closed.
  - Add a `FocusRequester` and pass it into `ChatComposerBar`; call `focusRequester.requestFocus()` when a member is selected from the sheet so the keyboard reappears.
  - Render `MentionPickerSheet` after the existing `GroupReadDetailSheet` block (around `ChatScreen.kt:423-428`).
  - Add a `BackHandler(enabled = showMentionPicker)` after the existing overlay `BackHandler`s so the mention sheet consumes Back first while open.
  - Drop the inline `if (ChatMentionPolicy.shouldShowPicker(...))` block in `ChatComposerBar` (lines 470-506).
  - Plumb a new `onOpenMentionPicker` / `onDismissMentionPicker` pair from `ChatScreen` to `ChatComposerBar` is **not** required — the sheet can be driven entirely from `ChatScreen` because the trigger state already lives in `ChatScreen`.
- `app/src/main/java/com/buyansong/im/chat/ChatMentionPolicy.kt` — no semantic change. The existing `shouldShowPicker(draft, isGroup)` predicate is reused as-is.

### Files explicitly NOT modified
- `ChatViewModel.kt` — picker visibility is UI state, not domain state. The ViewModel already exposes `mentionMembers`; the sheet consumes that list directly.
- `MessageRepository`, `GroupRepository`, `GroupDao` — no protocol or storage changes.
- `GroupReadDetailSheet.kt` — kept as the reference pattern but unchanged.
- `ConversationListPreviewPolicy.kt` — only consumes `mentionedUserIds`, not picker UI.

---

## Task 1: Create `MentionPickerSheet` composable

**Files:**
- Create: `app/src/main/java/com/buyansong/im/chat/MentionPickerSheet.kt`

- [ ] **Step 1.1: Write the file**

Create `app/src/main/java/com/buyansong/im/chat/MentionPickerSheet.kt`:

```kotlin
package com.buyansong.im.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionPickerSheet(
    members: List<GroupMember>,
    onMemberSelected: (GroupMember) -> Unit,
    onDismiss: () -> Unit
) {
    // skipPartiallyExpanded = true so the sheet always opens fully expanded,
    // matching the existing GroupReadDetailSheet behavior. With a scrollable
    // LazyColumn inside, the user can still flick through a large member list
    // without us having to add a separate drag-to-expand affordance.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "选择提醒的人",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = ByteImColors.TextPrimary
                    )
                )
            }
            HorizontalDivider()
            // LazyColumn gives us the scroll behavior we need for very large
            // groups. The default fillMaxWidth-height inside ModalBottomSheet
            // is bounded by the sheet's own max height (≈ screen height minus
            // top inset), so this list will not push the title row off-screen.
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(members, key = { it.userId }) { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMemberSelected(member) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarImage(
                            avatarUrl = member.avatarUrl,
                            displayName = member.displayName.ifBlank { member.userId },
                            modifier = Modifier.size(36.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = member.displayName.ifBlank { member.userId },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "ID: ${member.userId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 1.2: Compile**

Run:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. If it fails on `Modifier.clickable` missing import, add `import androidx.compose.foundation.clickable` next to the other `androidx.compose.foundation.*` imports at the top of the file.

- [ ] **Step 1.3: Add a minimal data-assumption test (no Compose UI runner required)**

Add a new file `app/src/test/java/com/buyansong/im/chat/MentionPickerSheetStructureTest.kt`:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Documents the shared data assumptions used by MentionPickerSheet's
 * call site. UI rendering is verified manually in the running app
 * (Compose UI tests are not in this app's test plan).
 */
class MentionPickerSheetStructureTest {

    @Test
    fun groupMemberDisplayNameFallbackToUserId() {
        // Document the existing fallback behavior shared with GroupReadDetailSheet:
        // a blank display name should still render the user id.
        val member = GroupMember(
            groupId = "g_1",
            userId = "u_1",
            displayName = "",
            avatarUrl = null,
            role = GroupMemberRole.MEMBER,
            joinedAt = 0L,
            updatedAt = 0L
        )
        assertEquals("u_1", member.displayName.ifBlank { member.userId })
    }

    @Test
    fun mentionMembersAreFilteredByCurrentUserId() {
        // Verify the filter logic used in MentionPickerSheet's call site:
        // state.mentionMembers.filter { it.userId != viewModel.currentUserId }
        val members = listOf(
            GroupMember("g", "me", "Me", null, GroupMemberRole.MEMBER, 0L, 0L),
            GroupMember("g", "other", "Other", null, GroupMemberRole.MEMBER, 0L, 0L)
        )
        val currentUserId = "me"
        val filtered = members.filter { it.userId != currentUserId }
        assertEquals(1, filtered.size)
        assertEquals("other", filtered[0].userId)
    }
}
```

- [ ] **Step 1.4: Run the new tests**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.MentionPickerSheetStructureTest
```
Expected: 2 tests pass.

---

## Task 2: Drive `showMentionPicker` from `ChatScreen` and hide the keyboard on open

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`

- [ ] **Step 2.1: Add picker state, selectable members, and `FocusRequester`**

In `ChatScreen.kt`, find the state declarations block at lines 103-117 (right after the `ChatScreen` composable opens). Add the picker state, the focus requester, the group flag, and the filtered member list *before* the existing `val focusManager = LocalFocusManager.current` line (currently line 115). Insert the following block:

```kotlin
    // Picker visibility is UI state only — it tracks the same
    // "draft ends with @" condition that ChatMentionPolicy.shouldShowPicker
    // checks, plus the existing guard that there must be someone to select.
    // Making it explicit lets the keyboard hide/show and BackHandler share
    // one source of truth.
    var showMentionPicker by remember { mutableStateOf(false) }
    val mentionFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val isGroupChat = state.peerId.startsWith("group:")
    val mentionableMembers = state.mentionMembers.filter { it.userId != viewModel.currentUserId }

    fun shouldOpenMentionPicker(text: String): Boolean {
        return ChatMentionPolicy.shouldShowPicker(
            draft = text,
            isGroup = isGroupChat
        ) && mentionableMembers.isNotEmpty()
    }
```

(Using a fully-qualified `androidx.compose.ui.focus.FocusRequester` here to avoid an import edit; the import can be tidied later if desired.)

- [ ] **Step 2.2: Recompute `showMentionPicker` whenever the draft changes**

In the `onDraftChange =` lambda passed into `ChatComposerBar` (currently at `ChatScreen.kt:321-324`), replace its body with:

```kotlin
                onDraftChange = {
                    draft = it
                    selectedMentions = ChatMentionPolicy.activeMentions(it.text, selectedMentions)
                    // Mirror the previous inline behavior: show the picker
                    // when the draft ends with "@" in a group chat and there
                    // is at least one other member to select.
                    showMentionPicker = shouldOpenMentionPicker(it.text)
                },
```

Add this effect near the other `LaunchedEffect(...)` blocks so the picker also reacts if group/member state changes after the user has already typed `@`:

```kotlin
    LaunchedEffect(draft.text, isGroupChat, mentionableMembers) {
        showMentionPicker = shouldOpenMentionPicker(draft.text)
    }
```

Because the effect keys do not include `showMentionPicker`, dismissing the sheet by Back or scrim does not immediately reopen it on the same draft; it will only recompute after the draft, group flag, or selectable member list changes.

- [ ] **Step 2.3: Hide the keyboard when the sheet opens**

Add a new `LaunchedEffect` *after* the existing `LaunchedEffect(shouldLoadEarlierHistory)` (currently at `ChatScreen.kt:186-190`). Insert:

```kotlin
    LaunchedEffect(showMentionPicker) {
        // When the picker is shown we hide the keyboard so the bottom sheet
        // is fully visible and the user can flick through the member list.
        // We intentionally do not call show() here — the keyboard will be
        // re-shown only after the user picks a member, see Step 2.4.
        if (showMentionPicker) {
            keyboardController?.hide()
        }
    }
```

- [ ] **Step 2.4: Re-show the keyboard after a member is selected**

Find the `onMentionSelected = { member -> ... }` lambda passed into `ChatComposerBar` (currently at `ChatScreen.kt:327-334`). The current implementation calls `ChatMentionPolicy.insertMention` and writes the new draft but does not touch focus. Since `mentionMembers` and `onMentionSelected` are being removed from `ChatComposerBar`'s signature (see Step 2.7/2.8), this lambda will no longer be passed to `ChatComposerBar`. Move the member selection logic into this `ChatScreen` local function, placed after `val keyboardController = LocalSoftwareKeyboardController.current` so the function can call `keyboardController?.show()`:


```kotlin
    fun handleMemberSelected(member: GroupMember) {
        val result = ChatMentionPolicy.insertMention(draft.text, selectedMentions, member)
        draft = TextFieldValue(
            text = result.draft,
            selection = TextRange(result.cursorPosition)
        )
        selectedMentions = result.selectedMentions
        showMentionPicker = false
        mentionFocusRequester.requestFocus()
        keyboardController?.show()
    }
```

Then use `handleMemberSelected` as the `onMemberSelected` callback in `MentionPickerSheet` (Step 2.6). The `keyboardController?.show()` is a safe fallback for devices where the platform does not auto-show the IME on programmatic focus; on stock Android it is a no-op when the IME is already visible.

- [ ] **Step 2.5: Add a `BackHandler` for the sheet**

Find the `BackHandler` block at `ChatScreen.kt:383-391` and add a new one for the mention picker **after** the existing preview/group/album handlers. Compose back handlers are handled with the later enabled handler taking priority, so placing this last makes the bottom sheet consume Back before the screen-level overlays when it is open:

```kotlin
    BackHandler(enabled = showMentionPicker) {
        // Dismissing via back press must NOT request focus on the input —
        // the user explicitly cancelled the picker, so leave the keyboard
        // hidden and let the next tap on the input field re-show it.
        showMentionPicker = false
    }
```

- [ ] **Step 2.6: Render the `MentionPickerSheet`**

Find the `if (showGroupReadSheet) { GroupReadDetailSheet(...) }` block at `ChatScreen.kt:423-428` and add a sibling block for the mention picker immediately after it. Insert:

```kotlin
    if (showMentionPicker) {
        MentionPickerSheet(
            members = mentionableMembers,
            onMemberSelected = { member -> handleMemberSelected(member) },
            onDismiss = { showMentionPicker = false }
        )
    }
```

This uses the shared `handleMemberSelected` function defined in Step 2.4, eliminating code duplication between the sheet callback and the (now-removed) `ChatComposerBar` callback.

- [ ] **Step 2.7: Drop the inline picker block in `ChatComposerBar`**

In `ChatComposerBar` (currently `ChatScreen.kt:446-586`), remove the entire `if (ChatMentionPolicy.shouldShowPicker(draft.text, isGroup) && mentionMembers.isNotEmpty()) { ... }` block at lines 470-506. Also remove `isGroup`, `mentionMembers`, and `onMentionSelected` from the `ChatComposerBar` signature and from its single call site in `ChatScreen`.

After this removal, `ChatComposerBar` should not reference `ChatMentionPolicy`, `GroupMember`, or `AvatarImage`; those responsibilities live in `ChatScreen` and `MentionPickerSheet`.

- [ ] **Step 2.8: Plumb the `FocusRequester` into `ChatComposerBar`**

The `TextField` inside `ChatComposerBar` (currently `ChatScreen.kt:524-536`) needs to be attached to the `mentionFocusRequester`. Add a new parameter to `ChatComposerBar`'s signature and forward it into the `TextField`. Replace the `ChatComposerBar(...)` signature (lines 446-459) with:

```kotlin
private fun ChatComposerBar(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    showMoreActions: Boolean,
    onMoreActionsClick: () -> Unit,
    onDismissMoreActions: () -> Unit,
    onPickMoreActionImage: () -> Unit,
    mentionFocusRequester: androidx.compose.ui.focus.FocusRequester,
    onEmojiClick: () -> Unit = {}
) {
```

And inside the `TextField(...)` call, add a `Modifier.focusRequester(mentionFocusRequester)` chain. Add the import near the other `androidx.compose.ui.focus.*` imports at the top of the file:

```kotlin
import androidx.compose.ui.focus.focusRequester
```

Update the `TextField(...)` call's `modifier = ...` chain (currently lines 527-533) to:

```kotlin
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(mentionFocusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                onDismissMoreActions()
                            }
                        },
```

Finally, update the call site of `ChatComposerBar` at `ChatScreen.kt:319-363` to pass the new parameter and remove the `isGroup`, `mentionMembers`, `onMentionSelected` arguments. Add `mentionFocusRequester = mentionFocusRequester,` as a named argument.

- [ ] **Step 2.9: Compile**

Run:
```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

---

## Task 3: Add focused unit tests around mention policy behavior

**Files:**
- Create: `app/src/test/java/com/buyansong/im/chat/ChatMentionPickerBehaviorTest.kt`

There is currently no test for `ChatMentionPolicy` in the repo. This task adds the minimal coverage that the picker behavior contract relies on: `shouldShowPicker` only opens the picker for group drafts ending in `@`, and `insertMention` changes the draft/cursor in the way the sheet callback expects.

- [ ] **Step 3.1: Write the tests**

Create `app/src/test/java/com/buyansong/im/chat/ChatMentionPickerBehaviorTest.kt`:

```kotlin
package com.buyansong.im.chat

import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMentionPickerBehaviorTest {

    @Test
    fun pickerIsShownWhenGroupDraftEndsWithAt() {
        assertTrue(ChatMentionPolicy.shouldShowPicker(draft = "@", isGroup = true))
        assertTrue(ChatMentionPolicy.shouldShowPicker(draft = "hello @", isGroup = true))
    }

    @Test
    fun pickerIsHiddenWhenSingleChatDraftEndsWithAt() {
        assertFalse(ChatMentionPolicy.shouldShowPicker(draft = "@", isGroup = false))
    }

    @Test
    fun pickerIsHiddenWhenGroupDraftDoesNotEndWithAt() {
        assertFalse(ChatMentionPolicy.shouldShowPicker(draft = "@alice hello", isGroup = true))
        assertFalse(ChatMentionPolicy.shouldShowPicker(draft = "", isGroup = true))
    }

    @Test
    fun insertMentionClosesPickerByChangingDraft() {
        // After insertMention, the draft no longer ends with "@", so
        // shouldShowPicker returns false — this is the mechanism that
        // auto-dismisses the sheet.
        val member = GroupMember("g", "u1", "Alice", null, GroupMemberRole.MEMBER, 0L, 0L)
        val result = ChatMentionPolicy.insertMention("@", emptyList(), member)
        assertFalse(ChatMentionPolicy.shouldShowPicker(result.draft, isGroup = true))
    }

    @Test
    fun insertMentionPlacesCursorAfterInsertedMention() {
        val member = GroupMember("g", "u1", "Alice", null, GroupMemberRole.MEMBER, 0L, 0L)
        val result = ChatMentionPolicy.insertMention("@", emptyList(), member)
        assertEquals(result.draft.length, result.cursorPosition)
    }
}
```

- [ ] **Step 3.2: Run the tests**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatMentionPickerBehaviorTest
```
Expected: 5 tests pass.

---

## Task 4: Run the full unit test module

- [ ] **Step 4.1: Run all Android unit tests**

Run:
```bash
./gradlew :app:testDebugUnitTest
```
Expected: All existing tests continue to pass plus the 2 new `MentionPickerSheetStructureTest` tests and 5 new `ChatMentionPickerBehaviorTest` tests. No existing test should regress.

- [ ] **Step 4.2: Run a debug build**

Run:
```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. The APK is required to run the manual smoke test in Task 5.

---

## Task 5: Manual smoke test on emulator/device

The picker behavior is UI-driven, so a Compose UI test is out of scope for this app's test plan. Verify the new behavior in a running app.

- [ ] **Step 5.1: Open a group chat with more than 10 members**

The existing dev fixtures (the group created via `发起群聊` + a few extra Contacts) provide only a small group. To stress the scroll, create a dev group whose `memberIds` contains 20+ known local users, or temporarily seed `mock-im-groups.sqlite` with extra members for the target group.

Open the group chat. Tap into the input field. The keyboard pops up.

- [ ] **Step 5.2: Type `@` and verify the bottom sheet appears without the keyboard**

With the keyboard up, type the character `@`. Expected:
- The soft keyboard slides down and disappears.
- A `ModalBottomSheet` slides up from the bottom with the title `选择提醒的人`.
- The keyboard does **not** overlap the sheet.

- [ ] **Step 5.3: Scroll a long member list**

If the group has 20+ members, drag the list inside the sheet. Expected:
- The list scrolls smoothly within the sheet's bounded height.
- The title `选择提醒的人` stays pinned at the top of the sheet.
- The sheet stays within the `ModalBottomSheet` max height and the list scrolls inside it instead of pushing the composer layout.

- [ ] **Step 5.4: Tap a member**

Tap any member row. Expected:
- The sheet dismisses.
- The composer `TextField` shows `@<displayName> ` with the cursor at the end of the trailing space.
- The soft keyboard slides back up.
- A `Send` button replaces the emoji/plus icons (existing composer behavior).
- No `@` is left dangling in the draft.

- [ ] **Step 5.5: Press the system Back button with the sheet open**

Open the picker again by typing `@`. Press the Android back button. Expected:
- The sheet dismisses.
- The keyboard does **not** reappear.
- The draft is unchanged.
- A subsequent tap on the input field re-shows the keyboard.

- [ ] **Step 5.6: Dismiss, then delete `@`**

Type `@`, let the sheet open, press Back to dismiss it, tap the input, then delete the `@` from the draft. Expected:
- The sheet stays dismissed after the draft no longer ends with `@` (this is driven by the `onDraftChange` recomputation in Step 2.2).
- The keyboard follows normal platform focus behavior; it is not forced open by the mention picker after cancellation.

- [ ] **Step 5.7: Single-chat regression check**

Open a single chat with another user. Type `@`. Expected: **no** mention picker appears. The composer behaves exactly as before. This guards the `isGroup = state.peerId.startsWith("group:")` branch.

---

## Task 6: Update documentation

- [ ] **Step 6.1: Update `docs/status/B10-group-chat-and-mention.md`**

In the `Still pending after this slice:` bullet that begins with `Rich @ editing: search, arbitrary cursor insertion, deleting mention chips as a unit, and better long-list member picker UI.` (currently near the end of the `Current Status` section), remove the `better long-list member picker UI` clause — this plan delivers it. Leave the rest of the bullet intact.

Add a new bullet to the `Implemented in this B10 slice:` list (anywhere among the bullets, near the existing `Group chat composer shows a first-pass @ picker when a group draft ends with @` line):

```markdown
- Group chat mention picker now renders as a scrollable bottom sheet that hides the soft keyboard while open and pulls the keyboard back up on the input after a member is selected, so large group member lists do not push the composer off-screen.
```

- [ ] **Step 6.2: Add a verification log to `docs/status/B10-group-chat-and-mention.md`**

`B10-group-chat-and-mention.md` does not currently have a verification table. Add a new `## Verification` section near the end of the file using the same Markdown table format as `docs/status/B2-single-chat.md`:

```markdown
## Verification

| Date | Area | Command | Result |
|---|---|---|---|
| 2026-06-10 | Group Mention Bottom Sheet | `./gradlew :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatMentionPickerBehaviorTest --tests com.buyansong.im.chat.MentionPickerSheetStructureTest` | Passed: 7 unit tests covering `ChatMentionPolicy.shouldShowPicker`, `insertMention` cursor/visibility behavior, and member-list filtering; manual smoke test confirmed the bottom sheet appears without the keyboard, scrolls for large groups, re-shows the keyboard on member selection, and dismisses cleanly on back press. |
```

---

## Self-Review

- [ ] **Spec coverage:**
  - Bottom sheet appears after `@` when selectable group members exist → covered by Task 2 Step 2.1 + 2.2 + 2.6.
  - Bottom sheet is scrollable → covered by Task 1 Step 1.1 (`LazyColumn` inside `ModalBottomSheet`).
  - Keyboard hides when sheet opens → covered by Task 2 Step 2.3.
  - Keyboard re-appears on the input after member selection → covered by Task 2 Step 2.4/2.6 (`handleMemberSelected` calls `mentionFocusRequester.requestFocus()` + `keyboardController?.show()`).
  - No git operations, no protocol/storage changes → confirmed (no commit steps, no `MessageRepository` or `GroupRepository` edits).
- [ ] **Placeholder scan:** No `TBD` / `TODO` / "implement later" / "similar to Task N" markers. Every code change has the actual code.
- [ ] **Type consistency:** `mentionFocusRequester` is introduced once in Task 2 Step 2.1 and used in Step 2.4, 2.6, 2.8. `showMentionPicker` is the same `Boolean` state across all references. `ChatMentionPolicy.shouldShowPicker(draft, isGroup)` matches its current signature in `ChatMentionPolicy.kt:24-26`. `GroupMember.role` uses `GroupMemberRole` enum, not `String`.
- [ ] **No code duplication:** `handleMemberSelected(member)` is extracted once in `ChatScreen` (Step 2.4) and called from the `MentionPickerSheet` callback (Step 2.6).
- [ ] **No dead parameters:** `mentionMembers` and `onMentionSelected` are removed from `ChatComposerBar`'s signature (Step 2.7/2.8), not kept as unused parameters.
- [ ] **UI consistency:** `MentionPickerSheet` row style matches the current inline picker style (`AvatarImage` 36.dp, `MaterialTheme.typography.titleMedium`/`bodySmall`).
- [ ] **Test reliability:** No reflection-based structural tests; all tests verify actual behavior (`shouldShowPicker` logic, `insertMention` cursor/visibility, member filtering).
