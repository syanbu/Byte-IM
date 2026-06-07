# Contacts Entry Placeholders Design

Date: 2026-06-03
Branch: `redesign-ui`

## Goal

In the ByteIM contacts page (`ContactListScreen`), add two static placeholder entries — **新的朋友** (New Friends) and **群聊** (Group Chat) — at the top of the list, visually grouped as their own block above the user contacts. While doing this, fix the contact-row dividers so they do not exceed the avatar's right edge, matching WeChat's expected look.

The two new entries are visual placeholders only. They do not route, do not fetch data, and do not show a "coming soon" toast — they are clickable (matching the existing top-bar search button pattern) but the click handler is empty.

## Scope

In scope:

- Add a new Composable `ContactEntryItem` that renders a single placeholder row (icon + title, no subtitle).
- Add a new Composable `ContactEntryBlock` that renders the two placeholder rows (新的朋友 + 群聊) as one grouped section at the top of the contacts list.
- Add two new vector drawables for the placeholder icons, scoped to `app/src/main/res/drawable/`.
- Modify `ContactListScreen.kt` to render the new block as the first item of the existing `LazyColumn`, and fix the `HorizontalDivider` indents for contact rows so they stop at the avatar's right edge.

Out of scope:

- No changes to `ContactListViewModel`, `ContactListUiState`, or `ContactListItem`. The two placeholders are static and do not flow through the ViewModel.
- No navigation, no click handler logic, no toast, no future "New Friends" / "Group Chat" feature implementation.
- No changes to dividers in other screens (chat list, profile, etc.) — the divider fix is limited to the contacts list.
- No letter-group headers (e.g. "A", "B") for the contacts list. The user explicitly chose "top + separate block" without letter grouping.

## Source Context

- Target file: `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`
- Drawable convention reference: `app/src/main/res/drawable/ic_add_circle.xml` (28dp, single-color stroke)
- UI constants: `app/src/main/java/com/buyansong/im/ui/ByteImUi.kt` (`ByteImColors`, `ByteImDimensions`)
- ByteIM UI redesign spec: `docs/superpowers/specs/2026-06-01-byteim-ui-redesign-design.md`

## Visual System

Use the existing ByteIM UI constants — no new colors or dimensions.

- Row height: `ByteImDimensions.ListItemHeight` (`72.dp`)
- Edge padding: `ByteImDimensions.EdgePadding` (`16.dp`)
- Gutter between icon and title: `ByteImDimensions.Gutter` (`12.dp`)
- Icon size: `28.dp` (matches top-bar `+` icon)
- Icon background tile: `50.dp` square, color `ByteImColors.SurfaceLow` (`#FCF9F8`), corner radius matches `ByteImShapes.Avatar` (`8.dp`)
- Title text: `MaterialTheme.typography.titleMedium`, `FontWeight.Medium`, color `ByteImColors.TextPrimary` — matches `ContactRow`'s name text style so the two sections feel like one continuous list
- Divider color: `ByteImColors.Divider`
- Divider indent (from screen left edge to divider start): `EdgePadding (16) + ListAvatarSize (50) + Gutter (12) = 78.dp` — this is the right edge of the avatar plus the gap before the title text
- Vertical gap between the placeholder block and the contacts list: `8.dp` (an empty `Spacer`, no divider — this is what creates the "separate block" feel)

## Components

### New vector drawables

Two new files in `app/src/main/res/drawable/`, each `28×28 dp`, single-color stroke (color `#000000`, inherited from existing `ic_add_circle.xml` pattern — the runtime `tint` in Compose handles the actual color):

- `ic_contact_new_friend.xml` — a stylized person silhouette with a `+` mark, indicating "add a new contact".
- `ic_contact_group_chat.xml` — a stylized group-of-three-people silhouette, indicating "group chat".

Both use `android:width="28dp"`, `android:height="28dp"`, `android:viewportWidth="28"`, `android:viewportHeight="28"`, and `android:fillColor="#000000"` filled paths (silhouettes rather than outlines, so they read cleanly at 28dp against the `SurfaceLow` tile). Tints are applied at the Composable level via `Icon(painter = ..., tint = ByteImColors.TextPrimary)`.

### `ContactEntryItem`

Private Composable in `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt` (kept in the same file for now — small enough that a separate file adds no value).

Signature:

```kotlin
@Composable
private fun ContactEntryItem(
    iconResId: Int,
    title: String,
    onClick: () -> Unit
)
```

Layout:

- Outer `Row`, `Modifier.fillMaxWidth().height(ByteImDimensions.ListItemHeight).clickable(onClick = onClick).padding(horizontal = ByteImDimensions.EdgePadding)`, `verticalAlignment = Alignment.CenterVertically`, `horizontalArrangement = Arrangement.spacedBy(12.dp)`.
- Icon container: `Box` of `Modifier.size(ByteImDimensions.ListAvatarSize).background(ByteImColors.SurfaceLow, ByteImShapes.Avatar)`, `contentAlignment = Alignment.Center`.
- Icon: `Icon(painter = painterResource(id = iconResId), contentDescription = title, tint = ByteImColors.TextPrimary, modifier = Modifier.size(28.dp))`.
- Title: `Text(text = title, style = MaterialTheme.typography.titleMedium, color = ByteImColors.TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))`.

No subtitle row. No avatar URL — these are static placeholders, not user profiles.

### `ContactEntryBlock`

Private Composable in the same file. Renders the two placeholder rows in order with a divider between them.

Signature:

```kotlin
@Composable
private fun ContactEntryBlock() {
    ContactEntryItem(iconResId = R.drawable.ic_contact_new_friend, title = "新的朋友", onClick = {})
    HorizontalDivider(
        color = ByteImColors.Divider,
        modifier = Modifier.padding(start = ByteImDimensions.EdgePadding + ByteImDimensions.ListAvatarSize + ByteImDimensions.Gutter)
    )
    ContactEntryItem(iconResId = R.drawable.ic_contact_group_chat, title = "群聊", onClick = {})
}
```

The divider between the two placeholders uses the same `78.dp` left indent as the contact-row dividers, so all dividers in the screen line up on the same vertical.

## Screen Changes

`ContactListScreen.kt` `LazyColumn` body changes from:

```kotlin
LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(state.items, key = { it.userId }) { item ->
        ContactRow(item = item, onClick = { viewModel.openContact(item.userId) })
        HorizontalDivider(color = ByteImColors.Divider)
    }
}
```

to:

```kotlin
LazyColumn(modifier = Modifier.fillMaxSize()) {
    item {
        ContactEntryBlock()
        Spacer(modifier = Modifier.height(8.dp))
    }
    items(state.items, key = { it.userId }) { item ->
        ContactRow(item = item, onClick = { viewModel.openContact(item.userId) })
        HorizontalDivider(
            color = ByteImColors.Divider,
            modifier = Modifier.padding(start = ByteImDimensions.EdgePadding + ByteImDimensions.ListAvatarSize + ByteImDimensions.Gutter)
        )
    }
}
```

Key changes:

1. A new `item { ContactEntryBlock() ... }` block at the top of the list, followed by an 8dp `Spacer`. No divider between the block and the contacts — the `Spacer` is what visually separates them.
2. Every contact row's `HorizontalDivider` now carries a `Modifier.padding(start = 78.dp)` so the line starts at the avatar's right edge instead of the screen's left edge. The 78dp value is the sum of `EdgePadding + ListAvatarSize + Gutter` — exactly the right edge of the avatar tile plus the gutter before the text.
3. The `+` button dropdown menu (`ConversationCreateMenu`) is unchanged. It already offers "发起群聊" and "添加朋友" — this new block is a separate top-level shortcut, not a replacement.

No other changes to the file. `ContactsTopBar`, the `Column` wrapper, the `LaunchedEffect` blocks, and the navigation plumbing stay as-is.

## Behavior

- Tapping 新的朋友 or 群聊: the row shows the standard Material ripple (via `clickable`), no navigation, no toast, no state change. This matches the existing top-bar `IconButton(onClick = { })` placeholder pattern.
- The block does not respond to search (the top-bar search button is also a placeholder, but search filtering is not in scope here).
- The block does not show a notification badge (no "new friend requests" counter). This can be added later when the feature is implemented.
- No new state, no new event flow, no new tests. These are pure visual additions.

## Testing

No new tests. The placeholders have no logic. The existing `ContactListViewModelTest` continues to cover the ViewModel and is unaffected.

## Risk

Low. Changes are additive (one Composable pair + two drawables + one modified `LazyColumn` body) and localized to the contacts screen. No ViewModel, repository, navigation, or protocol impact. The divider indent change affects only the contacts list; if a regression appears, reverting the `Modifier.padding(start = ...)` on the `HorizontalDivider` is a one-line change.
