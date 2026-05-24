# self-desgin Profile Chat UI Development Status

## Branch

`IM-b1TOb5-fix-chat`

## Scope

Implement the approved self-desgin profile/chat UI slice:

- Persist design and implementation plan.
- Add Android local user profile storage.
- Add Android profile API/repository boundary.
- Add Messages/Me bottom navigation.
- Move logout from Messages to Me.
- Show current user avatar, ID phone, and nickname on Me.
- Show peer nickname in chat top bar.
- Carry avatar URLs into conversation and chat UI state.
- Extend mock-server user records and profile endpoints.

## Confirmed Decisions

- Registration default nickname is the phone number.
- User ID is the phone number.
- Avatar files live in Alibaba Cloud OSS bucket `im-byte`.
- First version uses public-read avatar URLs.
- Long-lived OSS credentials remain backend-only and are not committed.

## Current Implementation State

- Design document: created.
- Implementation plan: created.
- Android implementation: completed for this slice.
- Mock-server implementation: completed for this slice.
- Verification: passed.

## Implemented Android Changes

- Added local `UserProfile` model and `user_profiles` SQLite table.
- Added `UserProfileDao`, in-memory DAO, and Android SQLite DAO.
- Extended `AuthSession` and auth parsing with phone, nickname, avatar URL, and profile timestamps.
- Added profile API/parser/repository boundary.
- Added bottom navigation with `Messages` and `Me`.
- Removed logout from `Messages`; logout now lives on `Me`.
- Added `MeScreen` and `MeViewModel` for current user avatar, ID phone, and nickname.
- Conversation rows now expose and render peer avatar URL and nickname from profile cache.
- Chat state now exposes peer nickname/avatar and current user avatar.
- Chat top bar now displays the peer nickname.
- Chat message rows render avatar placeholders/images and message content without phone-ID prefixes.

## Implemented Mock-Server Changes

- Extended `UserRecord` and `users` table with nickname, avatar URL, avatar object key, avatar updated time, and updated time.
- Added migration-safe column creation for existing SQLite mock databases.
- Registration now defaults nickname to phone.
- Auth responses now include profile fields.
- Added profile lookup, profile update, and batch profile response support in `AuthService`.
- Added HTTP routes:
  - `GET /users/me`
  - `GET /users/{userId}`
  - `PUT /users/me`
  - `POST /users/batch`
- Profile routes use bearer access-token verification.

## Verification Log

| Area | Command | Result |
|---|---|---|
| Android targeted RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests ... --console=plain` | Failed as expected before implementation because profile types and APIs were missing. |
| Mock-server targeted RED | `mvn -q test` | Failed as expected before implementation because profile fields and service methods were missing. |
| Android targeted GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.storage.UserProfileDaoContractTest --tests com.codex.im.profile.ProfileJsonParserTest --tests com.codex.im.profile.ProfileRepositoryTest --tests com.codex.im.profile.MeViewModelTest --tests com.codex.im.auth.AuthJsonParserTest --tests com.codex.im.conversation.ConversationListViewModelTest --tests com.codex.im.chat.ChatViewModelTest --console=plain` | Passed. |
| Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Mock-server full tests | `mvn -q test` in `mock-server` | Passed. |
| Login crash regression RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.conversation.ConversationListViewModelTest.startDoesNotAddDuplicateDefaultConversationWhenCanonicalConversationAlreadyExists --console=plain` | Failed before fix, reproducing duplicate conversation key state. |
| Login crash regression GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.conversation.ConversationListViewModelTest --console=plain` | Passed after fix. |
| Post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Post-fix mock-server full tests | `mvn -q test` in `mock-server` | Passed. |
| Avatar cache regression RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.ui.AvatarImageCacheTest --console=plain` | Failed before implementation because `AvatarImageCache` was missing. |
| Avatar cache regression GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.ui.AvatarImageCacheTest --console=plain` | Passed after adding memory and disk avatar byte cache. |
| Avatar cache post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Avatar cache post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Bottom navigation icon RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.BottomNavigationSpecTest --console=plain` | Failed before implementation because `BottomNavigationSpec` was missing. |
| Bottom navigation icon GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.BottomNavigationSpecTest --console=plain` | Passed after binding Messages to `message.png` and Me to `me.png`. |
| Bottom navigation icon post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Bottom navigation icon post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Top-level back behavior RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.TopLevelBackPolicyTest --console=plain` | Failed before implementation because `TopLevelBackPolicy` was missing. |
| Top-level back behavior GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.TopLevelBackPolicyTest --console=plain` | Passed after marking Messages and Me as app-exit routes. |
| Top-level back behavior post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Top-level back behavior post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Chat back visual residue RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.ChatBackPolicyTest --console=plain` | Failed before implementation because `ChatBackPolicy` was missing. |
| Chat back visual residue GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.ChatBackPolicyTest --console=plain` | Passed after enforcing navigate-before-close ordering. |
| Chat back visual residue post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Chat back visual residue post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed after moving unsupported SVG source files out of `res/drawable`. |
| Bottom navigation vector icon RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.BottomNavigationSpecTest --console=plain` | Failed before implementation because `ic_nav_message` and `ic_nav_me` were missing. |
| Bottom navigation vector icon GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.BottomNavigationSpecTest --console=plain` | Passed after converting SVG sources to Android vector drawables. |
| Bottom navigation vector icon post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Bottom navigation vector icon post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Chat composer input bar RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatDisplayPolicyTest --console=plain` | Failed before implementation because `composerLabel` and `shouldShowSendButton` were missing. |
| Chat composer input bar GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatDisplayPolicyTest --console=plain` | Passed after adding composer label and send visibility policy. |
| Chat composer input bar post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Chat composer input bar post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| WeChat-style Me profile entry RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeDisplayPolicyTest --console=plain` | Failed before implementation because `MeDisplayPolicy` was missing. |
| WeChat-style Me profile entry GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeDisplayPolicyTest --console=plain` | Passed after removing the Edit Profile button policy and defining Avatar/Name/ID detail rows. |
| WeChat-style Me profile entry post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| WeChat-style Me profile entry post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Profile avatar/name edit separation RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeDisplayPolicyTest --tests com.codex.im.profile.MeViewModelTest --console=plain` | Failed before implementation because the dedicated name editor labels and independent avatar save API were missing. |
| Profile avatar/name edit separation GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeDisplayPolicyTest --tests com.codex.im.profile.MeViewModelTest --console=plain` | Passed after separating avatar save from name editing and moving name save to a dedicated page header. |
| Profile avatar/name edit separation post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Profile avatar/name edit separation post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Profile name underline input RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeDisplayPolicyTest --console=plain` | Failed before implementation because `nameEditorInputStyle` was missing. |
| Profile name underline input GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeDisplayPolicyTest --console=plain` | Passed after defining the underline input policy and replacing the outlined name field. |
| Profile name underline input post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Profile name underline input post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |
| Profile back semantics RED | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeBackPolicyTest --console=plain` | Failed before implementation because the unified `MeBackPolicy` did not exist. |
| Profile back semantics GREEN | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.profile.MeBackPolicyTest --console=plain` | Passed after defining Me top-level, Profile detail, and Name editor back actions. |
| Profile back semantics post-fix Android full JVM | `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain` | Passed. |
| Profile back semantics post-fix Android debug build | `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain` | Passed. |

## Bug Fix Log

### Chat Avatar Load Delay And Default Avatar Flash

Observed behavior:

- Entering chat could feel sluggish when many message rows showed avatars.
- Scrolling up through local history could briefly show the default avatar before refreshing to the real avatar.

Root cause:

- User profile data, including peer nickname and avatar URL, was already persisted in the local `user_profiles` table.
- The avatar image bytes were not persisted locally. `AvatarImage` opened the URL and decoded the image inside each composable load path.
- When `LazyColumn` recycled message rows during history scrolling, rows could be recreated with empty bitmap state, show the default placeholder, then fetch/decode the same URL again.

Fix:

- Added `AvatarImageCache` with per-instance memory byte cache and disk byte cache under Android `cacheDir/avatar-images`.
- The default Android cache is shared process-wide, so repeated rows with the same URL do not create separate download paths.
- `AvatarImage` now checks a small decoded bitmap memory cache before showing the placeholder and only falls back to byte loading when needed.
- Added regression tests for memory reuse, disk reuse across cache instances, and blank URL handling.

### Bottom Navigation Icons

Change:

- Replaced text placeholder icons in the bottom navigation with static drawable resources.
- Messages uses `app/src/main/res/drawable/message.png`.
- Me uses `app/src/main/res/drawable/me.png`.
- Added `BottomNavigationSpec` so route, label, and icon resource IDs are testable outside Compose.
- `MainActivity` renders the PNGs with `Image` and `painterResource`, preserving the original image colors.

Follow-up:

- Converted `app/src/main/assets/message.svg` to `app/src/main/res/drawable/ic_nav_message.xml`.
- Converted `app/src/main/assets/me.svg` to `app/src/main/res/drawable/ic_nav_me.xml`.
- Updated `BottomNavigationSpec` so the bottom tabs use vector drawables instead of PNG resources, avoiding bitmap downscale artifacts on Android.

### Top-Level Back Behavior

Observed behavior:

- Pressing Android back from `Me` returned to `Messages`.

Root cause:

- `Me` was opened with `navController.navigate(...)`, leaving `Messages` below it in the Navigation back stack.
- Android Navigation therefore handled system back by popping `Me` and revealing `Messages`.

Fix:

- Added `TopLevelBackPolicy` to define `Messages` and `Me` as top-level app-exit routes.
- `MainActivity` now installs a `BackHandler` for those routes and finishes the Activity.
- Chat routes are not app-exit routes, so chat back behavior remains returning to the conversation list.

### Chat Back Visual Residue

Observed behavior:

- Returning from chat to the conversation list could show a brief visual residue of the chat screen.

Root cause:

- Chat back previously closed the active conversation before popping the chat route.
- Closing the conversation can refresh list-related state while the navigation pop and bottom-bar layout change are happening in the same frame.
- Chat hides the bottom navigation while the conversation list shows it, so the layout height also changes during the transition.

Fix:

- Added `ChatBackPolicy` to enforce `popBackStack()` before `messageRepository.closeConversation()`.
- Added a stable `MaterialTheme.colorScheme.background` container color to `Scaffold`.
- Added a full-size background to the `NavHost` container before applying navigation padding.
- Moved raw SVG source files from `res/drawable` to `assets`; Android resource compilation continues to use `message.png` and `me.png`.

### Chat Composer Input Bar

Observed behavior:

- The chat input used a default Material `OutlinedTextField` with the visible label `Message`.
- The input and send controls sat directly in the page padding instead of a distinct bottom composer area.
- `Send` was visible even when there was no draft content, only disabled.

Design:

- The composer is a full-width bottom bar separated from chat history by a subtle top divider.
- The bottom bar uses a quiet surface color, and the inner text input uses a different surface color with a soft rounded border.
- The `Message` label is removed so the input reads as a chat composer, not a form field.
- The send button is hidden while the trimmed draft is blank and appears only when there is content to send.

Fix:

- Added `ChatDisplayPolicy.composerLabel` and `ChatDisplayPolicy.shouldShowSendButton(...)` for testable composer display rules.
- Replaced the bottom inline `OutlinedTextField` row with a dedicated `ChatComposerBar`.
- Added `AnimatedVisibility` around the send button so it only occupies space when the draft has sendable content.

### WeChat-Style Me Profile Entry

Observed behavior:

- `Me` used a separate `Edit Profile` button below the avatar, nickname, and ID summary.
- Editing happened inline on the `Me` page, so the profile summary and edit controls competed for the same surface.

Design:

- `Me` now treats the avatar, nickname, and ID block as a single tappable profile entry.
- The separate `Edit Profile` button is removed.
- Tapping the profile entry opens a profile detail page inside the `Me` tab.
- The profile detail page is a list with `Avatar`, `Name`, and read-only `ID` rows.
- Avatar and name rows can enter edit/save flow; ID remains informational.

Fix:

- Added `MeDisplayPolicy` so the absence of the edit button and the profile detail row labels are testable.
- Split `MeScreen` into a home summary and profile detail surface.
- Reused the existing `MeViewModel` draft/save/cancel behavior so profile updates still go through the same repository and upload path.

Follow-up:

- Fixed avatar/name edit coupling: tapping the avatar row no longer enters name editing.
- Added an independent avatar save path that uploads selected avatar bytes while preserving the current nickname.
- Tapping the name row now opens a dedicated name editor page.
- The name editor page has its own top-right Save action instead of showing a shared Save/Cancel block on the profile detail page.
- The name editor input now uses a single underline style instead of an outlined text box.
- The profile detail page now remains a simple Avatar / Name / ID list.

### Login Then Messages Crash

Observed crash:

```text
java.lang.IllegalArgumentException: Key "single:13800113800:13900113900" was already used.
```

Root cause:

- `ConversationListScreen` uses `conversationId` as the `LazyColumn` key.
- `ConversationListViewModel` added the default peer row by checking only `peerId`.
- A shared local database can contain the same canonical `single:<a>:<b>` conversation with a stored `peerId` that points at the current user, especially after switching between demo accounts.
- The default-peer fallback then added a second row with the same `conversationId`, causing Compose to crash.

Fix:

- Resolve the peer from canonical `single:<a>:<b>` conversation IDs relative to the current session user.
- Deduplicate visible rows by `conversationId`.
- Add the default peer only when the default canonical `conversationId` is absent.

## Session Issue Summary

This session found and fixed the following product/UI issues:

1. Chat avatar delay and placeholder flash

   - Problem: Chat rows could briefly show the default avatar while scrolling history, then update to the real avatar.
   - Cause: Profile metadata was persisted locally, but avatar image bytes were fetched and decoded from URL per composable load path.
   - Fix: Added `AvatarImageCache` with memory and disk byte caching, plus a decoded bitmap memory cache in `AvatarImage`.
   - Verification: Added `AvatarImageCacheTest`; full Android JVM tests and debug build passed.

2. Bottom tab icons were text placeholders

   - Problem: Bottom navigation showed text-style placeholder icons before using app resources.
   - Cause: `NavigationBarItem.icon` rendered `Text("M")` / `Text("Me")`.
   - Fix: Added `BottomNavigationSpec` and rendered drawable resources with `Image` + `painterResource`.
   - Verification: Added `BottomNavigationSpecTest`; full Android JVM tests and debug build passed.

3. Back from `Me` returned to `Messages`

   - Problem: Pressing Android back on `Me` returned to `Messages`, but both tabs should behave like top-level app pages.
   - Cause: `Me` was pushed on top of `Messages` in the Navigation back stack.
   - Fix: Added `TopLevelBackPolicy`; `Messages` and `Me` now use `BackHandler` to finish the Activity.
   - Verification: Added `TopLevelBackPolicyTest`; full Android JVM tests and debug build passed.

4. Visual residue when returning from chat

   - Problem: Returning from chat to the conversation list could show a brief visual residue of the chat screen.
   - Cause: Chat back closed the active conversation before popping the chat route, causing state refresh and navigation/layout changes in the same frame.
   - Fix: Added `ChatBackPolicy` to navigate back before closing the conversation; also stabilized `Scaffold` and `NavHost` backgrounds.
   - Verification: Added `ChatBackPolicyTest`; full Android JVM tests and debug build passed.

5. PNG tab icons looked jagged after scaling

   - Problem: 512x512 PNG icons could look jagged or fuzzy when scaled down to 24dp in bottom navigation.
   - Cause: Bitmap downscaling is not ideal for small tab icons; raw SVG files also cannot live directly under `res/drawable`.
   - Fix: Converted `app/src/main/assets/message.svg` and `app/src/main/assets/me.svg` to Android vector drawables `ic_nav_message.xml` and `ic_nav_me.xml`; bottom tabs now reference vector resources.
   - Verification: Updated `BottomNavigationSpecTest`; full Android JVM tests and debug build passed.

6. Chat composer looked like a form field

   - Problem: The bottom input showed a visible `Message` label, used the default outlined text field treatment, and kept a disabled `Send` button visible.
   - Cause: The first chat UI used a generic Material form row instead of a dedicated IM composer.
   - Fix: Added a full-width bottom composer band, a softer rounded input surface, no visible `Message` label, and conditional send button visibility based on non-blank draft content.
   - Verification: Added `ChatDisplayPolicyTest` coverage for label removal and send visibility.

7. Me profile editing entry was too button-driven

   - Problem: `Me` showed a standalone `Edit Profile` button instead of making the profile block itself the entry point.
   - Cause: The first profile edit UI optimized for implementation simplicity rather than the expected IM profile information pattern.
   - Fix: Removed the edit button, made the profile summary tappable, and added a profile detail list with Avatar, Name, and read-only ID rows.
   - Verification: Added `MeDisplayPolicyTest` coverage for no edit button label and the profile detail row labels.

8. Avatar and name editing were coupled

   - Problem: Tapping the Avatar row also put the Name row into edit mode, and profile detail showed a shared Save/Cancel area.
   - Cause: Avatar selection reused `startEditing()`, which represented name editing and drove the whole profile detail edit state.
   - Fix: Added a separate avatar-save path in `MeViewModel`; Name now opens a dedicated editor page with a top-right Save action.
   - Verification: Added `MeViewModelTest.saveAvatarBytesKeepsNameEditingClosedAndUsesCurrentNickname` and expanded `MeDisplayPolicyTest` for the name editor page labels.

9. Name editor input looked too heavy

   - Problem: The dedicated Name editor used a full outlined text field, which made the simple one-line edit surface look visually redundant.
   - Cause: It reused the generic form-field treatment instead of a lighter profile-edit pattern.
   - Fix: Replaced the outlined field with a one-line `BasicTextField` and bottom divider.
   - Verification: Added `MeDisplayPolicyTest.nameEditorUsesUnderlineInputStyle`; full Android JVM tests and debug build passed.

10. Profile subpage system Back exited the app

   - Problem: Pressing Android system Back on the Profile detail page exited the app instead of returning to `Me`.
   - Cause: `MainActivity` registered the `Me` top-level exit BackHandler after `MeScreen`, so it took priority over the profile subpage handler.
   - Fix: Added `MeBackPolicy` and routed both left-top back buttons and `BackHandler` through the same `MeScreen` `handleBack`; `MainActivity` no longer registers a competing `Me` route BackHandler.
   - Verification: Added `MeBackPolicyTest`; full Android JVM tests and debug build passed.

## Remaining Risks

- Real Android image picking, local compression to 1MB, and OSS upload credential issuance need emulator/device verification against the selected Android versions.
- This slice focuses on profile data and display plumbing.
- No Compose UI screenshot/manual emulator verification was run in this pass.
- Avatar image loading now has local byte and decoded bitmap caching; a production app may later replace it with a dedicated image loading library for more advanced eviction, retry, and transformation policies.
