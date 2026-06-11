# Bug Plan: Chat Initial Blank Screen Optimization

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the blank screen flash when entering chat conversations. First entry uses controlled pre-load blocking at click time (WeChat style), subsequent entries use in-memory cache for instant render.

**Architecture:** Implement a two-layer optimization:
1. **Repository-layer LRU cache** for initial message pages (20 messages) across conversation IDs
2. **Synchronous pre-load** at conversation click time (before navigation) to block for ~30-50ms in exchange for instant content render
3. **ViewModel init-time cache check** to populate state before first composition

Cache invalidation happens on message send/receive/recall for the active conversation.

**Tech Stack:** Android Kotlin, Jetpack Compose Navigation, SQLite DAO, LinkedHashMap LRU cache, Kotlin Coroutines.

---

## Status

- Status: Planning
- Created on: 2026-06-11
- Branch: current working branch
- Scope: MessageRepository caching, ConversationList navigation, ChatViewModel initialization

## Observed Symptoms

Every time user enters any chat (single or group):

1. User taps a conversation in the conversation list.
2. Navigation transition starts immediately.
3. Chat screen renders with empty/gray background (1-2 frames).
4. Messages appear after ~50-100ms.
5. **Even returning to the same conversation shows the same blank flash.**

Expected behavior (WeChat style):

- First entry: Slight delay at tap (before navigation), then chat opens with content already visible.
- Second+ entry to the same conversation: Instant render with no blank.
- The user should never see a blank message list.

## Pre-Fix Code Evidence

### 1. No cross-navigation state retention

**`app/src/main/java/com/buyansong/im/MainActivity.kt`**

```kotlin
composable(route = SelfHostedImRoute.Chat.pattern) { chatBackStackEntry ->
    // ❌ remember is scoped to this composable; back navigation destroys it
    val chatViewModel = remember(session.userId, conversationId) {
        ChatViewModel(...)  // New instance EVERY navigation
    }
}
```

`remember` is **not a global cache**. It lives and dies with the NavHost composition.

### 2. Data load happens AFTER first composition

**`app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`**

```kotlin
// First frame renders BEFORE this runs
LaunchedEffect(viewModel) {
    viewModel.start()  // Triggers refreshInitialPage()
}
```

`LaunchedEffect` runs after the first composition. Frame 1 always has empty messages.

### 3. Every entry hits SQLite

**`app/src/main/java/com/buyansong/im/message/MessageRepository.kt`**

```kotlin
fun historyPageByConversationId(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
    // ❌ No cache; every call hits SQLite
    return messageDao.queryPage(conversationId, beforeTime, limit)
        .filter { it.isReadyForChatDisplay() }
}
```

No in-memory cache means even re-entering the same conversation within 1 second requires a full DB query.

### 4. Navigation has no pre-load hook

**`app/src/main/java/com/buyansong/im/MainActivity.kt`**

```kotlin
onOpenConversation = { conversationId ->
    // ❌ Navigate immediately; no time to pre-fetch
    SelfHostedImRoute.Chat.createRoute(conversationId)
        ?.let(navController::navigateToChat)
}
```

## Root Cause

Three cascading issues cause the blank flash:

| Layer | Problem |
|-------|---------|
| **Navigation** | No pre-load window between tap and transition |
| **ViewModel** | Created fresh on every navigation; no cross-navigation cache |
| **Compose** | `LaunchedEffect` runs post-composition; first frame guaranteed empty |

The `remember(key)` pattern only avoids recreation within the same composition lifetime. It does NOT survive navigation back-stack destruction.

## Why This Is Not a Compose Performance Issue

This is not about LazyColumn rendering speed. Even if Compose rendered instantly:

1. Initial `ChatUiState.messages` is `emptyList()`.
2. DB query is async and takes non-zero time.
3. Frame 1 will always be empty without pre-loading.

## Non-Goals

- Do NOT implement Navigation Component `saveState` — this would serialize to Bundle and cause ANRs on large message lists.
- Do NOT make ChatViewModel a global singleton — memory leak risk.
- Do NOT change SQLite query optimization or indexing (already indexed by `conversation_id + created_at`).
- Do NOT add shimmer/skeleton loading — the goal is NO loading state visible.
- Do NOT pre-load ALL conversations upfront — memory constrained on low-end devices.

## Implementation Plan

### Task 1: Add LRU Cache to MessageRepository

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`

**Goal:** Cache the latest 10 conversation initial pages (20 messages each) in memory.

- [ ] **Step 1a: Add synchronized LRU cache field**

```kotlin
// Cache: conversationId -> List<ChatMessage> (initial page only)
// LRU eviction after 10 entries
private val initialPageCache = Collections.synchronizedMap(
    object : LinkedHashMap<String, List<ChatMessage>>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChatMessage>>?): Boolean {
            return size > 10
        }
    }
)
```

- [ ] **Step 1b: Modify `historyPageByConversationId` to check cache first**

```kotlin
fun historyPageByConversationId(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
    // Only cache the INITIAL page (beforeTime = null)
    if (beforeTime == null) {
        initialPageCache[conversationId]?.let { return it }
    }
    
    val result = messageDao.queryPage(conversationId, beforeTime, limit)
        .filter { it.isReadyForChatDisplay() }
    
    if (beforeTime == null) {
        initialPageCache[conversationId] = result
    }
    return result
}
```

- [ ] **Step 1c: Add cache invalidation on message changes**

Call `initialPageCache.remove(conversationId)` in these paths:
- `handleIncoming()` - new message received
- `completeImageUploadAndQueueSend()` - message sent
- `recallMessage()` - message recalled
- `onConversationCleared()` - if such exists

- [ ] **Step 1d: Add explicit cache accessor for ViewModel**

```kotlin
fun getCachedInitialPage(conversationId: String): List<ChatMessage>? {
    return initialPageCache[conversationId]
}
```

---

### Task 2: Add Synchronous Pre-Load API

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`

**Goal:** Add a blocking pre-load method for use at click time.

- [ ] **Step 2a: Add `preloadInitialPageSync()` with timeout protection**

```kotlin
/**
 * Pre-load initial page synchronously (blocks caller).
 * Returns immediately if cached.
 * Has 100ms hard timeout to prevent ANR in worst case.
 */
fun preloadInitialPageSync(conversationId: String): List<ChatMessage> {
    initialPageCache[conversationId]?.let { return it }
    
    return runBlocking {
        withTimeoutOrNull(100) {
            withContext(Dispatchers.IO) {
                val messages = messageDao.queryPage(conversationId, null, 20)
                    .filter { it.isReadyForChatDisplay() }
                initialPageCache[conversationId] = messages
                messages
            }
        } ?: emptyList()  // Fallback: let async load handle it
    }
}
```

- [ ] **Step 2b: Add async preload variant for background pre-warming**

```kotlin
fun preloadInitialPageAsync(conversationId: String, scope: CoroutineScope) {
    if (initialPageCache.containsKey(conversationId)) return
    
    scope.launch(Dispatchers.IO) {
        val messages = messageDao.queryPage(conversationId, null, 20)
            .filter { it.isReadyForChatDisplay() }
        initialPageCache[conversationId] = messages
    }
}
```

---

### Task 3: Pre-Load At Conversation Click Time

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt` (conversation list click handler)

**Goal:** Block for ~30ms before navigation to prime the cache.

- [ ] **Step 3a: Pre-load before navigation in `onOpenConversation`**

```kotlin
onOpenConversation = { conversationId ->
    // ✅ WeChat-style: Pre-load FIRST, then navigate
    // User sees slight tap delay but NO blank screen after transition
    messageRepository.preloadInitialPageSync(conversationId)
    
    SelfHostedImRoute.Chat.createRoute(conversationId)
        ?.let(navController::navigateToChat)
}
```

- [ ] **Step 3b: Apply same pre-load to other chat entry points**
  - Contact profile "Send Message" button
  - Group list "Open Group" button
  - Push notification deep link handler (optional - push may arrive cold anyway)

---

### Task 4: Populate ViewModel State BEFORE First Composition

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`

**Goal:** Check cache in `init {}` so `state.messages` is non-empty before Compose runs.

- [ ] **Step 4a: Check cache immediately in ViewModel init**

```kotlin
init {
    // BEFORE LaunchedEffect runs: check if cache has data
    if (initialPeerId.isNotBlank()) {
        repository.getCachedInitialPage(initialPeerId)?.let { cached ->
            mutableState.value = mutableState.value.copy(
                messages = cached,
                // Also set hasMoreLocal if cache hit
                hasMoreLocal = cached.size >= HISTORY_PAGE_SIZE
            )
        }
        
        // Still trigger async refresh in background (in case cache is stale)
        scope.launch(dispatcher) {
            refreshInitialPage()
        }
    }
}
```

- [ ] **Step 4b: Remove redundant start() trigger if cache hit**

Verify `start()` still observes conversation updates but doesn't cause redundant DB query.

---

### Task 5: Cache Invalidation Tests

**Files:**

- New: `app/src/test/java/com/buyansong/im/message/MessageRepositoryCacheTest.kt`

**Goal:** Ensure cache correctly invalidates on message changes.

- [ ] **Step 5a: Test cache hit on repeated queries**
- [ ] **Step 5b: Test LRU eviction after 11 conversations**
- [ ] **Step 5c: Test cache invalidation after new message arrival**
- [ ] **Step 5d: Test timeout protection doesn't break normal flow**

---

### Task 6: Optional - Conversation List Pre-Warming

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`

**Goal:** Pre-warm top 3 visible conversations in background.

- [ ] **Step 6a: Async pre-load top 3 conversations when list is visible**

```kotlin
// In ConversationListViewModel, after page loads
state.conversations.take(3).forEach { conversation ->
    repository.preloadInitialPageAsync(conversation.id, scope)
}
```

This is opportunistic and has no correctness impact.

---

## Verification Plan

### Manual Verification Steps

1. **First entry cold start:**
   - [ ] Kill app
   - [ ] Open conversation list
   - [ ] Tap any conversation
   - [ ] Verify: No blank screen visible; messages appear immediately after transition

2. **Second entry same conversation:**
   - [ ] Press back to conversation list
   - [ ] Tap the same conversation again
   - [ ] Verify: Instant render; zero perceptible delay

3. **Cross-conversation switching:**
   - [ ] Open conversation A
   - [ ] Back to list
   - [ ] Open conversation B
   - [ ] Back to list
   - [ ] Open conversation A again
   - [ ] Verify: Both conversations open quickly (A may be evicted if >10 conversations in between)

4. **New message cache invalidation:**
   - [ ] Have device B send a message while device A is in conversation list
   - [ ] Tap the updated conversation
   - [ ] Verify: New message is visible (not stale cached data)

5. **Send message invalidation:**
   - [ ] Enter a conversation
   - [ ] Send a text message
   - [ ] Back to list
   - [ ] Re-enter same conversation
   - [ ] Verify: Sent message is visible

### Automated Verification

- [ ] All existing `ChatViewModelTest` cases pass
- [ ] All existing `MessageRepositoryTest` cases pass
- [ ] New `MessageRepositoryCacheTest` cases pass (Task 5)

### Performance Profiling (Optional)

- Measure: SQLite query time for initial page (should be <30ms on most devices)
- Measure: Time from tap to first chat frame (should improve by ~1 frame)
- Measure: Memory overhead of 10 cached conversations × 20 messages = negligible

## Rollback Strategy

If the synchronous pre-load causes perceived tap lag complaints:

1. Reduce `preloadInitialPageSync` timeout from 100ms to 50ms
2. Or remove the sync preload entirely but keep the LRU cache (still fixes 2nd+ entry)
3. Or move preload to happen in parallel with navigation transition (compromise)

## Related Docs

- [`feature-notes/B4-history-pagination-design-notes.md`](../feature-notes/B4-history-pagination-design-notes.md) - History page size and ordering
- [`feature-notes/user-profile-version-cache.md`](../feature-notes/user-profile-version-cache.md) - Similar caching pattern for user profiles
