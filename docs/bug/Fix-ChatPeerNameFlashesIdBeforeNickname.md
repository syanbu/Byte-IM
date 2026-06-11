# Bug Plan: 聊天界面先显示ID后渲染昵称

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除进入单聊界面时顶栏先闪原始用户ID、再替换为昵称的问题。采用"本地缓存优先、异步刷新兜底"的策略，与会话列表 `ConversationListViewModel` 的现有模式保持一致。

---

## Status

- Status: Fixed
- Created on: 2026-06-11
- Branch: rename-redesign-ui
- Scope: ChatViewModel initialization, single-chat profile resolution

## Observed Symptoms

每次进入单聊界面：

1. 用户在会话列表点击一个对话。
2. 聊天界面打开，顶栏立即显示原始用户ID（如 `15000000001`）。
3. 约几百毫秒后（网络往返时间），ID被替换为对方昵称。
4. **即使本地 SQLite 已经缓存了该用户的资料，仍然先闪 ID。**

预期行为（参考会话列表）：

- 进入聊天时，如果本地有缓存的昵称，顶栏应立即显示昵称。
- 如果对方改了昵称，可以在后台刷新后更新，但不应先闪 ID。

## Pre-Fix Code Evidence

### 1. `ChatUiState.peerName` 默认值就是 `peerId`

**`app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`** (line 37-53)

```kotlin
data class ChatUiState(
    val peerId: String = "",
    val peerName: String = peerId,   // ❌ 默认值就是原始 peerId
    ...
)
```

第一帧必然显示 ID。

### 2. `hydrateInitialStateFromCache()` 不读取本地用户资料

**`app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`** (line 796-815)

```kotlin
private fun hydrateInitialStateFromCache(peerId: String) {
    ...
    val cachedMessages = repository.getCachedInitialPage(conversationId) ?: return
    mutableState.value = mutableState.value.copy(
        messages = orderedMessages,
        hasMoreLocal = ...,
        peerReadUpToServerSeq = ...
    )
    // ❌ 只加载了消息，没有设置 peerName / peerAvatarUrl
}
```

此方法在 `init` 块中同步调用，本来有机会从本地 SQLite 读昵称，但没有做。

### 3. 单聊 `refreshProfiles()` 必须等网络完成

**`app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`** (line 661-671)

```kotlin
// 单聊路径
if (peerId.isNotBlank()) {
    profileRepository.refreshProfiles(...)  // ❌ 先发网络请求
}
val peerProfile = profileRepository.localProfile(peerId)  // 网络返回后才读本地
mutableState.value = mutableState.value.copy(
    peerName = peerProfile?.nickname ?: peerId,  // 网络超时时回退到 ID
    ...
)
```

即使本地已有缓存，也必须等整个 HTTP 往返完成才能显示昵称。

### 4. `selectPeer()` 同样的问题

**`app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`** (line 288-313)

```kotlin
fun selectPeer(peerId: String) {
    val trimmedPeerId = peerId.trim()
    mutableState.value = mutableState.value.copy(
        peerName = trimmedPeerId,  // ❌ 直接设为 ID
        ...
    )
    scope.launch(dispatcher) {
        refreshProfiles()  // 异步网络回调后才更新昵称
    }
}
```

### 5. 会话列表没有这个问题（对比参考）

**`app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`** (line 332)

```kotlin
val profile = profileRepository.localProfile(resolvedPeerId)  // ✅ 同步读本地缓存
peerName = profile?.nickname ?: ...
```

会话列表用"本地优先"模式，所以不闪 ID。

## Root Cause

三个缺陷叠加导致此 bug：

| # | 缺陷 | 位置 |
|---|------|------|
| 1 | `ChatUiState.peerName` 默认值是 `peerId` | `ChatViewModel.kt:39` |
| 2 | `hydrateInitialStateFromCache()` 不读本地用户资料 | `ChatViewModel.kt:796-815` |
| 3 | 单聊 `refreshProfiles()` 无本地优先回退 | `ChatViewModel.kt:661-671` |
| 4 | `selectPeer()` 重置 `peerName = trimmedPeerId` | `ChatViewModel.kt:288-313` |

核心问题是：`ChatViewModel` 初始化路径中**从未同步读取过本地用户资料缓存**，而 `profileRepository.localProfile()` 是同步 SQLite 查询（<1ms），完全可以在 `init` 阶段安全调用。

## Non-Goals

- 不改变 `ChatUiState` 的默认值设计（`peerName = peerId` 作为安全回退是合理的）。
- 不在导航前做预加载（与消息 LRU 预加载不同，资料缓存已存在 SQLite 中，只需在 init 中读取）。
- 不修改 `ProfileRepository` 接口。
- 不影响群聊的资料加载逻辑。

## Implementation Plan

### Task 1: `hydrateInitialStateFromCache()` 增加本地资料查找

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`

**Goal:** 在 `init` 阶段同步读取本地用户资料缓存，使首帧即可显示昵称。

- [x] **Step 1a: 在 `hydrateInitialStateFromCache()` 中加入本地资料查找**

当前代码（line 796-815）：
```kotlin
private fun hydrateInitialStateFromCache(peerId: String) {
    if (peerId.isBlank()) { return }
    val conversationId = if (peerId.isGroupConversationId()) {
        peerId
    } else {
        repository.conversationIdFor(session.userId, peerId)
    }
    val cachedMessages = repository.getCachedInitialPage(conversationId) ?: return
    val orderedMessages = MessageOrderingPolicy.sortOldestFirst(cachedMessages)
    mutableState.value = mutableState.value.copy(
        messages = orderedMessages,
        hasMoreLocal = orderedMessages.size == HISTORY_PAGE_SIZE,
        isHistoryMemoryLimitReached = false,
        errorMessage = null,
        peerReadUpToServerSeq = repository.conversationPeerReadCursorByConversationId(conversationId)
    )
    recomputeGroupReadIndicator()
}
```

修改为：
```kotlin
private fun hydrateInitialStateFromCache(peerId: String) {
    if (peerId.isBlank()) { return }
    val conversationId = if (peerId.isGroupConversationId()) {
        peerId
    } else {
        repository.conversationIdFor(session.userId, peerId)
    }

    // 从本地缓存同步读取对方昵称和头像，避免首帧闪 ID
    val peerProfile = profileRepository.localProfile(peerId)
    val conversation = repository.conversation(conversationId)
    if (peerId.isGroupConversationId()) {
        mutableState.value = mutableState.value.copy(
            peerName = conversation?.title ?: conversation?.peerName ?: peerId,
            peerAvatarUrl = conversation?.avatarUrl
        )
    } else {
        mutableState.value = mutableState.value.copy(
            peerName = peerProfile?.nickname ?: peerId,
            peerAvatarUrl = peerProfile?.avatarUrl
        )
    }

    val cachedMessages = repository.getCachedInitialPage(conversationId) ?: return
    val orderedMessages = MessageOrderingPolicy.sortOldestFirst(cachedMessages)
    mutableState.value = mutableState.value.copy(
        messages = orderedMessages,
        hasMoreLocal = orderedMessages.size == HISTORY_PAGE_SIZE,
        isHistoryMemoryLimitReached = false,
        errorMessage = null,
        peerReadUpToServerSeq = repository.conversationPeerReadCursorByConversationId(conversationId)
    )
    recomputeGroupReadIndicator()
}
```

> `profileRepository.localProfile()` 是同步 SQLite 查询，耗时 <1ms，可在 `init` 块中安全调用。

---

### Task 2: 单聊 `refreshProfiles()` 本地优先，异步刷新兜底

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`

**Goal:** 单聊路径先同步读缓存立即显示，再异步拉取最新资料。

- [x] **Step 2a: 修改单聊分支为"本地优先"模式**

当前代码（line 661-671）：
```kotlin
if (peerId.isNotBlank()) {
    profileRepository.refreshProfiles(currentSession.accessToken, listOf(currentSession.userId, peerId))
}
val peerProfile = profileRepository.localProfile(peerId)
val currentUserProfile = profileRepository.localProfile(currentSession.userId)
mutableState.value = mutableState.value.copy(
    peerName = peerProfile?.nickname ?: peerId,
    peerAvatarUrl = peerProfile?.avatarUrl,
    currentUserAvatarUrl = currentUserProfile?.avatarUrl,
    mentionMembers = emptyList()
)
```

修改为：
```kotlin
if (peerId.isNotBlank()) {
    // 先用本地缓存立即显示昵称（与 ConversationListViewModel.toItem() 同模式）
    val localPeerProfile = profileRepository.localProfile(peerId)
    val localCurrentUserProfile = profileRepository.localProfile(currentSession.userId)
    mutableState.value = mutableState.value.copy(
        peerName = localPeerProfile?.nickname ?: peerId,
        peerAvatarUrl = localPeerProfile?.avatarUrl,
        currentUserAvatarUrl = localCurrentUserProfile?.avatarUrl,
        mentionMembers = emptyList()
    )
    // 再异步刷新最新资料（如果昵称变了会触发第二次更新）
    profileRepository.refreshProfiles(currentSession.accessToken, listOf(currentSession.userId, peerId))
    val peerProfile = profileRepository.localProfile(peerId)
    val currentUserProfile = profileRepository.localProfile(currentSession.userId)
    mutableState.value = mutableState.value.copy(
        peerName = peerProfile?.nickname ?: peerId,
        peerAvatarUrl = peerProfile?.avatarUrl,
        currentUserAvatarUrl = currentUserProfile?.avatarUrl,
        mentionMembers = emptyList()
    )
}
```

> 如果 Task 1 的 `init` 阶段已设好本地缓存值，此处第一次 `copy` 是冗余的但无害（值相同不会触发重组）。保留的好处是：即使 `hydrateInitialStateFromCache` 因 `peerId` 为空而跳过，`start()` 仍能先显示本地缓存值。

---

### Task 3: `selectPeer()` 同步设置本地昵称

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`

**Goal:** 切换对话时，从本地缓存获取昵称，避免闪 ID。

- [x] **Step 3a: `selectPeer` 中读本地缓存设置初始昵称**

当前代码（line 288-313）：
```kotlin
fun selectPeer(peerId: String) {
    val trimmedPeerId = peerId.trim()
    mutableState.value = mutableState.value.copy(
        peerId = trimmedPeerId,
        peerName = trimmedPeerId,    // ❌ 直接设为 ID
        peerAvatarUrl = null,         // ❌ 丢失缓存头像
        messages = emptyList(),
        ...
    )
    ...
}
```

修改为：
```kotlin
fun selectPeer(peerId: String) {
    val trimmedPeerId = peerId.trim()
    // 先从本地缓存获取昵称，避免闪 ID
    val peerProfile = profileRepository.localProfile(trimmedPeerId)
    mutableState.value = mutableState.value.copy(
        peerId = trimmedPeerId,
        peerName = peerProfile?.nickname ?: trimmedPeerId,
        peerAvatarUrl = peerProfile?.avatarUrl,
        messages = emptyList(),
        isLoadingMore = false,
        hasMoreLocal = true,
        isHistoryMemoryLimitReached = false,
        errorMessage = null,
        peerReadUpToServerSeq = null,
        latestOwnSentMessageId = null,
        groupReadCountForLatest = 0,
        groupReadersForLatest = emptyList()
    )
    scope.launch(dispatcher) {
        if (trimmedPeerId.isNotEmpty()) { openCurrentConversation() }
        startGroupReadObservation()
        refreshProfiles()
        refreshInitialPage()
        recomputeGroupReadIndicator()
        scheduleMissingThumbnailRetries(immediate = true)
    }
}
```

---

## Files to Modify

| File | Change |
|------|--------|
| `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` | 三处修改：`hydrateInitialStateFromCache()`、单聊 `refreshProfiles()`、`selectPeer()` |

> 只改一个文件，不涉及接口变更。

## Verification Plan

### Manual Verification Steps

1. **缓存命中验证**
   - [ ] 正常使用后，关闭聊天再重新打开同一用户 → 顶栏应立即显示昵称，不再闪 ID

2. **昵称变更验证**
   - [ ] 对方改了昵称后，重新进入聊天 → 应先显示旧昵称（本地缓存），网络返回后更新为新昵称

3. **无缓存冷启动验证**
   - [ ] 卸载重装后，打开一个从未聊天过的用户 → 应先显示 ID，网络返回后更新昵称（此场景符合预期）

4. **`selectPeer` 验证**
   - [ ] 在聊天内切换到另一个对话 → 顶栏应立即显示该用户昵称（如果本地有缓存）

5. **群聊不受影响**
   - [ ] 进入群聊 → 行为不变

### Automated Verification

- [x] 聊天相关单元测试通过：`.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.*`
- [x] `refreshProfiles()` 单聊路径先应用本地缓存，再异步刷新远端资料；与 `init` 阶段值相同时 state 内容保持一致

## Related Docs

- [`docs/bug/Chat-Initial-Blank-Screen-Optimization.md`](Chat-Initial-Blank-Screen-Optimization.md) — 消息 LRU 缓存优化（类似的首帧问题，不同层面）
- [`docs/feature-notes/user-profile-version-cache.md`](../feature-notes/user-profile-version-cache.md) — 用户资料版本缓存机制
