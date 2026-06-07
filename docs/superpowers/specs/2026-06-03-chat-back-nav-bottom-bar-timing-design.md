# Chat Back Navigation Bottom Bar Timing Fix Design

Date: 2026-06-03
Branch: `redesign-ui`

## Goal

Fix a navigation/render-order bug: when the user is in a single chat screen and taps the back button, the bottom navigation bar pops in first, and the messages tab's conversation list re-renders visibly afterwards. The user perceives a "two-step" transition (bottom bar appears, then content settles) instead of a single coherent transition.

The fix moves the bottom navigation bar from the global `Scaffold`'s `bottomBar` slot into each top-level destination (`Conversations`, `Contacts`, `Me`) as part of that destination's own layout. The bar and the screen content then live in the same Composable subtree and are rendered in the same compose pass, so they appear together.

No new state, no new event flow, no new tests, no behavior changes beyond visual ordering.

## Root Cause

`MainActivity.kt:338-364` currently renders the bottom bar inside the global `Scaffold`'s `bottomBar` slot, conditionally on `currentRoute` matching a top-level route. The `NavHost` content (the destination composables) is rendered inside the `Scaffold`'s content lambda.

When the back stack pops from `chat/...` back to `conversations`:

1. `currentBackStackEntryAsState()` emits the new entry, `currentRoute` becomes `"conversations"`.
2. The `Scaffold` recomposes. Its `bottomBar` slot's condition (`BottomNavigationSpec.topLevelItems.any { it.route == currentRoute }`) becomes true. The `NavigationBar` Composable is composed and laid out — it has no data dependencies, so this is fast (effectively 1 frame).
3. The `Scaffold`'s content lambda is called, which hands control to `NavHost`. The `NavHost` resumes the `Conversations` composable, which re-subscribes to its `ViewModel`'s state and re-lays out the `LazyColumn` — this needs 1–2 additional frames because the Composable has more depth, state subscriptions, and child layout.

The user sees the bottom bar appear "instantly" (frame N) while the conversation list area is still settling (frame N+1, N+2). On devices with frame jitter this reads as "bottom bar pops in, then the list catches up".

## Source Context

- Affected file: `app/src/main/java/com/buyansong/im/MainActivity.kt`
  - `Scaffold` and its `bottomBar` slot: lines 338–364
  - `BottomNavigationIcon` (existing private Composable): lines 555–580
  - `navigateToTopLevelTab` (existing extension on `NavHostController`): lines 524–532
  - Top-level destinations: lines 378–409 (`Conversations`), 411–438 (`Contacts`), 462–480 (`Me`)
  - Non-top-level destinations (unchanged): lines 440–460 (`GroupCreate`), 482–507 (`Chat`)
- Navigation route definitions: `app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt`
- Bottom navigation spec (tab metadata): `app/src/main/java/com/buyansong/im/BottomNavigationSpec.kt`
- UI constants: `app/src/main/java/com/buyansong/im/ui/ByteImUi.kt` (`ByteImColors.Surface`, `ByteImDimensions.BottomBarHeight`)
- Screen Composables (each already accepts `modifier: Modifier = Modifier`):
  - `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
  - `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`
  - `app/src/main/java/com/buyansong/im/profile/MeScreen.kt`

## Scope

In scope:

- `MainActivity.kt` — extract the bottom-bar rendering into a new private `TopLevelBottomBar` Composable. Remove the `bottomBar` slot from the global `Scaffold`. Wrap each of the three top-level destination bodies in a `Column` that contains the existing `*Screen` Composable (with `Modifier.fillMaxWidth().weight(1f)`) and the new `TopLevelBottomBar` Composable.

Out of scope:

- `Chat` and `GroupCreate` destinations and their Composables.
- `ChatBackPolicy`, `TopLevelBackPolicy`, `TopLevelRouteBackHandler`.
- `BottomNavigationSpec` (unchanged).
- `BottomNavigationIcon` (kept as-is, used internally by `TopLevelBottomBar`).
- `navigateToTopLevelTab` (kept as-is, called by the new bar via a callback).
- All ViewModels, repositories, navigation routes, protocol, database, or any non-UI logic.
- Any transition / animation, including `NavHost`'s `enterTransition` / `popEnterTransition` (currently `EnterTransition.None`, left as-is).
- Build, install, and on-device verification — handled by the human partner, not part of the implementation work.
- The pre-existing test compile error in `app/src/test/java/com/buyansong/im/chat/ChatDisplayPolicyTest.kt` (unrelated to this change).

## Visual System

Reuse existing constants. No new colors or dimensions.

- Bottom bar container color: `ByteImColors.Surface` (white) — unchanged from today.
- Bottom bar height: `ByteImDimensions.BottomBarHeight` (64.dp) — unchanged.
- Bottom bar `tonalElevation`: 0.dp — unchanged.
- Unread badge color: `ByteImColors.BadgeRed` — unchanged.
- Each screen's `modifier: Modifier` parameter continues to receive `Modifier.fillMaxWidth().weight(1f)` from the wrapper `Column`. The screens' internal `Modifier.fillMaxSize()` chains remain unchanged, so they continue to fill the space the wrapper gives them.

## Component Change: New `TopLevelBottomBar` Composable

In `app/src/main/java/com/buyansong/im/MainActivity.kt`, add a new private Composable immediately above the existing `BottomNavigationIcon` (or just below `TopLevelRouteBackHandler` — exact placement is unimportant, group it with the other private navigation helpers).

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

The body is identical to what the `Scaffold`'s `bottomBar` slot currently renders (lines 342–362), with two surface changes:

1. The `currentRoute` value is now passed in as a parameter instead of being closed over from the enclosing scope.
2. The `navController.navigateToTopLevelTab(tab.route)` call is now abstracted to `onNavigateToTab(tab.route)` — the actual `navController` reference is provided by the caller, which has the right scope to call it.

The existing private `BottomNavigationIcon` Composable (lines 555–580) is unchanged and is reused internally by `TopLevelBottomBar`.

## Screen Changes in `MainActivity.kt`

### Change A: Remove `bottomBar` slot from global `Scaffold`

In the `AuthenticatedImNavHost` Composable, change the `Scaffold(...)` call so that it no longer passes a `bottomBar` argument. The current shape is:

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
        NavHost(...)
    }
```

It becomes:

```kotlin
    Scaffold(
        containerColor = ByteImColors.AppBackground
    ) { innerPadding ->
        NavHost(...)
    }
```

The `innerPadding` value the `Scaffold` passes to the content lambda will now no longer include the bottom bar's height (since the `bottomBar` slot is gone), but this is correct — the wrapper `Column` inside each top-level destination now manages the bottom bar height with `weight(1f)`.

### Change B: Wrap each top-level destination body in a `Column` with the new bar

For each of the three top-level destinations — `composable(SelfHostedImRoute.Conversations.route)`, `composable(SelfHostedImRoute.Contacts.route)`, `composable(SelfHostedImRoute.Me.route)` — the lambda body needs to:

1. Open a `Column(modifier = Modifier.fillMaxSize())`.
2. Call the existing `*Screen` Composable with `modifier = Modifier.fillMaxWidth().weight(1f)`.
3. Call the new `TopLevelBottomBar(currentRoute = currentRoute, unreadMessagesCount = unreadMessagesCount, onNavigateToTab = { tabRoute -> navController.navigateToTopLevelTab(tabRoute) })`.
4. Close the `Column`.

`TopLevelRouteBackHandler(...)` (and any other Composable calls that were siblings of the `*Screen` call) remain at the same level, after the closing `}` of the wrapper `Column`.

#### Conversations destination

Before:

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

After:

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

The two differences are:

1. A `Column(modifier = Modifier.fillMaxSize()) { ... }` wraps the `ConversationListScreen` call and adds a `TopLevelBottomBar` call.
2. The `ConversationListScreen` call gains `modifier = Modifier.fillMaxWidth().weight(1f)` as its first argument after `state` (the parameter order in the Composable signature is `viewModel, state, unreadCount, modifier, onStartGroupChat, onOpenConversation`).

The `TopLevelRouteBackHandler` call stays outside the `Column`, as a sibling of the `composable { }` body's other side-effect calls.

#### Contacts destination

Same shape as Conversations. The `ContactListScreen` Composable signature is `viewModel, state, modifier, onStartGroupChat, onOpenContact` — `modifier` is the 3rd parameter here, not the 4th. So the change is the same wrapper `Column`, with the `modifier = Modifier.fillMaxWidth().weight(1f)` argument placed at the 3rd position (after `state`).

Before:

```kotlin
            composable(SelfHostedImRoute.Contacts.route) {
                val contactListViewModel = remember(session.userId) { ... }
                val contactState by contactListViewModel.state.collectAsState()
                ContactListScreen(
                    viewModel = contactListViewModel,
                    state = contactState,
                    onStartGroupChat = { ... },
                    onOpenContact = { peerUserId -> ... }
                )
                TopLevelRouteBackHandler(...)
            }
```

After:

```kotlin
            composable(SelfHostedImRoute.Contacts.route) {
                val contactListViewModel = remember(session.userId) { ... }
                val contactState by contactListViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    ContactListScreen(
                        viewModel = contactListViewModel,
                        state = contactState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onStartGroupChat = { ... },
                        onOpenContact = { peerUserId -> ... }
                    )
                    TopLevelBottomBar(
                        currentRoute = currentRoute,
                        unreadMessagesCount = unreadMessagesCount,
                        onNavigateToTab = { tabRoute ->
                            navController.navigateToTopLevelTab(tabRoute)
                        }
                    )
                }
                TopLevelRouteBackHandler(...)
            }
```

#### Me destination

Same shape. The `MeScreen` Composable signature is `viewModel, state, modifier, onMoveTaskToBack, onLogout` — `modifier` is the 3rd parameter. The wrapper `Column` and the `TopLevelBottomBar` are added the same way.

Before:

```kotlin
            composable(SelfHostedImRoute.Me.route) {
                val meViewModel = remember(session.userId) { ... }
                val meState by meViewModel.state.collectAsState()
                MeScreen(
                    viewModel = meViewModel,
                    state = meState,
                    onMoveTaskToBack = { ... },
                    onLogout = onLogout
                )
            }
```

After:

```kotlin
            composable(SelfHostedImRoute.Me.route) {
                val meViewModel = remember(session.userId) { ... }
                val meState by meViewModel.state.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    MeScreen(
                        viewModel = meViewModel,
                        state = meState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onMoveTaskToBack = { ... },
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

The `Me` destination has no `TopLevelRouteBackHandler` in the current code — that pattern only exists for the two tabs that have a system-back behavior. Keep that as-is.

### Change C: Unchanged destinations

`Chat` (lines 482–507) and `GroupCreate` (lines 440–460) destinations are not modified. They continue to render full-screen inside the `NavHost` content area with no bottom bar.

## Why This Fixes the Bug

Putting `TopLevelBottomBar` and the `*Screen` Composable into the same `Column` means they live in the same Composable subtree. When the `NavHost` resumes the `Conversations` composable after popping back from `Chat`:

1. The whole wrapper `Column` is part of the destination's content.
2. The destination's content composes (and lays out, and draws) as one unit.
3. The bottom bar and the screen content go through the same compose pass — they appear in the same frame.

The `Scaffold`'s `bottomBar` slot is gone, so there is no longer a separate slot that becomes "true" out of sync with the content slot.

## Behavior

- Tapping a tab in the bottom bar: `onNavigateToTab` is invoked with the tab's route string, which calls `navController.navigateToTopLevelTab(tab.route)`. The current behavior is preserved exactly (this is the same call that was previously inside the `Scaffold`'s `bottomBar` slot).
- The unread badge on the Messages tab: still driven by `unreadMessagesCount`, still rendered by `BottomNavigationIcon`. Unchanged.
- The `selected` highlight on the active tab: still computed from `currentRoute == tab.route`. Unchanged.
- System back behavior on top-level tabs: still handled by `TopLevelRouteBackHandler` (unchanged for `Conversations` and `Contacts`; `Me` has none and stays that way).
- Tapping back from `Chat` to a top-level tab: the wrapper `Column` for that top-level tab is composed (or resumed) in one pass, so the bottom bar and the screen content appear in the same frame.

No new state, no new event flow, no new ViewModel, no new repository call, no new protocol, no new database query, no new test.

## Testing

No new tests. The change is purely visual ordering:

- The fix is exercised by the human partner on a real device (per the agreed workflow).
- The existing `ContactListViewModelTest` and any other unit tests are unaffected (no logic changed).
- The pre-existing test compile error in `app/src/test/java/com/buyansong/im/chat/ChatDisplayPolicyTest.kt` (introduced in commit `aca0e37`, before all this work) is unrelated to this change and remains out of scope.

## Risk

Low. The change is structural and localized:

- One new private Composable (`TopLevelBottomBar`) is added; it has the same body as the code it replaces.
- One `Scaffold` `bottomBar` slot is removed.
- Three destination lambdas gain a `Column` wrapper; the wrapped Composable gets a `modifier` argument.

If the wrapper `Column` were forgotten in any of the three top-level destinations, that destination would not have a bottom bar — visually obvious regression, immediately caught on device. There is no risk of "subtle" breakage; the only failure modes are visually obvious (missing bar, wrong bar height) or compile errors (Compose type-checks everything).

The Scaffold's `innerPadding` no longer includes a 64dp bottom inset. The non-top-level destinations (`Chat`, `GroupCreate`) do not consume `innerPadding.bottom` in any way that depends on it (the only consumer is the `NavHost`'s `Modifier.padding(innerPadding)` which adds the inset to the entire `NavHost`). Removing the inset gives `Chat` / `GroupCreate` 64dp more vertical space at the bottom — but their content is already `Modifier.fillMaxSize()` and they are not designed to leave space for a bottom bar, so this is correct and matches the previous behavior on the Chat route.
