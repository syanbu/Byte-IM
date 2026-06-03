# Contact Profile Display Design

Date: 2026-06-03
Branch: `redesign-ui`

## Goal

Insert a new **Contact Profile** page between the ByteIM Contacts tab and the single-chat screen. Tapping a contact row no longer jumps straight into chat; it first opens a read-only profile page that shows the peer's avatar, nickname, gender, and signature (everything from `UserProfile` except `userId` / `phone`). A sticky bottom "发送消息" button on that page is the only way to enter the chat screen for that peer.

This slice does not edit any profile data, does not introduce any new feature for adding friends, and does not change the Messages tab, group chat, or the chat screen itself.

## Scope

In scope:

- New route `contact-profile/{userId}` in `SelfHostedImRoute`.
- New ViewModel `ContactProfileViewModel` (read-only, no drafts).
- New Composable `ContactProfileScreen` rendering avatar, nickname, gender, and signature rows plus a sticky bottom "发送消息" button.
- New display policy `ContactProfileDisplayPolicy` for testable labels.
- Wire the new route into `MainActivity`'s `NavHost` and switch the Contacts tap handler to navigate to it.
- Unit tests for `ContactProfileViewModel` and `ContactProfileDisplayPolicy`.

Out of scope:

- Editing the peer's profile (read-only page; the contact is not the current user).
- Group member profile, mention-list profile, or any non-Contacts entry point. (See "Planned Reuse" below — these are not built now but the design leaves room for them.)
- Showing the peer's `userId` / `phone` (explicitly excluded by the requirement).
- New mock-server endpoints, schema, or seed data — `GET /users/{userId}` already returns the required fields, and the design degrades gracefully when `gender` / `signature` are null.
- Changes to chat list, conversation list, `ChatScreen`, or `ChatViewModel`.

## Source Context

Files relevant to the change:

- `app/src/main/java/com/codex/im/SelfHostedImRoute.kt` — sealed route definitions; `Chat` already shows the `{conversationId}` path-arg pattern.
- `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt` — current `onOpenContact` callback navigates straight to chat.
- `app/src/main/java/com/codex/im/contacts/ContactListViewModel.kt` — `openContact(userId)` sets `navigationTargetPeerId`.
- `app/src/main/java/com/codex/im/MainActivity.kt` — composes the `NavHost`, builds `ContactListViewModel`, wires `onOpenContact` to `SelfHostedImRoute.Chat.createSingleRoute(...)`.
- `app/src/main/java/com/codex/im/profile/ProfileRepository.kt` — `getProfile(accessToken, userId)` already returns a `UserProfile` with `nickname`, `avatarUrl`, `gender`, `signature` and persists it to `user_profiles`; `localProfile(userId)` reads from the same DAO.
- `app/src/main/java/com/codex/im/storage/StorageModels.kt` — `UserProfile` already carries `gender: Gender?` and `signature: String?`.
- `app/src/main/java/com/codex/im/ui/ByteImUi.kt` — `ByteImTopBar`, `ByteImListSurface`, `ByteImColors`, `ByteImDimensions`, `ByteImShapes`.
- `app/src/main/java/com/codex/im/ui/AvatarImage.kt` — existing avatar composable with byte and decoded-bitmap caching.
- `app/src/main/java/com/codex/im/profile/MeDisplayPolicy.kt` — existing labels and `genderLabel(...)` helper; used as a reference, not a dependency.
- `app/src/main/java/com/codex/im/profile/MeScreen.kt` — read-only profile row styling is the visual reference for the new page.

Mock-server is already sufficient:

- `mock-server/src/main/java/com/codex/imserver/auth/UserRecord.java` carries `gender` and `signature`.
- `mock-server/src/main/java/com/codex/imserver/auth/UserStore.java` stores them in the `users` table and the migration adds the columns if missing.
- `mock-server/src/main/java/com/codex/imserver/auth/AuthService.java` includes `gender` and `signature` in the JSON profile response (with `JsonNull` when unset).

No mock-server changes are required. Demo accounts simply have null `gender` and null `signature` today, which the design handles with the same `未设置` / `未填写` fallbacks the `Me` profile detail uses.

## Visual Design

### ASCII mockup

```
┌─────────────────────────────────────┐
│  ←    详细资料                      │   <- ByteImTopBar, title centered
├─────────────────────────────────────┤
│                                     │
│                                     │
│           ⬤  头像 (64dp)            │   <- 顶部 avatar 居中
│                                     │
│             昵称名                  │   <- nickname, titleLarge, 居中
│                                     │
├─────────────────────────────────────┤
│  昵称           昵称名              │   <- 资料行 (list surface)
├─────────────────────────────────────┤
│  性别           男                  │
├─────────────────────────────────────┤
│  个性签名       这是一段签名...      │   <- 多行 maxLines=2 截断
├─────────────────────────────────────┤
│                                     │
│              ... (空隙)             │
│                                     │
├─────────────────────────────────────┤
│  ┌───────────────────────────────┐  │
│  │         发送消息              │  │   <- sticky 主操作按钮
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

### Layout decisions

- **Avatar + nickname block** sits on the app background (`ByteImColors.AppBackground`, `#EDEDED`) above the white list surface, mirroring the gap-and-surface pattern used by `MeScreen.ProfileSummaryRow` → `ServicesRow`. This visually separates the "identity header" from the "data rows" the way WeChat does.
- **Data rows** (`昵称`, `性别`, `个性签名`) live inside a single `ByteImListSurface` block with `HorizontalDivider` between them. Each row uses the same `label + value + (no chevron)` pattern as `MeScreen.ProfileReadOnlyRow` / `ProfileValueRow`, but without the right-side `>` chevron and without `clickable` — the entire row is a static display.
- **`昵称` row is rendered** (the requirement says show nickname, but the existing `Me` profile uses a separate large-nickname display AND a small name row). To keep the page compact and avoid redundancy, the nickname is shown once in the header and the `昵称` row is replaced by a "auto" — actually, to honor the spec strictly ("昵称、头像、性别、个性签名" all displayed), the `昵称` row IS shown, even though the header also shows it. This matches the redundancy of the `Me` profile detail which lists 头像/昵称/性别/签名/ID. The header is a large presence; the row is a small confirmation. This is the same as the existing `Me` profile.
- **No ID row.** The requirement explicitly excludes ID. The `Me` profile has an ID row for the user's own reference; the peer profile does not.
- **Sticky bottom button** lives in a `Scaffold.bottomBar` so it stays above the system bottom inset and the keyboard never pushes it off. The button uses the green accent (`ByteImColors.PrimaryGreen`, `#07C160`) with white text, full-width, ~48dp tall, matching the `EditorActionButton` styling already used in the Me editor pages.
- **Loading state** is invisible when the cache hits: the cached avatar + nickname + rows are rendered immediately, and the only signal that a refresh is in flight is a thin `LinearProgressIndicator` under the top bar (or no indicator at all — see "Loading strategy" below). The user gets an instant page; the network round-trip happens behind the scenes.
- **Failure-with-no-cache** is the only state that needs a dedicated view: a centered "加载失败" message with a "重试" button.

### Visual system

- Use existing ByteIM UI constants only; no new colors or dimensions.
  - Top bar: `ByteImTopBar(title = "详细资料", onBack = onBack)` — reuses `ByteImDimensions.TopBarHeight` and `ByteImColors.Surface`.
  - Page background: `ByteImColors.AppBackground`.
  - List surface: `ByteImListSurface`.
  - Edge padding: `ByteImDimensions.EdgePadding` (`16.dp`).
  - Row height: `ByteImDimensions.ListItemHeight` (`72.dp`).
  - Avatar size in header: `ByteImDimensions.ProfileAvatarSize` (`64.dp`).
  - List row text styles: `MaterialTheme.typography.bodyLarge` for label, `bodyLarge` with `TextSecondary` for value, matching `MeScreen.ProfileValueRow`.
  - Bottom button container: `ByteImColors.Surface` for the bar background, `ByteImColors.PrimaryGreen` for the button, `Color.White` for the button text, `6.dp` rounded corners (matches `EditorActionButton`).

## Components

### New route (`SelfHostedImRoute.kt`)

Add a new sealed object inside the existing `sealed class SelfHostedImRoute`:

```kotlin
data object ContactProfile : SelfHostedImRoute("contact-profile/{userId}") {
    const val USER_ID_ARG = "userId"
    val pattern: String = route

    fun createRoute(userId: String): String? {
        val trimmed = userId.trim()
        if (trimmed.isEmpty()) return null
        return "contact-profile/$trimmed"
    }
}
```

This mirrors `SelfHostedImRoute.Chat`'s path-arg pattern so `MainActivity` can use the same `composable(route = SelfHostedImRoute.ContactProfile.pattern) { entry -> ... }` shape it already uses for chat.

### New ViewModel (`contacts/ContactProfileViewModel.kt`)

```kotlin
data class ContactProfileUiState(
    val profile: UserProfile? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

class ContactProfileViewModel(
    private val userId: String,
    private val session: AuthSession,
    private val profileRepository: ProfileRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ContactProfileUiState())
    val state: StateFlow<ContactProfileUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    fun start() {
        // Re-render local cache every time start() is called; the read is cheap
        // and lets a later entry (e.g. after a back-and-forth) pick up DAO writes
        // that happened between visits.
        val cached = profileRepository.localProfile(userId)
        mutableState.value = mutableState.value.copy(profile = cached)
        if (refreshJob?.isActive == true) return
        // 2. Background refresh.
        refreshJob = scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isRefreshing = false,
                    errorMessage = if (cached == null) "登录已过期，请重新登录" else null
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
            val remote = profileRepository.getProfile(validSession.accessToken, userId)
            if (remote != null) {
                mutableState.value = mutableState.value.copy(profile = remote, isRefreshing = false)
            } else {
                mutableState.value = mutableState.value.copy(
                    isRefreshing = false,
                    errorMessage = if (cached == null) "加载失败" else null
                )
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun retry() {
        // Clears errorMessage and re-runs the refresh path; cache stays.
        mutableState.value = mutableState.value.copy(errorMessage = null)
        start()
    }
}
```

Notes:

- `start()` is idempotent and reentrant-safe: a second call while a refresh is in flight is a no-op; a second call after the refresh finished but before `stop()` will trigger another refresh.
- The `cached` variable is captured before the launch and re-used inside the coroutine to decide whether the failure should surface as an error (no cache → show retry; cache exists → stay silent).
- The constructor takes the `userId` directly so the ViewModel does not need to read it from `SavedStateHandle`; the route argument is read once in `MainActivity` and passed in. This is consistent with how `ChatViewModel` is built in `MainActivity` today.

### New Composable (`contacts/ContactProfileScreen.kt`)

Public entry point:

```kotlin
@Composable
fun ContactProfileScreen(
    viewModel: ContactProfileViewModel,
    state: ContactProfileUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSendMessage: (peerUserId: String) -> Unit
)
```

Internally, three private composables:

- `ContactProfileScreenContent(state, onBack, onSendMessage)` — top-level layout with `Scaffold` and bottom-bar button. Calls `ByteImTopBar` and renders the appropriate body depending on `state.profile` / `state.errorMessage`.
- `ContactProfileHeader(profile)` — large avatar + nickname, centered, on the app background. The avatar and the nickname text are both rendered with no `clickable` modifier and no `Modifier.pointerInput`; they are decorative only. The whole page is read-only, and the header is part of that.
- `ContactProfileDataRows(profile)` — three rows (昵称 / 性别 / 个性签名) inside a `ByteImListSurface`, each with a `HorizontalDivider` between them. Signature row uses `maxLines = 2`, `overflow = TextOverflow.Ellipsis`. Gender row uses `ContactProfileDisplayPolicy.genderLabel(profile.gender)`. Empty gender falls back to `ContactProfileDisplayPolicy.genderUnsetLabel`. Empty signature falls back to `ContactProfileDisplayPolicy.signatureUnsetLabel`.

Body dispatch:

- If `state.profile == null && state.errorMessage != null` → render a centered error block: `Text(state.errorMessage)` + a `TextButton` "重试" calling `viewModel.retry()`. No bottom button (nothing to send to).
- Else → render `ContactProfileHeader` + `ContactProfileDataRows`. Bottom button is enabled.
- The bottom button is always present in the layout (so the layout doesn't jump when a profile arrives), but its `enabled` is `state.profile != null`.

Signature:

```kotlin
@Composable
private fun ContactProfileSendMessageBar(
    enabled: Boolean,
    onClick: () -> Unit
)
```

`Scaffold.bottomBar` slot. Background `ByteImColors.Surface`, top `HorizontalDivider` in `ByteImColors.Divider`, button full-width with `ByteImColors.PrimaryGreen` and `Color.White` text, `Modifier.padding(horizontal = ByteImDimensions.EdgePadding, vertical = 12.dp)`, min height 48dp, `RoundedCornerShape(6.dp)`.

### New display policy (`contacts/ContactProfileDisplayPolicy.kt`)

```kotlin
object ContactProfileDisplayPolicy {
    const val title = "详细资料"
    const val nicknameRowLabel = "昵称"
    const val genderRowLabel = "性别"
    const val signatureRowLabel = "个性签名"
    const val genderUnsetLabel = "未设置"
    const val signatureUnsetLabel = "未填写"
    const val sendMessageLabel = "发送消息"
    const val retryLabel = "重试"
    const val loadFailedMessage = "加载失败"
    const val sessionExpiredMessage = "登录已过期，请重新登录"

    fun genderLabel(gender: Gender?): String = when (gender) {
        Gender.MALE -> "男"
        Gender.FEMALE -> "女"
        null -> genderUnsetLabel
    }
}
```

Mirrors `MeDisplayPolicy` so the gender/signature fallbacks are consistent across the app. The policy is intentionally a pure-Kotlin object so it's easy to unit-test outside Compose.

### `MainActivity` wiring

Inside `AuthenticatedImNavHost`'s `NavHost`, add:

```kotlin
composable(route = SelfHostedImRoute.ContactProfile.pattern) { entry ->
    val userId = entry.arguments
        ?.getString(SelfHostedImRoute.ContactProfile.USER_ID_ARG)
        .orEmpty()
    if (userId.isBlank()) {
        // Defensive: route is malformed; pop back to the previous screen.
        LaunchedEffect(Unit) { navController.popBackStack() }
        return@composable
    }
    val viewModel = remember(session.userId, userId) {
        ContactProfileViewModel(
            userId = userId,
            session = session,
            profileRepository = profileRepository,
            validSessionProvider = validSessionProvider
        )
    }
    val state by viewModel.state.collectAsState()
    ContactProfileScreen(
        viewModel = viewModel,
        state = state,
        onBack = { navController.popBackStack() },
        onSendMessage = { peerUserId ->
            SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)
                ?.let { navController.navigateToChat(it) }
        }
    )
}
```

Defensive `userId.isBlank()` check is a safety net for malformed routes; in practice the path-arg is always non-blank because `createRoute` rejects empty input.

In the existing `composable(SelfHostedImRoute.Contacts.route)` block, change `onOpenContact` from:

```kotlin
onOpenContact = { peerUserId ->
    SelfHostedImRoute.Chat.createSingleRoute(session.userId, peerUserId)?.let(navController::navigateToChat)
}
```

to:

```kotlin
onOpenContact = { peerUserId ->
    SelfHostedImRoute.ContactProfile.createRoute(peerUserId)?.let { navController.navigate(it) }
}
```

The bottom-bar policy (`BottomNavigationSpec.topLevelItems.any { it.route == currentRoute }`) already hides the bottom bar on non-top-level routes. `ContactProfile` is a non-top-level route, so the chat-style `bottomBar = null` behavior continues to work without any change to `TopLevelBackPolicy` or `BackHandler` — the profile page just gets no bottom navigation, and the system Back / TopBar Back both pop to the Contacts tab.

The chat destination is still reached through the same `navigateToChat(...)` extension as before, so `ChatBackPolicy` and the chat → conversation back transition remain unchanged.

## Behavior

### Loading strategy (cached-first, silent refresh)

1. `ContactProfileScreen` mounts → `viewModel.start()` runs.
2. `start()` synchronously reads `profileRepository.localProfile(userId)` and pushes it into `state.profile`. If the cache has a row from a prior `refreshProfiles(...)` call (e.g. when the user was on the Contacts tab and we already batch-fetched all contact profiles), the page renders fully and immediately.
3. `start()` then launches a coroutine on the IO dispatcher. Inside the coroutine, `validSessionProvider()` is consulted; if null, the page either shows the session-expired error (no cache) or stays silent (cache present) — depending on `cached != null`.
4. With a valid session, `state.isRefreshing = true` is published (no UI element reads this today; it is left in the state for the test surface and for a future inline progress affordance) and `profileRepository.getProfile(accessToken, userId)` is called. This is a `GET /users/{userId}` HTTP request that already exists in the mock-server and returns `{ userId, phone, nickname, avatarUrl, avatarUpdatedAt, updatedAt, gender, signature }` per the B1 status doc.
5. On success, the result is persisted by `ProfileRepository` and pushed into `state.profile`. UI re-renders with fresh values (typically identical to the cache; only visible when the peer has changed their nickname, avatar, gender, or signature since the last contact list refresh).
6. On failure, `state.errorMessage` is set only when there is no cached profile to fall back on. When the cache is present, the page stays unchanged and the error is suppressed — the user can see the data they already have.

### Sticky send-message button

- Always rendered in `Scaffold.bottomBar`. Enabled only when `state.profile != null`.
- Tapping the button calls `onSendMessage(peerUserId)`, which is the `peerUserId` from the route arg, not from `state.profile` — this is intentional so a stale cache (where the user is not in the local DB yet) still routes correctly to chat via `SelfHostedImRoute.Chat.createSingleRoute(currentUserId, peerUserId)`.
- The navigation uses the existing `navigateToChat(...)` extension, which `popUpTo(graph.findStartDestination().id) { saveState = false }` so the back stack is reset and the chat page becomes the top destination. Pressing system Back from chat returns to Contacts (not to the profile page), preserving the "tapping contact opens profile; tapping send message commits to chat" intent.

### Back behavior

- `ByteImTopBar(onBack = onBack)` calls `navController.popBackStack()`, which returns to the Contacts tab (the previous entry in the back stack).
- The Contacts tab is a top-level route registered in `TopLevelBackPolicy`, so further system Back from Contacts is handled by `TopLevelRouteBackHandler` (`moveTaskToBack`).
- The system back from the ContactProfile page itself is not intercepted — `BackHandler` is not registered in `ContactProfileScreen` because the default back behavior (pop the route) is exactly what we want.

### Failure / empty states

| Condition | UI |
|---|---|
| Cache present, refresh in flight | Cached data shown; no loading indicator. |
| Cache present, refresh succeeds | Cached data silently replaced with fresh data on next recomposition. |
| Cache present, refresh fails | Cached data stays; no error banner. |
| No cache, refresh in flight | Top bar + centered `CircularProgressIndicator`. |
| No cache, refresh fails | Centered "加载失败" + "重试" button. `Scaffold.bottomBar` shows the send button but disabled. |
| No cache, session expired | Centered "登录已过期，请重新登录" + "重试" button (which will not help; the user needs to log in again, but retry at least re-checks the session). Send button disabled. |
| Profile fetched but is the current user | Unreachable in practice (DemoContactResolver never returns `session.userId`); even if it did, the page would render and the Send button would route to a self-chat conversation with the same `userId` on both sides. No additional guard is added; this is consistent with the contact → chat flow's existing behavior. |

## Planned Reuse (Out of Scope for This Slice)

The same read-only profile surface is intended to be reachable from a few more entry points in future slices:

- **Single-chat message row** — tapping a peer avatar in `ChatScreen` (the avatar at the side of an incoming bubble, and the avatar on the chat top bar near the peer name) should open the same page.
- **Group-chat message row** — tapping a peer avatar in a group chat should open the same page for that member.
- **Group member list** — tapping a member row in a future group detail screen should open the same page.

This slice does NOT implement any of those entry points. The Contacts tab is the only entry point right now. The points below describe the design choices already made to keep that reuse cheap when we get to it — they are not work for this slice.

### Design choices that already support reuse

- **The Composable is named `ContactProfileScreen` and lives in the `contacts/` package.** The page itself is just a read-only view of any `UserProfile` keyed by `userId`; nothing in the Composable, ViewModel, or DisplayPolicy is specific to the Contacts tab.
- **`ContactProfileViewModel` takes `userId: String` directly** (not as a `SavedStateHandle` key, not as a path through the Contacts list). Any caller that knows a `userId` can build one.
- **The data source is `ProfileRepository.getProfile(accessToken, userId)`**, which is the same call used by `MeViewModel.currentUserProfile(...)` today. It works for any user — the current user, a single-chat peer, a group member, or an unrelated user.
- **The "Send Message" action takes `peerUserId: String`** (from the route arg, not from `state.profile`). This means future entry points that don't have a single-chat context (e.g. group member profile) can disable the button or replace it with another action without rewriting the Composable.
- **The bottom bar uses `Scaffold.bottomBar`**, not a hard-coded `Column` at the end of the body. The slot can be hidden (no bottom bar) for non-chat entry points, or replaced with a different CTA (e.g. "Mention" in a group member profile, or "View Group" for the current user's own profile if it ever lands here).
- **The page has no affiliation with the Contacts tab in its title or chrome.** The top bar reads "详细资料", which is generic.

### What will need to change to support those entry points later

These are NOT this slice's work; they are listed so the next design can pick them up cheaply.

- **Route name:** `contact-profile/{userId}` is Contacts-specific. A more generic `user-profile/{userId}` (or `peer-profile/{userId}`) would fit the chat entry points better. The renaming can be done in a follow-up that also adds the chat wiring.
- **Chat screen wiring:** the peer avatar and the chat top bar peer name need an `onClick` that builds the new route and navigates. `ChatScreen` and `ChatTopBar` are not modified in this slice.
- **Group member entry point:** requires a group member list screen and a way to surface a peer's `userId` from a group message row. Not in this slice.
- **"Send Message" semantics in group member context:** if a user opens a group member's profile from a group, the button should still open a single chat with that member (or it could be replaced with a "Send Mention" or "View Group" action). The decision is deferred to the future slice.

If a future slice needs the page to be reachable from chat, the implementation should re-evaluate the route name and the bottom-bar CTA at that time, then design a follow-up spec.

## File-Level Change List

| File | Change |
|---|---|
| `app/src/main/java/com/codex/im/SelfHostedImRoute.kt` | Add `data object ContactProfile` with `userId` path arg. |
| `app/src/main/java/com/codex/im/contacts/ContactProfileViewModel.kt` | **New** — read-only ViewModel, cached-first + background refresh. |
| `app/src/main/java/com/codex/im/contacts/ContactProfileScreen.kt` | **New** — public `ContactProfileScreen(...)` + private `ContactProfileScreenContent`, `ContactProfileHeader`, `ContactProfileDataRows`, `ContactProfileSendMessageBar`. |
| `app/src/main/java/com/codex/im/contacts/ContactProfileDisplayPolicy.kt` | **New** — pure-Kotlin label constants and `genderLabel(...)`. |
| `app/src/main/java/com/codex/im/MainActivity.kt` | Add `composable(SelfHostedImRoute.ContactProfile.pattern)` block; change `onOpenContact` in the `Contacts` block to navigate to `ContactProfile.createRoute(...)` instead of `Chat.createSingleRoute(...)`. |
| `app/src/test/java/com/codex/im/contacts/ContactProfileDisplayPolicyTest.kt` | **New** — testable labels and `genderLabel`. |
| `app/src/test/java/com/codex/im/contacts/ContactProfileViewModelTest.kt` | **New** — see "Testing" below. |

No changes to: `ProfileRepository`, `ProfileApi`, `ProfileJsonParser`, `UserProfile`, `UserProfileDao`, `ChatScreen`, `ChatViewModel`, `ConversationListScreen`, `ContactListScreen` (besides the wire-up change), `MeScreen`, `MeViewModel`, the mock-server, the SQLite schema, the WebSocket protocol, or any other route.

## Testing

### Unit tests for `ContactProfileViewModelTest`

All tests use the existing fakes pattern (`InMemoryUserProfileDao`, `FakeProfileApi`, a stub `AuthSession`, an inline `validSessionProvider`).

1. `startRendersCachedProfileImmediately`
   - Seed `userProfileDao` with a `UserProfile` for `userId`.
   - Call `start()`, await idle.
   - Assert the first `state.value` read (synchronously, before await) already has `profile = cached`.

2. `startRefreshesProfileInBackground`
   - Seed cache with a stale profile.
   - Use a `FakeProfileApi` that returns a fresh `UserProfile` (different `nickname`).
   - Call `start()`, await idle.
   - Assert `state.profile?.nickname == fresh.nickname`.

3. `startSurfacesErrorWhenNoCacheAndRemoteFails`
   - Empty `userProfileDao`.
   - `FakeProfileApi.user(...)` returns `ProfileResult.Failure`.
   - Call `start()`, await idle.
   - Assert `state.profile == null` and `state.errorMessage == "加载失败"`.

4. `startKeepsCacheAndStaysSilentWhenRemoteFails`
   - Seed cache.
   - `FakeProfileApi.user(...)` returns `ProfileResult.Failure`.
   - Call `start()`, await idle.
   - Assert `state.profile == cached` and `state.errorMessage == null`.

5. `startHandlesExpiredSessionWhenNoCache`
   - `validSessionProvider` returns `null`.
   - Empty `userProfileDao`.
   - Call `start()`, await idle.
   - Assert `state.errorMessage == "登录已过期，请重新登录"`.

6. `startHandlesExpiredSessionWhenCacheExists`
   - `validSessionProvider` returns `null`.
   - Seed cache.
   - Call `start()`, await idle.
   - Assert `state.profile == cached` and `state.errorMessage == null`.

7. `retryClearsErrorAndReRuns`
   - Set `state.errorMessage` via the failure-with-no-cache path.
   - Switch `FakeProfileApi` to success.
   - Call `retry()`.
   - Assert `state.errorMessage == null` and `state.profile == fresh`.

8. `stopCancelsInFlightRefresh`
   - Use a `FakeProfileApi` that suspends indefinitely.
   - Call `start()`, then `stop()`.
   - Assert `refreshJob == null` and no state changes after stop.

### Unit tests for `ContactProfileDisplayPolicyTest`

1. `titleIsDetailedProfile` — assert `title == "详细资料"`.
2. `genderLabelReturnsMaleForMale` / `genderLabelReturnsFemaleForFemale` / `genderLabelReturnsUnsetForNull`.
3. `genderRowLabel` / `signatureRowLabel` / `sendMessageLabel` / `retryLabel` / `loadFailedMessage` / `sessionExpiredMessage` constants match the spec.

### Existing tests to re-run

- `ContactListViewModelTest` (unchanged; `openContact` is unchanged).
- `BottomNavigationSpecTest` (unchanged; the new route does not add a bottom-nav tab).
- `SelfHostedImRouteTest` (extend to assert `ContactProfile.createRoute("u1") == "contact-profile/u1"`, `createRoute("")` is null, `createRoute("  ")` is null, `createRoute("u1")?.contains("u1")`).
- `TopLevelBackPolicyTest` (unchanged).
- `ChatBackPolicyTest` (unchanged; chat back behavior is unaffected).

### Build / lint

- `bash ./gradlew :app:testDebugUnitTest` — full Android JVM unit test run.
- `bash ./gradlew :app:assembleDebug` — debug APK assemble to catch Compose signature / resource issues.

## Risks and Mitigations

- **Risk:** Tapping a contact is now two taps away from the chat screen (profile → send message → chat). This is a deliberate UX trade-off per the requirement.
  - **Mitigation:** The profile page renders the cached avatar + nickname instantly, so the perceived delay is minimal. The send-message button is the same height and color as a primary CTA, so it is discoverable.

- **Risk:** A peer who has not yet been batch-fetched in the contacts refresh has no cache, and the page would briefly show an empty state.
  - **Mitigation:** The current `ContactListViewModel.refresh()` already calls `profileRepository.refreshProfiles(validSession.accessToken, contactIds)` before populating `state.items`, so by the time the user can tap a contact row, that peer's profile is already in the local cache in `user_profiles`. The empty state is a theoretical fallback only.

- **Risk:** `profileRepository.getProfile(accessToken, userId)` writes to the DAO. If the peer is unknown, the API returns failure and the DAO is untouched. The repository contract already handles this; no extra guard is needed in the ViewModel.

- **Risk:** `MeDisplayPolicy` already provides the gender/signature fallbacks. Using a separate `ContactProfileDisplayPolicy` duplicates those labels.
  - **Mitigation:** The duplication is intentional. The peer profile page is read-only, the Me profile page is editable, and the labels are user-visible product text. If product text changes, both policies would be updated in lockstep. A future refactor could extract a `ProfileLabels` shared object; that refactor is not part of this slice.

- **Risk:** Mock-server demo accounts have null `gender` and null `signature` today, so the page will show `未设置` / `未填写` for the two demo accounts.
  - **Mitigation:** This is the expected behavior per the `Me` profile detail pattern. Seeding demo accounts with real values is a separate content task and is out of scope for this slice.

- **Risk:** Navigation stack — accidentally leaving a residual ContactProfile entry on the back stack after send-message tap.
  - **Mitigation:** `navigateToChat(...)` already uses `popUpTo(graph.findStartDestination().id) { saveState = false }`, which collapses the back stack to the start destination. ContactProfile is collapsed along with Contacts and any other intermediate entries.

## Acceptance Criteria

- The new `contact-profile/{userId}` route exists in `SelfHostedImRoute` and is registered in `MainActivity`'s `NavHost`.
- Tapping a contact row in the Contacts tab navigates to the new ContactProfile page.
- The new page shows avatar, nickname, gender, and signature from the peer's `UserProfile`.
- The page does NOT show the peer's `userId` / `phone`.
- The page uses the existing ByteIM UI constants for colors, dimensions, and shapes.
- A sticky bottom "发送消息" button is always present, and is enabled only when a profile has been loaded.
- Tapping "发送消息" navigates to the existing single-chat screen for that peer.
- Tapping the top-bar Back returns to the Contacts tab.
- The page renders cached profile data immediately and refreshes from `GET /users/{userId}` in the background.
- The page does not block the user on a loading screen when cache is present.
- All unit tests in the new `ContactProfileViewModelTest` and `ContactProfileDisplayPolicyTest` pass.
- All existing unit tests in `ContactListViewModelTest`, `BottomNavigationSpecTest`, `SelfHostedImRouteTest`, `TopLevelBackPolicyTest`, `ChatBackPolicyTest`, and `MeBackPolicyTest` continue to pass.
- `./gradlew :app:testDebugUnitTest` passes.
- `./gradlew :app:assembleDebug` passes.
- No mock-server change is required.
- No protocol / API / SQLite / ViewModel outside the new files is modified.
