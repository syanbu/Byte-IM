# Contacts Entry Placeholders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two static placeholder entries (新的朋友, 群聊) as a separate block at the top of the contacts list, and shorten the contact-row dividers so they stop at the avatar's right edge.

**Architecture:** Keep the change fully inside `ContactListScreen.kt` plus two new vector drawables. The new block is composed as a single LazyColumn item before the existing `items(state.items)` loop, and the existing contact-row `HorizontalDivider` gets a left padding equal to the avatar tile's right edge. No ViewModel or state changes.

**Tech Stack:** Jetpack Compose, Material 3, Android vector drawables, Kotlin. Existing project has ViewModel unit tests (no Compose UI test infrastructure), and the spec explicitly says no new tests for this change.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/res/drawable/ic_contact_new_friend.xml` | Create | 28×28dp vector icon for 新的朋友 (person + plus). |
| `app/src/main/res/drawable/ic_contact_group_chat.xml` | Create | 28×28dp vector icon for 群聊 (three people). |
| `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt` | Modify | Add `ContactEntryItem` and `ContactEntryBlock` Composables; render the block as the first `LazyColumn` item; add left padding to the contact-row `HorizontalDivider` so it stops at the avatar's right edge. |

The two new Composables live in the same file as `ContactListScreen.kt` because they are small, private, and only used by this screen. Splitting them into a new file would add indirection without paying off in clarity or testability.

No other files are touched. No tests are added (the spec's "Testing" section is explicit: "No new tests. The placeholders have no logic.").

---

## Task 1: Add the two vector drawable icons

**Files:**
- Create: `app/src/main/res/drawable/ic_contact_new_friend.xml`
- Create: `app/src/main/res/drawable/ic_contact_group_chat.xml`

- [ ] **Step 1: Create `ic_contact_new_friend.xml`**

Create the file with this exact content. It mirrors the existing `ic_add_circle.xml` style: 28×28dp viewport, single-color fill (`#000000`), no stroke. The `tint` in Compose will recolor it at runtime, so the source color is just a placeholder.

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp"
    android:height="28dp"
    android:viewportWidth="28"
    android:viewportHeight="28">
    <path
        android:fillColor="#000000"
        android:pathData="M14,8 a3.5,3.5 0 1,0 0.001,0 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M5,22 c0,-3 4,-5 9,-5 s9,2 9,5 v1 H5 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M22,3 h2 v3 h3 v2 h-3 v3 h-2 v-3 h-3 v-2 h3 Z" />
</vector>
```

- [ ] **Step 2: Create `ic_contact_group_chat.xml`**

Create the file with this exact content. Same style: 28×28dp viewport, single-color fill, no stroke.

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp"
    android:height="28dp"
    android:viewportWidth="28"
    android:viewportHeight="28">
    <path
        android:fillColor="#000000"
        android:pathData="M7,8 a2.5,2.5 0 1,0 0.001,0 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M21,8 a2.5,2.5 0 1,0 0.001,0 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M14,5 a3,3 0 1,0 0.001,0 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M2,21 c0,-3 2,-5 4.5,-5 c1.2,0 2.3,0.4 3.2,1.1 c-0.4,0.8 -0.6,1.7 -0.6,2.6 v1.3 H2 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M10,21 c0,-3.5 1.8,-6 4,-6 c2.2,0 4,2.5 4,6 v1 H10 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M18.9,17.1 c0.9,-0.7 2,-1.1 3.2,-1.1 c2.5,0 4.5,2 4.5,5 v1 H18.4 v-1.3 c0,-0.9 -0.2,-1.8 -0.6,-2.6 Z" />
</vector>
```

- [ ] **Step 3: Verify the drawables are syntactically valid**

The Android resource compiler will validate the XML on the next build. For a quick sanity check that the files exist and are readable, run:

```bash
ls -la app/src/main/res/drawable/ic_contact_new_friend.xml app/src/main/res/drawable/ic_contact_group_chat.xml
```

Expected: both files listed with non-zero size.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/drawable/ic_contact_new_friend.xml app/src/main/res/drawable/ic_contact_group_chat.xml
git commit -m "feat(contacts): add entry placeholder vector icons"
```

---

## Task 2: Add the `ContactEntryItem` and `ContactEntryBlock` Composables

**Files:**
- Modify: `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`

Both Composables are private to the file. They sit at the bottom of `ContactListScreen.kt`, after the existing `ContactRow` Composable.

- [ ] **Step 1: Add the new imports**

Open `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt` and add the following import. It is needed for the `SurfaceLow` background tile color used in `ContactEntryItem`.

Current imports in the file (lines 3–40) already include `Box`, `Row`, `Column`, `Modifier.size`, `MaterialTheme`, `Text`, `painterResource`, `FontWeight`, `TextOverflow`, `ByteImColors`, `ByteImDimensions`, `ByteImShapes`, `R.drawable` (via `R` reference later). The new code also needs `Spacer` and `height` for the gap between the block and the contact list.

Add this import alongside the existing `androidx.compose.foundation.layout.*` imports (currently lines 3–13):

```kotlin
import androidx.compose.foundation.layout.Spacer
```

(If the existing block already imports `Spacer` from the same package, this is a duplicate and the import line should be skipped. Check with `grep -n "import androidx.compose.foundation.layout.Spacer" app/src/main/java/com/codex/im/contacts/ContactListScreen.kt` first. If it returns a match, skip this step.)

- [ ] **Step 2: Append the two Composables at the end of the file**

Append the following block to the very end of `ContactListScreen.kt` (after the closing brace of the existing `ContactRow` Composable, currently at line 168):

```kotlin
@Composable
private fun ContactEntryItem(
    iconResId: Int,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ByteImDimensions.ListAvatarSize)
                .background(ByteImColors.SurfaceLow, ByteImShapes.Avatar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = title,
                tint = ByteImColors.TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = ByteImColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ContactEntryBlock() {
    ContactEntryItem(
        iconResId = R.drawable.ic_contact_new_friend,
        title = "新的朋友",
        onClick = {}
    )
    HorizontalDivider(
        color = ByteImColors.Divider,
        modifier = Modifier.padding(
            start = ByteImDimensions.EdgePadding +
                ByteImDimensions.ListAvatarSize +
                ByteImDimensions.Gutter
        )
    )
    ContactEntryItem(
        iconResId = R.drawable.ic_contact_group_chat,
        title = "群聊",
        onClick = {}
    )
}
```

- [ ] **Step 3: Verify the file still parses (Kotlin compile check via Gradle build)**

The project has no standalone Kotlin lint step for the IDE; the earliest authoritative check is the build. To make sure the additions compile before wiring them into the screen, run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL` with no compilation errors. If it fails with "Unresolved reference: R", check that the file already has `package com.codex.im.contacts` at the top and that `R` is implicitly resolved via the `com.codex.im.R` namespace (no explicit import is needed because the file lives in that package).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/codex/im/contacts/ContactListScreen.kt
git commit -m "feat(contacts): add ContactEntryItem and ContactEntryBlock composables"
```

---

## Task 3: Wire the new block into the LazyColumn and fix the divider indent

**Files:**
- Modify: `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt:71-79`

- [ ] **Step 1: Replace the LazyColumn body**

In `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`, the `LazyColumn` currently looks like (lines 71–79):

```kotlin
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.userId }) { item ->
                    ContactRow(
                        item = item,
                        onClick = { viewModel.openContact(item.userId) }
                    )
                    HorizontalDivider(color = ByteImColors.Divider)
                }
            }
```

Replace that block with:

```kotlin
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    ContactEntryBlock()
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(state.items, key = { it.userId }) { item ->
                    ContactRow(
                        item = item,
                        onClick = { viewModel.openContact(item.userId) }
                    )
                    HorizontalDivider(
                        color = ByteImColors.Divider,
                        modifier = Modifier.padding(
                            start = ByteImDimensions.EdgePadding +
                                ByteImDimensions.ListAvatarSize +
                                ByteImDimensions.Gutter
                        )
                    )
                }
            }
```

The two changes here:

1. A new `item { ContactEntryBlock(); Spacer(...) }` at the top. `ContactEntryBlock` renders the two placeholder rows with the divider between them; the `Spacer` adds an 8dp vertical gap so the block feels separate from the contacts list.
2. The contact-row `HorizontalDivider` now carries `Modifier.padding(start = 78.dp)` (the sum `EdgePadding + ListAvatarSize + Gutter`), so the divider starts at the avatar's right edge instead of the screen's left edge.

- [ ] **Step 2: Compile-check the screen**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If the import for `Spacer` was missed in Task 2 Step 1, the error message will be `Unresolved reference: Spacer` — go back and add the import.

- [ ] **Step 3: Run the existing unit tests to confirm no regression**

The existing `ContactListViewModelTest` exercises the ViewModel that the screen depends on. Running the full unit test suite catches any accidental breakage in the contacts subsystem.

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL` and all existing tests pass. The change here is purely visual — no logic was added — so the existing tests should pass unchanged.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/codex/im/contacts/ContactListScreen.kt
git commit -m "feat(contacts): render placeholder block and indent row dividers"
```

---

## Task 4: Build the debug APK and visually verify

**Files:** none modified.

This task has no code changes. The goal is to confirm the new block renders correctly on a real device or emulator, and that the divider indent looks right.

- [ ] **Step 1: Build the debug APK**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` is produced.

- [ ] **Step 2: Install on a device or emulator and open the contacts tab**

```bash
./gradlew :app:installDebug
```

Open the app, switch to the **通讯录** tab. Verify visually that:

- The first two rows show 新的朋友 and 群聊, each with a dark icon on a light tile, the title to the right of the tile, and the row height matches the contact rows below.
- Tapping either row shows a ripple but does not navigate, show a toast, or change state. (This is intentional — they're placeholders.)
- There is a visible 8dp vertical gap between 群聊 and the first contact row, with no divider line in the gap.
- The horizontal divider between each pair of contact rows starts at the right edge of the avatar tile (not the left edge of the screen). It should look like the dividers in the reference image.
- The divider between 新的朋友 and 群聊 is in the same vertical line as the contact-row dividers — that is, all dividers in the screen start at 78dp from the left.

If any of these checks fail, fix in place and re-run `./gradlew :app:installDebug`. Common adjustments if needed:

- If the icon tile is too prominent: change `ByteImColors.SurfaceLow` in `ContactEntryItem` to `ByteImColors.Surface` or another existing color.
- If the icon paths look off in render: edit the path data in `ic_contact_new_friend.xml` / `ic_contact_group_chat.xml` and rebuild.
- If the gap is too small or too large: change the `8.dp` in the `Spacer` inside the `item { ... }` block.

- [ ] **Step 3: Final commit (only if adjustments were made in Step 2)**

If Step 2 required edits, commit them now:

```bash
git add -A
git commit -m "fix(contacts): polish placeholder block visuals after device check"
```

If Step 2 passed with no changes, skip the commit and move to the wrap-up.

---

## Self-Review

**1. Spec coverage:**

- Vector drawables for 新的朋友 + 群聊 → Task 1. ✓
- `ContactEntryItem` Composable → Task 2. ✓
- `ContactEntryBlock` Composable → Task 2. ✓
- Render block as first `LazyColumn` item, with 8dp spacer → Task 3. ✓
- Indent `HorizontalDivider` to avatar right edge (78dp) → Task 3. ✓
- Build verification → Task 4. ✓
- No new tests → explicitly absent, matches the spec's "No new tests" line. ✓
- No ViewModel changes → Tasks 1–4 never touch `ContactListViewModel.kt`. ✓
- No navigation, no click handler logic, no toast → the `onClick = {}` literal in Task 2 enforces this. ✓
- No letter-group headers → not introduced anywhere. ✓

**2. Placeholder scan:**

- No TBD/TODO/"fill in later" markers. ✓
- No "similar to Task N" hand-waves — every code block is written out. ✓
- No "add appropriate error handling" vague steps — there is no error path in a visual placeholder, so this isn't relevant. ✓

**3. Type consistency:**

- `ContactEntryItem(iconResId: Int, title: String, onClick: () -> Unit)` is defined in Task 2 and called twice in `ContactEntryBlock` (Task 2) with the correct types. ✓
- `R.drawable.ic_contact_new_friend` and `R.drawable.ic_contact_group_chat` referenced in Task 2 are created in Task 1. Order is correct (Task 1 commits before Task 2). ✓
- The `Spacer` import added in Task 2 Step 1 is used in Task 3. ✓
- The `Modifier.padding(start = ...)` argument type is `Dp`; the expression `EdgePadding + ListAvatarSize + Gutter` evaluates to `Dp` because all three are `Dp` constants. Matches the signature in `androidx.compose.foundation.layout.padding`. ✓

No issues found. Plan is ready for execution.
