# Top Bar Gray and Contact Separator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Messages and Contacts top tabs' top bars gray, and add a visible gray full-width separator bar between 群聊 and the first contact row.

**Architecture:** Add an optional `containerColor: Color = ByteImColors.Surface` parameter to the shared `ByteImTopBar` Composable. The two top-level tab callers (Messages and Contacts) pass `ByteImColors.AppBackground` to make their top bars gray; all other call sites keep the default white. In `ContactListScreen`, replace the existing invisible 8dp `Spacer` after `ContactEntryBlock` with a `Spacer` that is `fillMaxWidth()` and has `ByteImColors.AppBackground` as its background.

**Tech Stack:** Jetpack Compose, Material 3, Android Kotlin. No new dependencies, no new tests, no new files. The human partner will handle build, install, and on-device visual verification after the implementation is committed.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/codex/im/ui/ByteImUi.kt` | Modify | Add `containerColor: Color = ByteImColors.Surface` parameter to `ByteImTopBar`; use it in the `Box` `background(...)` call instead of the hard-coded `ByteImColors.Surface`. |
| `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt` | Modify | Pass `containerColor = ByteImColors.AppBackground` to its `ByteImTopBar` call (Messages tab). No new imports — `ByteImColors` is already imported. |
| `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt` | Modify | (a) Pass `containerColor = ByteImColors.AppBackground` to the `ByteImTopBar` call inside `ContactsTopBar` (Contacts tab). (b) Replace the invisible 8dp `Spacer` in the `LazyColumn`'s first `item { ... }` with a `Spacer` that is `fillMaxWidth()` and has `ByteImColors.AppBackground` as its background. |

No new files. No new tests. The `background` Compose import in `ContactListScreen.kt` is already present from the previous task (commit `df56f59`); no import changes are needed.

---

## Task 1: Add `containerColor` parameter to `ByteImTopBar` and use it in the two top-level tabs

**Files:**
- Modify: `app/src/main/java/com/codex/im/ui/ByteImUi.kt:67-82` (signature + first `background(...)` line)
- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt:118-149` (add `containerColor` argument to the `ByteImTopBar` call)
- Modify: `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt:103-129` (add `containerColor` argument to the `ByteImTopBar` call inside `ContactsTopBar`)

- [ ] **Step 1: Add the `containerColor` parameter to `ByteImTopBar` in `ByteImUi.kt`**

Open `app/src/main/java/com/codex/im/ui/ByteImUi.kt`. Find the `ByteImTopBar` Composable signature at line 68:

```kotlin
@Composable
fun ByteImTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<@Composable () -> Unit> = emptyList(),
    centerTitle: Boolean = false
) {
```

Change it to add the new parameter as the last one:

```kotlin
@Composable
fun ByteImTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<@Composable () -> Unit> = emptyList(),
    centerTitle: Boolean = false,
    containerColor: Color = ByteImColors.Surface
) {
```

The `Color` type is already imported at the top of the file (line 24: `import androidx.compose.ui.graphics.Color`).

- [ ] **Step 2: Use `containerColor` in the `Box` `background(...)` call in `ByteImUi.kt`**

Still in `ByteImUi.kt`, find the `Box` modifier chain at line 77–82:

```kotlin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ByteImDimensions.TopBarHeight)
            .background(ByteImColors.Surface)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        contentAlignment = Alignment.Center
    ) {
```

Change the `.background(ByteImColors.Surface)` line to `.background(containerColor)`:

```kotlin
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ByteImDimensions.TopBarHeight)
            .background(containerColor)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        contentAlignment = Alignment.Center
    ) {
```

- [ ] **Step 3: Pass `containerColor = ByteImColors.AppBackground` from the Messages tab in `ConversationListScreen.kt`**

Open `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`. Find the `ByteImTopBar` call starting at line 118:

```kotlin
    ByteImTopBar(
        title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
        centerTitle = true,
        actions = listOf(
            {
                // 搜索图标：当前为视觉占位，后续接搜索路由
                IconButton(
                    onClick = { },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = MessageTopBarActionPolicy.searchIconResId),
                        contentDescription = "搜索",
                        tint = ByteImColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            ...
        )
    )
```

Add the new `containerColor` argument on a new line between `centerTitle = true,` and `actions = listOf(...)`:

```kotlin
    ByteImTopBar(
        title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
        centerTitle = true,
        containerColor = ByteImColors.AppBackground,
        actions = listOf(
            {
                // 搜索图标：当前为视觉占位，后续接搜索路由
                IconButton(
                    onClick = { },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = MessageTopBarActionPolicy.searchIconResId),
                        contentDescription = "搜索",
                        tint = ByteImColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            ...
        )
    )
```

`ByteImColors` is already imported in this file (line 36: `import com.codex.im.ui.ByteImColors`). No import changes needed.

- [ ] **Step 4: Pass `containerColor = ByteImColors.AppBackground` from the Contacts tab in `ContactListScreen.kt`**

Open `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`. Find the `ByteImTopBar` call inside `ContactsTopBar` starting at line 103:

```kotlin
    ByteImTopBar(
        title = "通讯录",
        centerTitle = true,
        actions = listOf(
            {
                // 搜索图标：当前为视觉占位
                IconButton(
                    onClick = { },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = ContactTopBarActionPolicy.searchIconResId),
                        ...
                    )
                }
            },
            ...
        )
    )
```

Add the new `containerColor` argument on a new line between `centerTitle = true,` and `actions = listOf(...)`:

```kotlin
    ByteImTopBar(
        title = "通讯录",
        centerTitle = true,
        containerColor = ByteImColors.AppBackground,
        actions = listOf(
            {
                // 搜索图标：当前为视觉占位
                IconButton(
                    onClick = { },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = ContactTopBarActionPolicy.searchIconResId),
                        ...
                    )
                }
            },
            ...
        )
    )
```

`ByteImColors` is already imported in this file. No import changes needed.

- [ ] **Step 5: Compile-check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If the build fails with `Unresolved reference: containerColor` or similar, re-read the diff and check that the parameter was added in the right position in the `ByteImTopBar` signature.

The unit test task (`:app:testDebugUnitTest`) is expected to still fail at the `compileDebugUnitTestKotlin` step due to the pre-existing `ChatDisplayPolicyTest.kt` issue (last modified at commit `aca0e37`, before this work). This is out of scope — do not attempt to fix it.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/codex/im/ui/ByteImUi.kt \
        app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt \
        app/src/main/java/com/codex/im/contacts/ContactListScreen.kt
git commit -m "feat(ui): gray top bar for Messages and Contacts tabs"
```

---

## Task 2: Replace the invisible 8dp Spacer with a visible gray separator bar

**Files:**
- Modify: `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt:74-78` (the first `item { ... }` block in the `LazyColumn`)

- [ ] **Step 1: Replace the Spacer modifier chain**

Open `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`. Find the first `item { ... }` block in the `LazyColumn` (the block added by commit `2330142`). It currently reads:

```kotlin
                item {
                    ContactEntryBlock()
                    Spacer(modifier = Modifier.height(8.dp))
                }
```

Replace it with:

```kotlin
                item {
                    ContactEntryBlock()
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(ByteImColors.AppBackground)
                    )
                }
```

Three changes from the original:

1. The `Spacer` is no longer a single-line `Spacer(modifier = Modifier.height(8.dp))`; it now uses a multi-line `Modifier` chain.
2. The `Modifier` chain adds `.fillMaxWidth()` so the bar spans the full width of the row.
3. The chain adds `.background(ByteImColors.AppBackground)` so the bar is visible as a gray band.

The `.height(8.dp)` is preserved so the bar occupies the same vertical space the invisible gap used to.

- [ ] **Step 2: Verify required imports are present**

The `Spacer` change needs `fillMaxWidth` (from `androidx.compose.foundation.layout`) and `background` (from `androidx.compose.foundation.background`). Both should already be imported in `ContactListScreen.kt`:

- `background` was added in commit `df56f59` for the `ContactEntryItem` icon tile.
- `fillMaxWidth` is in the existing `androidx.compose.foundation.layout.*` import block at the top of the file.

Verify with:

```bash
cd d:/Desktop/engine/IM
grep -n "import androidx.compose.foundation.background" app/src/main/java/com/codex/im/contacts/ContactListScreen.kt
grep -n "import androidx.compose.foundation.layout.fillMaxWidth\|fillMaxWidth" app/src/main/java/com/codex/im/contacts/ContactListScreen.kt
```

Expected: the `background` import line is present, and `fillMaxWidth` is either imported explicitly or via a wildcard `androidx.compose.foundation.layout.*` import. If `fillMaxWidth` is missing entirely, add `import androidx.compose.foundation.layout.fillMaxWidth` to the import block — but this is highly unlikely given the existing usage of `fillMaxWidth` in `ContactEntryItem` and `ContactEntryBlock`.

- [ ] **Step 3: Compile-check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If the build fails with `Unresolved reference: fillMaxWidth` or `Unresolved reference: background`, add the missing imports per Step 2.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/codex/im/contacts/ContactListScreen.kt
git commit -m "feat(contacts): add gray separator between placeholder block and contacts"
```

---

## Self-Review

**1. Spec coverage:**

- Add `containerColor: Color = ByteImColors.Surface` parameter to `ByteImTopBar` → Task 1 Step 1. ✓
- Replace hard-coded `.background(ByteImColors.Surface)` with `.background(containerColor)` → Task 1 Step 2. ✓
- Update `ConversationListScreen.kt` to pass `containerColor = ByteImColors.AppBackground` → Task 1 Step 3. ✓
- Update `ContactListScreen.kt` `ContactsTopBar` to pass `containerColor = ByteImColors.AppBackground` → Task 1 Step 4. ✓
- Replace invisible 8dp `Spacer` with visible gray `Spacer` in `ContactListScreen` → Task 2 Step 1. ✓
- The other 4 `ByteImTopBar` call sites (`ChatScreen`, `GroupCreateScreen`, 3 in `MeScreen`) are not modified → not in any task, matching the spec's "out of scope". ✓
- No new tests, no new files, no behavior changes → Tasks 1 and 2 only add a defaulted parameter and a layout modifier, matching the spec. ✓
- Build/install/visual verification handled by the human → not part of any task, matching the user's explicit request. ✓

**2. Placeholder scan:**

- No TBD/TODO/"fill in later" markers. ✓
- No "similar to Task N" hand-waves — the `ByteImTopBar` call patterns in Task 1 Steps 3 and 4 show the surrounding code (search and add buttons) in full. ✓
- No vague steps. ✓
- The single conditional check in Task 2 Step 2 (grep for `fillMaxWidth` import) is a verification step, not a placeholder, and explicitly tells the engineer what to do if the import is missing. ✓

**3. Type consistency:**

- `containerColor: Color = ByteImColors.Surface` is added in Task 1 Step 1 and used in Task 1 Step 2 and passed in Task 1 Steps 3 and 4. All references match. ✓
- The `ByteImColors` references in Task 1 Steps 3 and 4 and Task 2 Step 1 use the same qualified name `com.codex.im.ui.ByteImColors` (resolved via the existing import in each file). ✓
- `Spacer` in Task 2 Step 1 uses the same import path as the existing `Spacer` import added in commit `df56f59`. ✓

No issues found. Plan is ready for execution.
