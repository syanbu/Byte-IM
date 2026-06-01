# ByteIM UI Redesign Design

Date: 2026-06-01
Branch: `redesign-ui`

## Goal

Redesign the Android client UI using the prototypes in `docs/PRD/pages/` as the visual direction while keeping the existing Jetpack Compose architecture, navigation, ViewModels, repositories, protocol, database schema, and API contracts intact.

The app brand changes from `SelfHostedIM` to `ByteIM` for user-visible product text.

## Source Context

- Product requirements: `docs/PRD/IM_PRODUCT_REQUIREMENTS_CN_EN.md`
- Prototype pages: `docs/PRD/pages/`
- Project background: `docs/projectBackground.md`
- Development status: `docs/DEVELOPMENT_STATUS.md`
- Development constraints: `docs/DEVELOPMENT-CONSTRAINTS.md`

The current client is an Android Kotlin single-module app using Jetpack Compose. The UI surface is mainly implemented in:

- `app/src/main/java/com/codex/im/auth/LoginScreen.kt`
- `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- `app/src/main/java/com/codex/im/contacts/ContactListScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatImagePreviewScreen.kt`
- `app/src/main/java/com/codex/im/group/GroupCreateScreen.kt`
- `app/src/main/java/com/codex/im/profile/MeScreen.kt`

## Scope

The redesign covers all client pages represented by the prototypes:

- Login and register
- Messages tab and conversation list
- Contacts tab
- Me tab
- Profile display and profile edit flows
- Group creation contact selection
- Single chat
- Group chat
- Chat image preview where needed for visual consistency
- Chat interaction states including copy, recall, send state, image loading, image failure, mention display, and reconnect status presentation

The redesign does not change:

- WebSocket protocol
- HTTP API contracts
- SQLite schemas
- Message ordering, ACK, retry, recall, read receipt, image upload, or group-chat behavior
- Existing route semantics and Back behavior
- Existing ViewModel ownership of state

## Approach

Use a lightweight ByteIM UI layer and apply it page by page.

This is preferred over direct per-screen styling because it keeps list rows, top bars, badges, chat bubbles, and spacing consistent without forcing a broad Material theme rewrite. It is also preferred over a full theme migration because the current goal is visual alignment with the prototypes, not a foundational design-system refactor.

## Visual System

Create small Compose UI constants and shared components for the redesign. These should be scoped to UI appearance and avoid owning business state.

### Brand

- User-visible app name: `ByteIM`
- Update `app_name`, login title, and PRD product text where appropriate.
- Keep internal class names such as `SelfHostedImRoute` unless a rename is necessary for user-visible behavior. Avoid broad symbolic renames that increase risk without improving the UI.

### Colors

Use the prototype palette as the source of truth:

- App background: light gray, close to `#EDEDED`
- Primary surfaces: white or near-white
- Dividers: light gray, close to `#EEEEEE`
- Primary accent: green for selected navigation, add actions, send button, success/read indicators
- Self message bubble: WeChat-style green, close to `#95EC69`
- Peer message bubble: white
- Error and unread badges: red
- Secondary text: medium gray
- Primary text: near black

### Layout Metrics

Use dimensions close to the prototypes:

- Top app bar: about `56dp`
- Bottom navigation: about `56dp` to `64dp`
- List rows: about `72dp`
- Conversation and profile avatars: about `48dp` to `50dp`
- Chat avatars: about `40dp`
- Page horizontal edge margin: `16dp`
- Chat bubble horizontal padding: about `12dp`
- Chat bubble vertical padding: about `8dp`

### Typography

Keep the system/Material font stack. Do not add web fonts from the HTML prototypes. Match the prototypes through practical Compose typography choices:

- Top bar title: medium to semibold, about Material title scale
- List title: medium weight, single line
- List preview: smaller gray text, single line with ellipsis
- Timestamp and system notices: small gray text
- Chat body: readable body size, no dense debug/status text in the main bubble area

### Shared Components

Introduce shared UI pieces only where they reduce duplication:

- ByteIM top bar for main and secondary pages
- ByteIM list row styling for conversation, contacts, profile menu, and group selection rows
- ByteIM unread badge
- ByteIM bottom navigation styling where needed
- Chat bubble style helpers for self, peer, image, loading, failure, and centered notices

Existing components such as `AvatarImage` should be reused and visually wrapped rather than replaced.

## Page Design

### Login and Register

Keep the current `LoginScreen` mode switch between login and register.

Visual changes:

- Title becomes `ByteIM`.
- Page uses a light gray background with a clean form area.
- Inputs and buttons follow the prototype direction with full-width controls.
- Loading and error states remain visible and non-blocking.
- Registration still validates password confirmation locally before sending a request.

### Messages

Keep the current route, ViewModel, unread calculation, connection status, and plus-menu behavior.

Visual changes:

- Top bar shows `Message` or `Message(n)`.
- Right-side add button uses the green accent.
- Add menu contains current supported entries: start group chat and add friend. Unsupported prototype-only actions must not create fake behavior.
- Conversation rows use avatar, name, last message preview, time, unread badge, and mention prefix styling.
- Empty state remains real and does not create mock conversations.
- Connection/reconnect status appears as a lightweight inline notice instead of visually dominating the page.

### Contacts

Keep current contacts source and navigation to single chat.

Visual changes:

- Top bar matches the Messages/Me structure.
- Contact rows use the shared avatar/list-row styling.
- Rows show name and useful secondary identity text where available.
- Dividers and touch feedback match the prototype direction.

### Me, Profile, and Edit

Keep current profile fetch, avatar upload, nickname edit, and logout behavior.

Visual changes:

- Me page uses a profile header with larger avatar, display name, account ID, QR/chevron affordances where supported visually.
- Menu rows are grouped on the gray background using white surfaces.
- Profile display and edit pages use the same top bar/back semantics as existing code.
- Avatar upload and nickname edit controls become visually consistent with the prototypes.

### Group Create

Keep the existing authenticated group creation flow.

Visual changes:

- Page becomes a contact-selection screen with a secondary top bar.
- Contact rows support selected/unselected visual states.
- Confirmation action is visually clear and disabled when the current ViewModel state does not allow submit.
- Loading and error states remain visible.

### Chat

Keep the current `ChatScreen`, `ChatViewModel`, message loading, send, image, recall, read receipt, retry, mention, and group rename behavior.

Visual changes:

- Top bar uses back button, conversation name, and a more button.
- Message area uses the prototype gray chat background.
- Self messages are right-aligned green bubbles.
- Peer messages are left-aligned white bubbles.
- Group chat shows sender names for peer messages.
- Centered system notices are small gray pills or light text notices.
- Composer stays at the bottom with text input, image action, mention behavior, and send button.
- Connection or error notices use compact inline presentation.

### Chat Interaction States

Preserve existing state semantics, but reduce visual noise.

- Pending/sent/read/failed status appears near the message, not as bulky row text.
- Failed messages keep retry affordance.
- Image loading overlays use compact progress treatment.
- Image failures show retry visually without changing retry behavior.
- Long-press copy/recall actions use a compact dark action menu similar to the prototype.
- Recalled messages appear as centered system notices.
- Mention display in conversation previews and chat text uses the existing mention data and a red accent where applicable.

## Testing and Verification

Verification should prioritize regression safety because the task is UI-focused:

- Run the existing Android JVM unit tests, preferably `bash ./gradlew :app:testDebugUnitTest`.
- Run focused tests for UI policies if new pure Kotlin policy helpers are introduced.
- Compile after UI edits to catch Compose signature and resource issues.
- Use `rg` to confirm user-visible `SelfHostedIM` strings are replaced with `ByteIM`.
- If emulator verification is available, manually inspect the main flows:
  - Login/register
  - Messages tab, unread badge, plus menu
  - Contacts to single chat
  - Single chat text/image send states
  - Group creation and group chat
  - Me/profile/edit/avatar flow

## Risks and Mitigations

- Risk: UI styling changes accidentally alter business behavior.
  - Mitigation: Keep ViewModels, repositories, route names, and protocol code unchanged unless strictly necessary for UI state display.
- Risk: Broad theme changes cause unrelated visual or test churn.
  - Mitigation: Use scoped ByteIM UI constants/components instead of a full Material theme migration.
- Risk: Prototype-only actions imply unimplemented features.
  - Mitigation: Only expose behavior already supported by the app, or keep unsupported entries inert if they already exist in current UI.
- Risk: Chat page is dense and contains many states.
  - Mitigation: Preserve current message row classification and display policies where possible, changing only presentation and adding tests for any new policy extraction.

## Acceptance Criteria

- The app branch is `redesign-ui`.
- User-visible app name is `ByteIM`.
- All client pages listed in Scope visually align with the `docs/PRD/pages/` direction while remaining Compose-native.
- Existing business behavior, navigation semantics, protocol, persistence, and APIs are unchanged.
- Existing unit tests pass or any unrelated baseline failure is documented.
- No fake data or fake conversations are introduced to match prototypes.
- Unsupported prototype actions do not become misleading functional UI.
