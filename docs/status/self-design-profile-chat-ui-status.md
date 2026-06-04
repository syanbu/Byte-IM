# Self-Design Profile and Chat UI Status

## Scope

This document consolidates the recent self-design profile/chat status notes.
The work is mostly Android UI and interaction polish, but not purely visual:
some profile, avatar, navigation, and offline-message behavior required local
storage, HTTP API, mock-server, and routing support.

Consolidated from:

- `self-desgin-profile-chat-ui-development-status.md`
- `self-desgin-profile-edit-development-status.md`
- `self-design-chat-back-navigation-development-plan.md`
- `self-design-chat-back-navigation-status.md`
- `self-design-chat-ui-interaction-status.md`

## Status

Implemented for the current Android client and local mock-server demo scope.

Manual emulator/device verification is still recommended for profile/avatar
flows, chat visual behavior, vector tab icons, back navigation, and real OSS
avatar upload.

## Design Intent

The self-design pass moves the app from a basic engineering UI toward a more
IM-like experience:

- profile data is visible and editable from `Me`
- avatar and nickname are first-class UI data
- conversation and chat screens show peer/group identity instead of raw IDs
- Messages, Contacts, and Me are separated into top-level tabs
- chat rows and composer UI avoid debug/status text
- back navigation follows expected Android and top-level tab semantics
- visual stability issues such as avatar flashing and chat-back residue are
  reduced

## UI Changes

### Top-Level Navigation

- Added bottom navigation for `Messages`, `Contacts`, and `Me`.
- Removed logout from `Messages`; logout now lives under `Me`.
- Added vector drawable tab icons:
  - `ic_nav_message.xml`
  - `ic_nav_contacts.xml`
  - `ic_nav_me.xml`
- `Messages`, `Contacts`, and `Me` are treated as top-level app routes.
- System Back on top-level routes moves/exits the task according to
  `TopLevelBackPolicy`.

### Chat Navigation

- Authenticated screens use Navigation Compose.
- Authentication remains outside the authenticated `NavHost`.
- The authenticated graph starts at `conversations`.
- Conversation rows navigate to chat routes instead of replacing state manually.
- Chat top Back and Android system/gesture Back return to the conversation list.
- Chat back now navigates before closing the active repository conversation,
  avoiding visual residue during the transition.
- `Scaffold` and `NavHost` backgrounds were stabilized to avoid transient
  visual gaps while switching between chat and conversation list layouts.

### Chat UI Cleanup

- Removed user-visible debug/login text from the chat page.
- Removed message status text such as `SENDING`, `SENT`, `FAILED`, and
  `RECEIVED` from normal message rows.
- Removed the old `No more local messages` end-of-history text.
- Kept loading-more and memory-limit indicators where they still communicate
  active state.
- Removed the old user-visible chat Back label.
- Chat rows render message content and avatar identity without phone-ID prefixes.

### Chat Composer

- Replaced the generic form-like input row with a dedicated bottom composer bar.
- Removed the visible `Message` field label.
- Used a softer chat-input surface and subtle divider.
- The send button appears only when the trimmed draft has content.
- Composer display rules are covered by `ChatDisplayPolicyTest`.

### Conversation List

- Conversation rows now render peer nickname and avatar from the local profile
  cache when available.
- Empty `Messages` no longer shows a fixed default demo peer.
- Conversation rows are deduplicated by canonical `conversationId`.
- Contacts discovery moved to the separate Contacts tab.

### Contacts

- Added a `Contacts` top-level tab.
- Demo accounts are mutual demo contacts.
- Contact entry behavior later routes through the contact profile page before
  entering chat.

### Me and Profile UI

- `Me` shows the current user's avatar, phone/ID, and nickname.
- The profile summary block is the entry point to profile details.
- Removed the standalone `Edit Profile` button.
- Profile detail shows list rows for:
  - Avatar
  - Name
  - read-only ID
- Avatar editing and name editing are separated.
- Tapping the avatar row saves avatar changes without entering name editing.
- Tapping the name row opens a dedicated name editor page.
- The name editor has a top-right save action and a one-line underline input.
- Profile detail and editor Back behavior is handled by `MeBackPolicy`.

## Non-UI Support Added

These changes support the UI but are not purely UI-layer changes.

### Android Profile Data

- Added `UserProfile` model and `user_profiles` local SQLite table.
- Added profile DAO implementations.
- Extended auth/session parsing with phone, nickname, avatar URL, and profile
  timestamps.
- Added profile API/parser/repository boundary.
- Profile cache is used by conversation rows and chat state.

### Avatar Loading and Caching

- Added `AvatarImageCache` with memory and disk byte caching.
- Added decoded bitmap memory caching in `AvatarImage`.
- Blank avatar URLs safely fall back to placeholders.
- This reduced avatar placeholder flashes when scrolling chat history.

### Profile Edit and Avatar Upload

- Added avatar image picking from Android media.
- Selected avatar images are compressed to JPEG under 1 MB before upload.
- Added avatar upload API boundary:
  - request signed upload target
  - upload compressed bytes to signed URL
  - persist updated profile through `PUT /users/me`
- Manual avatar URL input was removed from the UI.

### Mock-Server Profile and OSS Support

- Extended user records and mock SQLite users table with nickname/avatar fields.
- Registration defaults nickname to phone.
- Auth responses include profile fields.
- Added profile endpoints:
  - `GET /users/me`
  - `GET /users/{userId}`
  - `PUT /users/me`
  - `POST /users/batch`
- Added `POST /oss/avatar/upload-target`.
- OSS runtime config is read from environment variables.
- Missing OSS credentials return JSON failure instead of crashing.
- Login responses keep `username` as phone after nickname changes, avoiding
  Android auth validation failures.

### Offline Demo Delivery

- Mock-server now keeps an in-memory per-receiver offline queue.
- Queued messages are sent as `RECEIVE_MESSAGE` after receiver auth.
- Android conversation-list collection starts before connect, avoiding a race
  where auth-time packets could arrive before collectors were installed.

## Key Bugs Fixed

1. Chat avatar load delay and default avatar flash

   Cause: avatar bytes were fetched/decoded repeatedly per composable load path.
   Fix: memory/disk avatar byte cache plus decoded bitmap cache.

2. Bottom navigation icons were placeholders or scaled bitmaps

   Cause: early implementation used text placeholders, then large PNGs.
   Fix: `BottomNavigationSpec` and Android vector drawables.

3. Back from `Me` returned to `Messages`

   Cause: top-level tabs were pushed onto the Navigation back stack.
   Fix: `TopLevelBackPolicy` and route-specific Back handling.

4. Chat back visual residue

   Cause: active conversation closed before navigation pop, causing state/layout
   changes in the same frame.
   Fix: navigate first, close conversation after pop ordering.

5. Chat composer looked like a form field

   Cause: generic Material field treatment.
   Fix: dedicated composer bar, no visible label, conditional send button.

6. Me profile editing was too button-driven

   Cause: separate edit button and inline edit mode.
   Fix: tappable profile entry, profile detail list, dedicated name editor.

7. Avatar and name editing were coupled

   Cause: avatar selection reused name-editing state.
   Fix: independent avatar save path.

8. Profile subpage system Back exited the app

   Cause: top-level `Me` BackHandler competed with subpage BackHandler.
   Fix: unified profile Back handling through `MeBackPolicy`.

9. Empty Messages showed a hard-coded demo conversation

   Cause: conversation list default-peer fallback.
   Fix: remove fallback and expose demo users through Contacts.

10. Offline receiver did not see messages after login

    Cause: mock-server skipped offline receivers and Android could miss immediate
    auth-time packets.
    Fix: in-memory offline queue plus collector-before-connect ordering.

## Verification Summary

The original split status files recorded these verification groups:

- Android profile/profile-cache/profile-display targeted tests passed.
- Android profile edit/avatar upload targeted tests passed.
- Android full JVM tests passed after the relevant profile/chat UI passes.
- Android debug builds passed after the relevant profile/chat UI passes.
- Mock-server profile, auth, OSS upload-target, and offline delivery tests
  passed.
- Navigation, top-level Back, chat Back, chat composer, Me display, Me back,
  Contacts, and conversation-list behavior have focused JVM coverage.

Representative test classes:

- `BottomNavigationSpecTest`
- `TopLevelBackPolicyTest`
- `ChatBackPolicyTest`
- `ChatDisplayPolicyTest`
- `ConversationListViewModelTest`
- `ContactListViewModelTest`
- `MeDisplayPolicyTest`
- `MeBackPolicyTest`
- `MeViewModelTest`
- `AvatarImageCacheTest`
- `ProfileRepositoryTest`
- `ProfileJsonParserTest`
- mock-server `AuthServiceTest`
- mock-server `OssUploadServiceTest`
- mock-server `MessageRouterTest`

## Current Risks

- Real Android gallery picking, avatar compression, and OSS upload still need
  manual emulator/device verification against the configured OSS bucket.
- OSS upload requires valid backend environment variables and bucket policy.
- No Compose screenshot/UI automation is configured for these visual surfaces.
- The offline queue is demo in-memory support; it is not durable recovery.
- Avatar caching is intentionally lightweight; a production client may later use
  a dedicated image loading stack for richer eviction/retry policies.

## Superseded Files

The following files now intentionally contain only a redirect note:

- `self-desgin-profile-chat-ui-development-status.md`
- `self-desgin-profile-edit-development-status.md`
- `self-design-chat-back-navigation-development-plan.md`
- `self-design-chat-back-navigation-status.md`
- `self-design-chat-ui-interaction-status.md`
