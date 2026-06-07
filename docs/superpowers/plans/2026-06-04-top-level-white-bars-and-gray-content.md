# Top-Level White Bars and Gray Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore white top-level chrome for Messages and Contacts, render the Messages and Contacts scrollable content layers on the shared light-gray app background, and preserve unread badges, network status notice behavior, and bottom-navigation behavior.

**Architecture:** Keep the existing top-level screen structure (`ConversationListScreen` / `ContactListScreen` plus `TopLevelBottomBar`) and make only localized styling changes. Reuse `ByteImTopBar`'s existing `containerColor` support, add matching `containerColor` support to `ByteImListSurface`, switch the Messages and Contacts top bars back to white explicitly, and remove the obsolete special gray contact separator treatment so spacing reads correctly against the new gray content container.

**Tech Stack:** Jetpack Compose, Material 3, Android Kotlin. No new dependencies. Verification is compile-based plus human visual validation on device.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/buyansong/im/ui/ByteImUi.kt` | Modify | Add an optional `containerColor` parameter to `ByteImListSurface` while keeping the default white background for existing non-top-level callers. |
| `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt` | Modify | Make the Messages top bar explicitly white and make the Messages list container explicitly light gray without touching unread-count or connection-status behavior. |
| `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt` | Modify | Make the Contacts top bar explicitly white, make the Contacts list container explicitly light gray, and remove the obsolete special gray separator-bar treatment after `ContactEntryBlock()`. |

`app/src/main/java/com/buyansong/im/MainActivity.kt` should not be edited in this task. Its `TopLevelBottomBar` is already fixed white and should remain a regression guard rather than a change target.

---

### Task 1: Add list-surface color support for top-level content containers

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/ui/ByteImUi.kt`

- [ ] **Step 1: Add `containerColor` to `ByteImListSurface`**

Open `app/src/main/java/com/buyansong/im/ui/ByteImUi.kt` and change:

```kotlin
@Composable
fun ByteImListSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ByteImColors.Surface)
    ) {
        content()
    }
}
```

to:

```kotlin
@Composable
fun ByteImListSurface(
    modifier: Modifier = Modifier,
    containerColor: Color = ByteImColors.Surface,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
    ) {
        content()
    }
}
```

This keeps existing callers white by default and gives the two top-level list screens an opt-in way to render on `ByteImColors.AppBackground`.

- [ ] **Step 2: Compile-check the shared component signature change**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin
```

Expected: the build may fail at callers until the next task is complete, but `ByteImUi.kt` itself should have no syntax or import errors. If the compiler flags `Color` usage, re-check that `import androidx.compose.ui.graphics.Color` is still present at the top of the file.

---

### Task 2: Restore white Messages chrome and move the Messages list onto the gray content layer

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`

- [ ] **Step 1: Make the Messages list container explicitly gray**

Open `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt` and change:

```kotlin
        ByteImListSurface(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.conversationId }) { item ->
                    ConversationRow(
                        item = item,
                        onClick = {
                            viewModel.openConversation(if (item.isGroup) item.conversationId else item.peerId)
                        }
                    )
                    HorizontalDivider(color = ByteImColors.Divider)
                }
            }
        }
```

to:

```kotlin
        ByteImListSurface(
            modifier = Modifier.weight(1f),
            containerColor = ByteImColors.AppBackground
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.conversationId }) { item ->
                    ConversationRow(
                        item = item,
                        onClick = {
                            viewModel.openConversation(if (item.isGroup) item.conversationId else item.peerId)
                        }
                    )
                    HorizontalDivider(color = ByteImColors.Divider)
                }
            }
        }
```

Do not move or edit the `ConversationConnectionStatusPolicy.visibleLabel(...)` block or the `ByteImSystemNotice(...)` call above it.

- [ ] **Step 2: Make the Messages top bar explicitly white**

In the same file, change:

```kotlin
    ByteImTopBar(
        title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
        centerTitle = true,
        containerColor = ByteImColors.AppBackground,
        actions = listOf(
```

to:

```kotlin
    ByteImTopBar(
        title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
        centerTitle = true,
        containerColor = ByteImColors.Surface,
        actions = listOf(
```

Do not change:

```kotlin
title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount)
```

Do not change the search button, plus button, or `ConversationCreateMenu(...)`.

- [ ] **Step 3: Compile-check the Messages screen changes**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin
```

Expected: `ConversationListScreen.kt` compiles, and there are no errors around `ByteImSystemNotice`, unread title formatting, or the new `ByteImListSurface` parameter.

---

### Task 3: Restore white Contacts chrome, move the Contacts list onto the gray content layer, and remove the obsolete separator-bar treatment

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`

- [ ] **Step 1: Make the Contacts list container explicitly gray**

Open `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt` and change:

```kotlin
        ByteImListSurface(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
```

to:

```kotlin
        ByteImListSurface(
            modifier = Modifier.weight(1f),
            containerColor = ByteImColors.AppBackground
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
```

- [ ] **Step 2: Make the Contacts top bar explicitly white**

In the same file, change:

```kotlin
    ByteImTopBar(
        title = "通讯录",
        centerTitle = true,
        containerColor = ByteImColors.AppBackground,
        actions = listOf(
```

to:

```kotlin
    ByteImTopBar(
        title = "通讯录",
        centerTitle = true,
        containerColor = ByteImColors.Surface,
        actions = listOf(
```

Do not change the search button, plus button, or `ConversationCreateMenu(...)`.

- [ ] **Step 3: Remove the special gray separator-bar treatment**

Still in `ContactListScreen.kt`, change:

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

to:

```kotlin
                item {
                    ContactEntryBlock()
                    Spacer(modifier = Modifier.height(8.dp))
                }
```

The spacer remains 8dp tall, but it no longer needs its own explicit gray fill because the parent list container is now gray.

- [ ] **Step 4: Compile-check the Contacts screen changes**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin
```

Expected: `ContactListScreen.kt` compiles with the new `ByteImListSurface` parameter and no obsolete `Spacer` modifier issues.

---

### Task 4: Final regression verification and commit

**Files:**
- Verify only: `app/src/main/java/com/buyansong/im/MainActivity.kt`
- Verify only: `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
- Verify only: `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`

- [ ] **Step 1: Re-run the compile target after all edits**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Audit the unchanged behavior constraints before commit**

Confirm by inspection that these lines or behaviors were not altered:

```kotlin
title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount)
```

```kotlin
ConversationConnectionStatusPolicy.visibleLabel(state.connectionStatus)
```

```kotlin
ByteImSystemNotice(
    text = statusLabel,
    modifier = Modifier.padding(vertical = 8.dp)
)
```

```kotlin
NavigationBar(
    containerColor = ByteImColors.Surface,
    tonalElevation = 0.dp,
    modifier = Modifier.height(ByteImDimensions.BottomBarHeight)
)
```

Also confirm the unread badge logic in `BottomNavigationIcon(...)` was not touched.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/buyansong/im/ui/ByteImUi.kt \
        app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt \
        app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt \
        docs/superpowers/specs/2026-06-04-top-level-white-bars-and-gray-content-design.md \
        docs/superpowers/plans/2026-06-04-top-level-white-bars-and-gray-content.md
git commit -m "docs: replace gray top-bar scheme with white chrome plan"
```
