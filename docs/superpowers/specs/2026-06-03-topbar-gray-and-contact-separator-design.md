# Top Bar Gray + Contact Separator Bar Design

Date: 2026-06-03
Branch: `redesign-ui`

## Goal

Two follow-up visual tweaks to the contacts-placeholder work already committed on this branch:

1. The two **top-level tab top bars** (Messages tab and Contacts tab) should have a **gray** background instead of white, so the top bar visually distinguishes itself from the white content list below it.
2. Between **群聊** (the second placeholder row) and the **first contact row** in `ContactListScreen`, the invisible 8dp gap should be replaced with a **visible gray full-width separator bar**, so the placeholder block reads as its own section rather than just blending into the contacts.

Both changes are visual only. No new state, no new event flow, no new tests, no behavior changes.

## Source Context

- Existing spec: `docs/superpowers/specs/2026-06-03-contacts-entry-placeholders-design.md`
- Existing plan: `docs/superpowers/plans/2026-06-03-contacts-entry-placeholders.md`
- Top bar component: `app/src/main/java/com/codex/im/ui/ByteImUi.kt` (`ByteImTopBar`, lines 67–129)
- UI constants: `ByteImColors.AppBackground` = `#EDEDED` (gray, used as the app's outer background)
- Contacts screen: `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`
- Messages screen: `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- Top bar callers: `ChatScreen`, `ContactListScreen`, `GroupCreateScreen`, `ConversationListScreen`, `MeScreen` (3 call sites)

## Scope

In scope:

- `ByteImTopBar` Composable — add an optional `containerColor` parameter that controls the top bar's background color, defaulting to the current white (`ByteImColors.Surface`).
- `ConversationListScreen` (Messages tab) — pass `containerColor = ByteImColors.AppBackground` to its `ByteImTopBar` call.
- `ContactListScreen` (Contacts tab) — pass `containerColor = ByteImColors.AppBackground` to its `ByteImTopBar` call.
- `ContactListScreen` LazyColumn — replace the existing invisible 8dp `Spacer` after `ContactEntryBlock` with a visible 8dp gray `Spacer` (fillMaxWidth + background).

Out of scope:

- The other 4 `ByteImTopBar` call sites (`ChatScreen`, `GroupCreateScreen`, 3 sites in `MeScreen`) — keep their default white background.
- The 1dp `HorizontalDivider` lines inside `ContactEntryBlock` and between contact rows — unchanged.
- All other visual styling, colors, dimensions, layout, behavior, navigation, ViewModel, repository, protocol, or test changes.
- Build, install, and on-device verification — handled by the human partner, not part of the implementation work.

## Visual System

Reuse the existing ByteIM UI constants. No new colors or dimensions.

- Top bar background (when explicitly set to gray): `ByteImColors.AppBackground` (`#EDEDED`).
- Top bar background (default, unchanged for other callers): `ByteImColors.Surface` (white).
- Separator bar background: `ByteImColors.AppBackground` (same gray, matches the top bars).
- Separator bar height: `8.dp` (matches the existing invisible `Spacer` height, so the gray bar replaces the gap at the same vertical position — no extra height change).
- Separator bar width: full row width (`Modifier.fillMaxWidth()`).

## Component Change: `ByteImTopBar`

In `app/src/main/java/com/codex/im/ui/ByteImUi.kt`, change the signature of `ByteImTopBar` from:

```kotlin
@Composable
fun ByteImTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<@Composable () -> Unit> = emptyList(),
    centerTitle: Boolean = false
)
```

to:

```kotlin
@Composable
fun ByteImTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<@Composable () -> Unit> = emptyList(),
    centerTitle: Boolean = false,
    containerColor: Color = ByteImColors.Surface
)
```

In the function body, change the `Box` modifier from:

```kotlin
modifier = modifier
    .fillMaxWidth()
    .height(ByteImDimensions.TopBarHeight)
    .background(ByteImColors.Surface)
    .padding(horizontal = ByteImDimensions.EdgePadding),
```

to:

```kotlin
modifier = modifier
    .fillMaxWidth()
    .height(ByteImDimensions.TopBarHeight)
    .background(containerColor)
    .padding(horizontal = ByteImDimensions.EdgePadding),
```

Only that single `.background(...)` line changes. The `containerColor` parameter has a default of `ByteImColors.Surface`, so the other 4 callers do not need to be touched and will see identical rendering to before.

## Caller Changes

### `ConversationListScreen.kt` (Messages tab)

Find the `ByteImTopBar(...)` call (currently around line 118). Add the `containerColor` argument:

```kotlin
ByteImTopBar(
    title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
    centerTitle = true,
    containerColor = ByteImColors.AppBackground,  // ← new
    actions = listOf( /* unchanged */ )
)
```

If `ByteImColors` is not already imported in this file, add `import com.codex.im.ui.ByteImColors`. Check the current imports first; if `ByteImColors` (or the whole `com.codex.im.ui.*` namespace via a wildcard) is already imported, skip the import addition.

### `ContactListScreen.kt` (Contacts tab)

The same change applies to the `ContactsTopBar` Composable in this file, which is a thin wrapper around `ByteImTopBar`. The `ByteImColors` import is already present (it was needed for `ByteImColors.AppBackground` background in the `ContactListScreen` wrapper and for `ByteImColors.Divider` in the dividers), so no new import is needed.

Find the `ByteImTopBar(...)` call inside `ContactsTopBar` (currently around line 103). Add the `containerColor` argument:

```kotlin
ByteImTopBar(
    title = "通讯录",
    centerTitle = true,
    containerColor = ByteImColors.AppBackground,  // ← new
    actions = listOf( /* unchanged */ )
)
```

### `ContactListScreen.kt` LazyColumn — separator bar

Find the first `item { ... }` block in the `LazyColumn` (added by the previous task). It currently reads:

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

Three differences:

1. The `Spacer` modifier is no longer just `Modifier.height(8.dp)`. It now has `.fillMaxWidth()` and `.background(ByteImColors.AppBackground)`.
2. The `background` import (`androidx.compose.foundation.background`) must be present. It was already added in the previous task's commit `df56f59`, so no new import is needed — but verify it is present in the file's import block.
3. The `Spacer` height stays `8.dp` — the gray bar occupies the same vertical space as the old invisible gap, so the visual rhythm of the list below it is unchanged.

No other parts of `ContactListScreen.kt` are touched. The `ContactEntryItem`, `ContactEntryBlock`, the contact-row `HorizontalDivider` indents, the `ContactsTopBar` (other than the `containerColor` argument), and the `Column` wrapper all stay as-is.

## Behavior

- Tapping the top bar's search or `+` icons: unchanged. The icons still render with the existing `tint` and `contentDescription`. The top bar's icon contrast against a gray background is slightly different from against white — this is intentional and the icon's `ByteImColors.TextPrimary` (near-black) tint remains readable.
- Scrolling the contacts list: unchanged. The gray bar is a single `LazyColumn` row item, so it scrolls with the rest of the content (which is fine — the user only ever sees the bar between 群聊 and the first contact when scrolled to the top).
- The gray bar does not respond to clicks (it is a `Spacer`, not a clickable element).
- No new state, no new event flow, no new ViewModel or repository changes.

## Testing

No new tests. The change is purely visual:

- The `containerColor` parameter is a pure visual styling knob, exercised by the human partner on a real device (per the agreed workflow).
- The gray `Spacer` is a layout primitive, exercised by the human partner on a real device.
- The existing `ContactListViewModelTest` is unaffected (no logic changed).
- The pre-existing test compile error in `app/src/test/java/com/codex/im/chat/ChatDisplayPolicyTest.kt` (introduced in commit `aca0e37`, before all this work) is unrelated to this change and remains out of scope.

## Risk

Low. All changes are visual styling:

- Adding a defaulted `containerColor` parameter to `ByteImTopBar` is API-additive. Existing call sites that omit the argument see the same default (`ByteImColors.Surface`) and therefore render identically to before. A botched argument value at a single call site would only affect that one top bar.
- Changing the `Spacer` to a gray background bar is a 3-line edit in one file. Reverting is trivial.

The only cross-cutting risk is the human partner forgetting to verify that the unchanged callers (ChatScreen, GroupCreateScreen, MeScreen) still render with white top bars. The agreed workflow handles this: the human partner will install and visually confirm after the implementation is committed.
