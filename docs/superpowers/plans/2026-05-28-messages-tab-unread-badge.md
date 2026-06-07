# Messages Tab Unread Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a WeChat-style unread badge to the bottom `Messages` tab that shows the total unread count across all conversations and caps display at `99+`.

**Architecture:** Keep unread counts sourced from conversation summary storage, add a total-unread query to the conversation DAO/repository path, and let the bottom-navigation layer react to `MessageRepository.conversationUpdates` so the badge stays live across `Messages`, `Contacts`, and `Me`.

**Tech Stack:** Android Kotlin, Jetpack Compose Material 3, SQLite DAO layer, repository shared-flow updates, JUnit unit tests.

---

### Task 1: Add unread-count query coverage

**Files:**
- Modify: `app/src/test/java/com/buyansong/im/storage/ConversationDaoContractTest.kt`
- Modify: `app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt`

- [ ] **Step 1: Write the failing DAO test**

- [ ] **Step 2: Run the targeted DAO test to verify it fails**

- [ ] **Step 3: Write the failing repository test for total unread passthrough**

- [ ] **Step 4: Run the targeted repository test to verify it fails**

### Task 2: Add unread-count query implementation

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/ConversationDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/AndroidConversationDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`

- [ ] **Step 1: Add `totalUnreadCount()` to the DAO interface and in-memory implementation**

- [ ] **Step 2: Add SQLite `SUM(unread_count)` support in `AndroidConversationDao`**

- [ ] **Step 3: Add `MessageRepository.totalUnreadCount()` passthrough**

- [ ] **Step 4: Run the targeted unread-count tests to verify they pass**

### Task 3: Add bottom-tab badge display rules

**Files:**
- Create: `app/src/test/java/com/buyansong/im/MessagesTabBadgePolicyTest.kt`
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`

- [ ] **Step 1: Write failing badge policy tests for hidden/1/99/99+ cases**

- [ ] **Step 2: Run the targeted badge policy test to verify it fails**

- [ ] **Step 3: Add minimal badge formatting and icon rendering support**

- [ ] **Step 4: Run the targeted badge policy test to verify it passes**

### Task 4: Wire live badge updates into bottom navigation

**Files:**
- Modify: `app/src/test/java/com/buyansong/im/MainActivity` or nearby app-level tests if needed
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`

- [ ] **Step 1: Add a failing test for live badge refresh if the existing test surface supports it; otherwise rely on repository/DAO tests plus manual Compose logic inspection**

- [ ] **Step 2: In `AuthenticatedImNavHost`, collect `conversationUpdates`, re-read total unread count, and pass it to the `Messages` tab icon**

- [ ] **Step 3: Ensure the initial badge count is loaded once on composition so stored unread survives app restart/session restore**

- [ ] **Step 4: Run targeted tests for badge policy and repository/DAO behavior**

### Task 5: Final verification

**Files:**
- Modify: `docs/status/B3-conversation-list.md` only if behavior notes need sync during implementation

- [ ] **Step 1: Run targeted unit tests for storage, repository, and badge policy**

- [ ] **Step 2: Run the full Android unit test suite**

- [ ] **Step 3: Review diff for scope control and confirm the implementation matches `docs/bug/Add-MessagesTabUnreadBadge.md`**
