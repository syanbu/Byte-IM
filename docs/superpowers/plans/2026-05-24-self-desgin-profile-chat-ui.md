# self-desgin Profile Chat UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first usable profile-aware IM UI: Messages/Me tabs, local user profiles, peer nickname in chat top bar, avatar URLs in UI state, and mock-server profile endpoints.

**Architecture:** Add a small `profile` module on Android with API, repository, DAO, and view models. Keep messages storing sender/receiver IDs; resolve display names and avatar URLs through local `UserProfile` records. Extend mock-server auth storage with profile fields and JSON endpoints.

**Tech Stack:** Android Kotlin, Jetpack Compose, SQLiteOpenHelper, OkHttp, Gson, Java mock-server, Netty HTTP handler, SQLite JDBC, JUnit.

---

### Task 1: Persist Project Design and Status

**Files:**
- Create: `docs/superpowers/specs/2026-05-24-self-desgin-profile-chat-ui-design.md`
- Create: `docs/superpowers/plans/2026-05-24-self-desgin-profile-chat-ui.md`
- Create: `docs/status/self-desgin-profile-chat-ui-development-status.md`

- [x] **Step 1: Save design document**

Capture the approved product and OSS decisions in the design document.

- [x] **Step 2: Save implementation plan**

Create this plan with TDD checkpoints.

- [x] **Step 3: Update status after implementation**

Record files changed, tests run, and remaining risks.

### Task 2: Android Profile Storage

**Files:**
- Modify: `app/src/main/java/com/codex/im/storage/StorageModels.kt`
- Create: `app/src/main/java/com/codex/im/storage/UserProfileDao.kt`
- Create: `app/src/main/java/com/codex/im/storage/AndroidUserProfileDao.kt`
- Modify: `app/src/main/java/com/codex/im/storage/ImDatabaseHelper.kt`
- Test: `app/src/test/java/com/codex/im/storage/UserProfileDaoContractTest.kt`

- [x] **Step 1: Write failing DAO contract test**

Assert an in-memory DAO stores profile fields, updates nickname/avatar, and returns null for missing users.

- [x] **Step 2: Run test and verify failure**

Run: `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --tests com.codex.im.storage.UserProfileDaoContractTest --console=plain`

- [x] **Step 3: Implement model and DAO**

Add `UserProfile`, `UserProfileDao`, `InMemoryUserProfileDao`, and Android SQLite table mapping.

- [x] **Step 4: Run test and verify pass**

Run the same test command.

### Task 3: Android Profile API and Repository

**Files:**
- Create: `app/src/main/java/com/codex/im/profile/UserProfileModels.kt`
- Create: `app/src/main/java/com/codex/im/profile/ProfileApi.kt`
- Create: `app/src/main/java/com/codex/im/profile/ProfileJsonParser.kt`
- Create: `app/src/main/java/com/codex/im/profile/OkHttpProfileApi.kt`
- Create: `app/src/main/java/com/codex/im/profile/ProfileRepository.kt`
- Modify: `app/src/main/java/com/codex/im/auth/AuthModels.kt`
- Modify: `app/src/main/java/com/codex/im/auth/AuthJsonParser.kt`
- Test: `app/src/test/java/com/codex/im/auth/AuthJsonParserTest.kt`
- Test: `app/src/test/java/com/codex/im/profile/ProfileJsonParserTest.kt`
- Test: `app/src/test/java/com/codex/im/profile/ProfileRepositoryTest.kt`

- [x] **Step 1: Write failing parser/repository tests**

Assert profile JSON parses, auth session carries profile data, repository caches `me`, fetches missing peer profiles, and updates local DAO.

- [x] **Step 2: Run tests and verify failure**

Run targeted profile/auth JVM tests.

- [x] **Step 3: Implement minimal profile API/repository**

Repository should read local first and call API for missing profiles.

- [x] **Step 4: Run targeted tests and verify pass**

Run the same targeted tests.

### Task 4: Android UI State and Navigation

**Files:**
- Modify: `app/src/main/java/com/codex/im/SelfHostedImRoute.kt`
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt`
- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListViewModel.kt`
- Modify: `app/src/main/java/com/codex/im/conversation/ConversationListScreen.kt`
- Modify: `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/codex/im/profile/MeViewModel.kt`
- Create: `app/src/main/java/com/codex/im/profile/MeScreen.kt`
- Create: `app/src/main/java/com/codex/im/ui/AvatarImage.kt`
- Test: `app/src/test/java/com/codex/im/conversation/ConversationListViewModelTest.kt`
- Test: `app/src/test/java/com/codex/im/chat/ChatViewModelTest.kt`
- Test: `app/src/test/java/com/codex/im/profile/MeViewModelTest.kt`

- [x] **Step 1: Write failing ViewModel tests**

Assert conversation items expose `peerAvatarUrl`, chat state exposes `peerName`/`peerAvatarUrl`, and Me VM exposes current user profile.

- [x] **Step 2: Run tests and verify failure**

Run targeted ViewModel tests.

- [x] **Step 3: Implement UI state and screens**

Add bottom tabs, move logout to Me, render avatar placeholders/URLs, and show chat title as peer nickname.

- [x] **Step 4: Run targeted tests and verify pass**

Run targeted ViewModel tests.

### Task 5: Mock-Server Profile Endpoints

**Files:**
- Modify: `mock-server/src/main/java/com/codex/imserver/auth/UserRecord.java`
- Modify: `mock-server/src/main/java/com/codex/imserver/auth/UserStore.java`
- Modify: `mock-server/src/main/java/com/codex/imserver/auth/AuthService.java`
- Modify: `mock-server/src/main/java/com/codex/imserver/netty/HttpAuthHandler.java`
- Test: `mock-server/src/test/java/com/codex/imserver/auth/AuthServiceTest.java`
- Test: `mock-server/src/test/java/com/codex/imserver/auth/UserStoreTest.java`

- [x] **Step 1: Write failing backend tests**

Assert registration defaults nickname to phone, update profile changes fields, and batch lookup returns profile JSON.

- [x] **Step 2: Run tests and verify failure**

Run: `mvn -q test` inside `mock-server`.

- [x] **Step 3: Implement backend profile fields and endpoints**

Add migration-safe columns and JSON handlers.

- [x] **Step 4: Run backend tests and verify pass**

Run `mvn -q test`.

### Task 6: Full Verification and Status

**Files:**
- Modify: `docs/status/self-desgin-profile-chat-ui-development-status.md`

- [x] **Step 1: Run Android JVM tests**

Run: `gradle-9.0.0\bin\gradle.bat :app:testDebugUnitTest --console=plain`

- [x] **Step 2: Run Android debug build**

Run: `gradle-9.0.0\bin\gradle.bat :app:assembleDebug --console=plain`

- [x] **Step 3: Run mock-server tests**

Run: `mvn -q test` inside `mock-server`.

- [x] **Step 4: Update development status**

Record completed scope, commands, results, and risks.

## Self-Review

- Spec coverage: profile model, OSS public-read decision, bottom tabs, Me logout, chat nickname, avatar URL state, and backend profile endpoints are covered.
- Placeholder scan: no TBD/TODO placeholders remain.
- Type consistency: Android type is `UserProfile`; backend JSON uses `userId`, `phone`, `nickname`, `avatarUrl`, `avatarObjectKey`, `avatarUpdatedAt`, and `updatedAt`.
