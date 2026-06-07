# Top-Level White Bars and Gray Content Design

Date: 2026-06-04
Branch: `redesign-ui`

## Goal

Replace the now-implemented "gray top bar + gray contact separator bar" direction with a more WeChat-like top-level visual system:

1. The Messages top bar and Contacts top bar should be fixed white, not gray.
2. The bottom navigation bar (`消息` / `通讯录` / `我`) should stay fixed white across all three top-level routes without route-specific visual changes.
3. The scrollable content layer under the top-level chrome should read as light gray, especially on the Messages screen, so the white top bar and white bottom bar feel like stable chrome framing the content beneath them.

This is still a visual-only follow-up. No new route behavior, state model, network logic, or unread-count logic is introduced.

## Why This Direction Is More Coherent

The previous gray-top-bar direction over-emphasized the chrome itself. What we want now is the opposite: stable white navigation chrome, with the moving content layer visually separated underneath it.

That gives the top-level app a clearer hierarchy:

- White fixed chrome: Messages top bar, Contacts top bar, bottom navigation bar
- Light gray content layer: message list, contacts list, profile-home page background

This is closer to the user's stated WeChat reference and creates a more consistent system across the three top-level tabs.

## Source Context

- Supersedes deleted spec: `docs/superpowers/specs/2026-06-03-topbar-gray-and-contact-separator-design.md`
- Supersedes deleted plan: `docs/superpowers/plans/2026-06-03-topbar-gray-and-contact-separator.md`
- Shared UI primitives: `app/src/main/java/com/buyansong/im/ui/ByteImUi.kt`
- Messages screen: `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`
- Contacts screen: `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`
- Top-level nav host and bottom bar: `app/src/main/java/com/buyansong/im/MainActivity.kt`
- Me screen: `app/src/main/java/com/buyansong/im/profile/MeScreen.kt`

## In Scope

- Messages top bar: switch from gray back to white.
- Contacts top bar: switch from gray back to white.
- Messages content area: render the scrollable list area on the app light-gray background.
- Contacts content area: render the scrollable list area on the app light-gray background.
- Contacts entry/list separation: remove the previous design requirement that the gap after `群聊` must be a special visible gray bar.
- Bottom navigation: preserve the current fixed-white appearance and selected-tab behavior as an explicit regression guard.
- Me top-level home: preserve its current light-gray page background and fixed-white bottom navigation behavior as part of the unified top-level system.

## Out of Scope

- Introducing a new top bar on the Me home screen.
- Refactoring top-level routing from the current `Column + TopLevelBottomBar` structure to a new shared `Scaffold`.
- Changing unread badge computation, unread title formatting, or unread badge placement.
- Changing connection-state logic or the presence/placement of the network status notice.
- Changing search / plus action behavior in top bars.
- Changing contact-row divider indentation, message-row content layout, profile editing pages, chat pages, or group-create pages.
- Adding tests for purely visual styling changes.

## Visual Rules

Reuse existing colors:

- Fixed top-level chrome background: `ByteImColors.Surface` (white)
- Top-level content background: `ByteImColors.AppBackground` (`#EDEDED`)
- Existing text, badge, divider, and icon colors: unchanged

Apply those rules as follows:

- Messages screen:
  - `ByteImTopBar` stays white.
  - `ByteImSystemNotice` remains in the same position and with the same behavior.
  - The list container behind the `LazyColumn` should be light gray.
- Contacts screen:
  - `ByteImTopBar` stays white.
  - The list container behind the `LazyColumn` should be light gray.
  - The gap after `ContactEntryBlock()` returns to being ordinary spacing, which will now read as gray naturally because the parent list container is gray.
- Me screen:
  - No new top bar is added.
  - The home page continues to sit on the app light-gray background and keeps the same white content blocks and white bottom bar relationship it already has.
- Bottom navigation:
  - Keep `NavigationBar(containerColor = ByteImColors.Surface, tonalElevation = 0.dp)`.
  - Selected tab state, labels, icons, and unread badge behavior remain unchanged across `消息`, `通讯录`, and `我`.

## Shared Component Direction

Do not do a large structural refactor for this pass.

Instead, use the existing shared UI primitives:

- Keep `ByteImTopBar`'s color override support.
- Extend `ByteImListSurface` with an optional `containerColor` parameter, defaulting to white so non-top-level call sites remain unchanged.
- Pass `ByteImColors.AppBackground` only from the top-level Messages and Contacts list containers.

This keeps the implementation small and localized while still expressing the new visual system clearly in code.

## Behavior Guarantees

These are hard constraints for the implementation:

- `MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount)` remains untouched.
- The unread badge in the bottom navigation remains untouched.
- `ConversationConnectionStatusPolicy.visibleLabel(...)` and `ByteImSystemNotice(...)` remain untouched in both logic and placement.
- Top-bar action icons and menus remain untouched in behavior.
- Top-level route switching and back behavior remain untouched.

The result should feel visually different, but functionally identical.

## Risk

Low.

The only meaningful risk is an accidental visual regression where a shared color primitive is changed too broadly and affects non-top-level screens. The implementation should avoid that by:

- keeping white as the default for shared components, and
- opting top-level screens into gray content explicitly at the call sites.
