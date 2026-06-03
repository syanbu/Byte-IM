# Chat Back Nav Bottom Bar Timing Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the bottom navigation bar from the global `Scaffold` into each of the three top-level destination Composables (Conversations, Contacts, Me) so the bar and the screen content compose in the same pass and no longer race when popping back from Chat.

**Architecture:** In `MainActivity.kt`, extract the bottom-bar rendering into a new private `TopLevelBottomBar` Composable. Remove the `bottomBar` slot from the global `Scaffold`. Wrap each of the three top-level destination bodies in a `Column` that contains the existing `*Screen` Composable (with `Modifier.fillMaxWidth().weight(1f)`) plus the new `TopLevelBottomBar` Composable.

**Tech Stack:** Jetpack Compose, Material 3, Android Kotlin. No new dependencies, no new tests, no new files. Build, install, and on-device visual verification are handled by the human partner after the implementation is complete. **Do not run any git commands — the human partner handles all git operations; the implementation is left as an unstaged working-tree modification.**

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `app/src/main/java/com/codex/im/MainActivity.kt` | Modify | (a) Add a new private `TopLevelBottomBar` Composable. (b) Remove the `bottomBar` slot from the global `Scaffold` in `AuthenticatedImNavHost`. (c) Wrap each of the three top-level destination bodies (`Conversations`, `Contacts`, `Me`) in a `Column` that includes the screen Composable and the new bottom bar. |

No new files. No new tests. No other files are touched.

---

## Task 1: Apply the refactor to `MainActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt` (multiple distinct regions: add new Composable, remove Scaffold slot, update three destinations)

- [ ] **Step 1: Add the new `TopLevelBottomBar` private Composable**

Add this Composable to `app/src/main/java/com/codex/im/MainActivity.kt`, placed immediately above the existing private `BottomNavigationIcon` Composable (which currently lives at lines 555–580). The new Composable is a verbatim extraction of what the `Scaffold`'s `bottomBar` slot currently renders, with two small refactors: `currentRoute` is a parameter instead of being closed over, and the `navController.navigateToTopLevelTab(tab.route)` call is abstracted to an `onNavigateToTab` callback.

```kotlin
@Composable
private fun TopLevelBottomBar(
    currentRoute: String?,
    unreadMessagesCount: Int,
    onNavigateToTab: (String) -> Unit
) {
    NavigationBar(
        containerColor = ByteImColors.Surface,
        tonalElevation = 0.dp,
        modifier = Modifier.height(ByteImDimensions.BottomBarHeight)
    ) {
        BottomNavigationSpec.topLevelItems.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onNavigateToTab(tab.route) },
                label = { Text(tab.label) },
                icon = {
                    BottomNavigationIcon(
                        spec = tab,
                        unreadCount = if (tab.route == BottomNavigationSpec.messages.route) unreadMessagesCount else 0
                    )
                }
            )
        }
    }
}
```

The `NavigationBar`, `NavigationBarItem`, `BottomNavigationSpec`, `BottomNavigationIcon`, `Text`, `ByteImColors`, `ByteImDimensions`, and `dp` symbols are all already imported in this file. No new imports are required.

- [ ] **Step 2: Remove the `bottomBar` slot from the global `Scaffold`**

In `app/src/main/java/com/codex/im/MainActivity.kt`, inside the `AuthenticatedImNavHost` Composable, find the `Scaffold(...)` call (currently at lines 338–365) and change it from:

```kotlin
    Scaffold(
        containerColor = ByteImColors.AppBackground,
        bottomBar = {
            if (BottomNavigationSpec.topLevelItems.any { it.route == currentRoute }) {
                NavigationBar(
                    containerColor = ByteImColors.Surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.height(ByteImDimensions.BottomBarHeight)
                ) {
                    BottomNavigationSpec.topLevelItems.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigateToTopLevelTab(tab.route)
                            },
                            label = { Text(tab.label) },
                            icon = {
                                BottomNavigationIcon(
                                    spec = tab,
                                    unreadCount = if (tab.route == BottomNavigationSpec.messages.route) unreadMessagesCount else 0
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            ...
        )
    }
```

to:

```kotlin
    Scaffold(
        containerColor = ByteImColors.AppBackground
    ) { innerPadding ->
        NavHost(
            ...
        )
    }
```

The `bottomBar` argument and its entire `if (...) { NavigationBar(...) { ... } }` block are deleted. Everything else (the `containerColor`, the `innerPadding` content lambda, the `NavHost` call) stays exactly as it is.

After this step, the `Scaffold` no longer has a bottom bar. The top-level destinations do not yet have one in their bodies either, so visually the bar will be missing on Conversations / Contacts / Me. This intermediate state is expected — Step 3 re-introduces the bar inside each destination.

- [ ] **Step 3: Wrap the `Conversations` destination body in a `Column` with `TopLevelBottomBar`**

Find the `composable(SelfHostedImRoute.Conversations.route) { ... }` block (currently at lines 378–409). Change the body from:

```kotlin
            composable(SelfHostedImRoute.Conversations.route) {
                val conversationListViewModel = remember(session.userId) {
                    ConversationListViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        profileRepository = profileRepository,
                        groupRepository = groupRepository,
                        validSessionProvider = validSessionProvider,
                        thumbnailPreloader = CoilChatThumbnailPreloader(context)
                    )
                }
                val conversationState by conversationListViewModel.state.collectAsState()
                ConversationListScreen(
                    viewModel = conversationListViewModel,
                    state = conversationState,
                    unreadCount = unreadMessagesCount,
                    onStartGroupChat = {
                        navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenConversation = { conversationId ->
                        SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
                    }
                )
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Conversations.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }
```

to:

```kotlin
            composable(SelfHostedImRoute.Conversations.route) {
                val conversationListViewModel = remember(session.userId) {
                    ConversationListViewModel(
                        session = session,
                        repository = messageRepository,
                        connection = connection,
                        profileRepository = profileRepository,
                        groupRepository = groupRepository,
                        validSessionProvider = validSessionProvider,
                        thumbnailPreloader = CoilChatThumbnailPreloader(context)
                    )
                }
                val conversationState by conversationListViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    ConversationListScreen(
                        viewModel = conversationListViewModel,
                        state = conversationState,
                        unreadCount = unreadMessagesCount,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onStartGroupChat = {
                            navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                                launchSingleTop = true
                            }
                        },
                        onOpenConversation = { conversationId ->
                            SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
                        }
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Conversations.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }
```

Two changes:

1. Wrap the `ConversationListScreen` call in `Column(modifier = Modifier.fillMaxSize()) { ... }` and add a `TopLevelBottomBar` call inside the `Column` after `ConversationListScreen`.
2. Add `modifier = Modifier.fillMaxWidth().weight(1f)` to the `ConversationListScreen` call as the 4th argument (after `state` and `unreadCount` — the `ConversationListScreen` Composable signature is `viewModel, state, unreadCount, modifier, onStartGroupChat, onOpenConversation`).

The `TopLevelRouteBackHandler` call stays outside the `Column` as a sibling of the `composable { }` body's other top-level calls.

- [ ] **Step 4: Wrap the `Contacts` destination body in a `Column` with `TopLevelBottomBar`**

Find the `composable(SelfHostedImRoute.Contacts.route) { ... }` block (currently at lines 411–438). Change the body from:

```kotlin
            composable(SelfHostedImRoute.Contacts.route) {
                val contactListViewModel = remember(session.userId) {
                    ContactListViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        contactResolver = DemoContactResolver::contactsFor,
                        validSessionProvider = validSessionProvider
                    )
                }
                val contactState by contactListViewModel.state.collectAsState()
                ContactListScreen(
                    viewModel = contactListViewModel,
                    state = contactState,
                    onStartGroupChat = {
                        navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                            launchSingleTop = true
                        }
                    },
                    onOpenContact = { peerUserId ->
                        SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)?.let(navController::navigateToChat)
                    }
                )
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Contacts.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }
```

to:

```kotlin
            composable(SelfHostedImRoute.Contacts.route) {
                val contactListViewModel = remember(session.userId) {
                    ContactListViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        contactResolver = DemoContactResolver::contactsFor,
                        validSessionProvider = validSessionProvider
                    )
                }
                val contactState by contactListViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    ContactListScreen(
                        viewModel = contactListViewModel,
                        state = contactState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onStartGroupChat = {
                            navController.navigate(SelfHostedImRoute.GroupCreate.route) {
                                launchSingleTop = true
                            }
                        },
                        onOpenContact = { peerUserId ->
                            SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)?.let(navController::navigateToChat)
                        }
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
                TopLevelRouteBackHandler(
                    route = SelfHostedImRoute.Contacts.route,
                    currentRoute = currentRoute,
                    activity = activity
                )
            }
```

The `ContactListScreen` Composable signature is `viewModel, state, modifier, onStartGroupChat, onOpenContact` — `modifier` is the 3rd parameter. The new `modifier = Modifier.fillMaxWidth().weight(1f)` argument goes between `state` and `onStartGroupChat`.

- [ ] **Step 5: Wrap the `Me` destination body in a `Column` with `TopLevelBottomBar`**

Find the `composable(SelfHostedImRoute.Me.route) { ... }` block (currently at lines 462–480). Change the body from:

```kotlin
            composable(SelfHostedImRoute.Me.route) {
                val meViewModel = remember(session.userId) {
                    MeViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        avatarUploadApi = avatarUploadApi,
                        validSessionProvider = validSessionProvider
                    )
                }
                val meState by meViewModel.state.collectAsState()
                MeScreen(
                    viewModel = meViewModel,
                    state = meState,
                    onMoveTaskToBack = {
                        activity?.moveTaskToBack(true)
                    },
                    onLogout = onLogout
                )
            }
```

to:

```kotlin
            composable(SelfHostedImRoute.Me.route) {
                val meViewModel = remember(session.userId) {
                    MeViewModel(
                        session = session,
                        profileRepository = profileRepository,
                        avatarUploadApi = avatarUploadApi,
                        validSessionProvider = validSessionProvider
                    )
                }
                val meState by meViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    MeScreen(
                        viewModel = meViewModel,
                        state = meState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onMoveTaskToBack = {
                            activity?.moveTaskToBack(true)
                        },
                        onLogout = onLogout
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
            }
```

The `MeScreen` Composable signature is `viewModel, state, modifier, onMoveTaskToBack, onLogout` — `modifier` is the 3rd parameter. The new `modifier = Modifier.fillMaxWidth().weight(1f)` argument goes between `state` and `onMoveTaskToBack`.

Note: the `Me` destination has no `TopLevelRouteBackHandler` in the current code; this matches the spec and is preserved as-is.

- [ ] **Step 6: Compile-check**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. If the build fails:

- `Unresolved reference: TopLevelBottomBar` — Step 1 was skipped or the Composable was placed in the wrong file.
- `Not enough information to infer type variable` near `Modifier.fillMaxWidth().weight(1f)` — the `weight(1f)` call needs to be inside a `Column { }` scope. Re-check that the wrapper `Column` is present and that the `*Screen` Composable call is inside it.
- `Type mismatch` on the `modifier` argument — confirm the parameter is in the 3rd or 4th position of the `*Screen` Composable's signature as documented in Steps 3, 4, and 5.

The unit test task (`:app:testDebugUnitTest`) is expected to still fail at the `compileDebugUnitTestKotlin` step due to the pre-existing `ChatDisplayPolicyTest.kt` issue (last modified at commit `aca0e37`, before this work). This is out of scope — do not attempt to fix it.

- [ ] **Step 7: Do NOT commit**

**Do not run `git add` or `git commit` or any other git command.** Leave the modified file in the working tree as an unstaged change. The human partner will handle the commit and any subsequent git operations.

- [ ] **Step 8: Report back**

Report to the controller (not to the human partner) with:

- **Status:** DONE | DONE_WITH_CONCERNS | BLOCKED | NEEDS_CONTEXT
- A summary of the changes made (the new Composable, the Scaffold change, and the three destination changes).
- The compile check result.
- Confirmation that no git commands were run.
- Any deviations from the spec or plan, with justification.
- Self-review findings (see below).

## Self-Review

Before reporting back, verify each item:

**Completeness:**
- Did I add the `TopLevelBottomBar` Composable with the exact signature and body shown in Step 1?
- Did I remove the `bottomBar` slot from the global `Scaffold` (Step 2)?
- Did I wrap all three top-level destinations (Conversations, Contacts, Me) in `Column` with `TopLevelBottomBar` and the correct `modifier` argument (Steps 3, 4, 5)?
- Did I leave the `Chat` and `GroupCreate` destinations untouched?

**Discipline:**
- Did I avoid any git commands (no `git add`, `git commit`, `git status`, `git diff`, etc.)?
- Did I avoid any other file changes outside `MainActivity.kt`?
- Did I avoid adding any new imports?
- Did I avoid adding any new state, callbacks, or other Composable parameters beyond the spec?
- Did I preserve the `TopLevelRouteBackHandler` calls on the Conversations and Contacts destinations as siblings of the wrapper `Column`?

**Quality:**
- Is the new `TopLevelBottomBar` Composable placed in `MainActivity.kt` (not extracted to a new file)? The spec chose to keep it private to this file.
- Does the `Modifier.fillMaxWidth().weight(1f)` modifier chain appear in the correct parameter position for each `*Screen` Composable (4th for `ConversationListScreen`, 3rd for `ContactListScreen` and `MeScreen`)?
- Is the `Column(modifier = Modifier.fillMaxSize())` wrapper immediately inside the `composable { ... }` lambda body, wrapping the screen Composable call and the `TopLevelBottomBar` call?

**Testing:**
- Did the compile check succeed?
- Is the pre-existing test compile error in `ChatDisplayPolicyTest.kt` still out of scope (not touched)?

If any item fails, fix it before reporting.
