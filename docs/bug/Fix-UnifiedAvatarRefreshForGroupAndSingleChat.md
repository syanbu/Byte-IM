# Fix: 群聊/单聊头像刷新逻辑不统一

- Created on: 2026-06-11
- Branch: rename-redesign-ui
- Scope: `ChatViewModel.refreshProfiles()` 单聊/群聊双路径、`ChatDisplayPolicy.bubbleAvatar()` 分支、`ConversationListViewModel.refresh()` 缺版本感知
- Status: Pending

## Observed Symptoms

群聊和单聊的用户头像刷新行为不一致：

- **单聊**：每次进入聊天都会无条件发起网络请求拉取双方头像（即使本地已是最新） ❌
- **群聊**：按需拉取——仅在本地 profile 缺失或远程版本更新时才请求 ✅
- **会话列表**：已缓存的头像永远不会被刷新（`ensureProfiles` 未传 `remoteVersions`） ❌

## Root Cause Analysis

### 1. 单聊和群聊使用完全不同的 profile 拉取方法

`ChatViewModel.refreshProfiles()` 内部有两条独立路径：

| 维度 | 群聊 | 单聊 |
|------|------|------|
| 拉取方法 | `ensureProfiles()` 版本感知、按需请求 | `refreshProfiles()` 始终请求网络 |
| 使用 remoteVersions | ✅ 来自消息 + 群成员 | ❌ 不使用 |
| 头像来源 | `Conversation.avatarUrl`（会话表） | `UserProfile.avatarUrl`（用户表） |
| senderProfiles Map | 填充（每条消息按 senderId 查） | 始终为空 |
| mentionMembers | 从 profile 回填 | 始终为空 |

```kotlin
// ChatViewModel.kt:606-674

// 群聊路径：ensureProfiles + remoteVersions
profileRepository.ensureProfiles(
    accessToken = currentSession.accessToken,
    userIds = senderIds + memberIds + currentSession.userId,
    remoteVersions = messageVersions + memberVersions  // ← 版本感知
)

// 单聊路径：refreshProfiles（始终请求网络）
profileRepository.refreshProfiles(
    currentSession.accessToken,
    listOf(currentSession.userId, peerId)  // ← 无版本检查
)
```

### 2. ChatDisplayPolicy.bubbleAvatar() 需要群聊/单聊双分支

因为单聊不填充 `senderProfiles` Map，`bubbleAvatar()` 必须按会话类型走不同路径：

```kotlin
// ChatDisplayPolicy.kt:37-56

if (message.conversationType == ConversationType.GROUP) {
    // 群聊：从 senderProfile 取
    BubbleAvatar(displayName = senderProfile?.nickname, avatarUrl = senderProfile?.avatarUrl)
} else {
    // 单聊：从顶层 peerAvatarUrl 取
    BubbleAvatar(displayName = peerName, avatarUrl = peerAvatarUrl)
}
```

如果单聊也填充 `senderProfiles`，这条分支就可以统一。

### 3. 会话列表 ensureProfiles 不传 remoteVersions → 已缓存头像永不更新

```kotlin
// ConversationListViewModel.kt:252-259

profileRepository.ensureProfiles(
    accessToken = validSession.accessToken,
    userIds = conversations
        .filterNot { it.type == ConversationType.GROUP || ... }
        .map { it.peerIdForCurrentSession() }
        .plus(mentionedUserIdsFor(conversations))
        .plus(recalledUserIdsFor(conversations))
    // ← 缺少 remoteVersions 参数！
)
```

`ensureProfiles` 在无 `remoteVersions` 时退化为"只拉取本地不存在的 profile"（line 95），已缓存用户的头像永远不会被刷新。

### 4. 群成员头像回填逻辑重复

`ChatViewModel.refreshProfiles()` 和 `GroupInfoViewModel.backfillMembersFromLocal()` 实现了相同的"查 localProfile → 覆盖 displayName/avatarUrl"逻辑：

```kotlin
// ChatViewModel.kt:642-648
member.copy(
    displayName = profile?.nickname?.takeIf { it.isNotBlank() } ?: member.displayName,
    avatarUrl = profile?.avatarUrl ?: member.avatarUrl
)

// GroupInfoViewModel.kt:111-116 — 完全相同
member.copy(
    displayName = profile?.nickname?.takeIf { it.isNotBlank() } ?: member.displayName,
    avatarUrl = profile?.avatarUrl ?: member.avatarUrl
)
```

## Fix Plan

### Step 1: ChatViewModel.refreshProfiles() — 单聊路径改用 ensureProfiles

文件: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` (~line 662)

将单聊路径从 `refreshProfiles()` 改为 `ensureProfiles()` + `remoteVersions`，同时构建 `senderProfiles` Map：

```kotlin
// 当前：
if (peerId.isNotBlank()) {
    applyCachedPeerDisplay(peerId)
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

// 改为：
if (peerId.isNotBlank()) {
    applyCachedPeerDisplay(peerId)
    val senderIds = mutableState.value.messages
        .map { it.senderId }
        .filter { it.isNotBlank() }
        .distinct()
    val messageVersions = mutableState.value.messages
        .mapNotNull { message -> message.senderProfileVersion?.let { message.senderId to it } }
        .toMap()
    val profiles = if (currentSession.accessToken.isNotBlank()) {
        profileRepository.ensureProfiles(
            accessToken = currentSession.accessToken,
            userIds = senderIds + currentSession.userId + peerId,
            remoteVersions = messageVersions
        )
    } else {
        emptyList()
    }
    val profilesById = (profiles + listOf(peerId, currentSession.userId)
        .mapNotNull(profileRepository::localProfile))
        .distinctBy { it.userId }
        .associateBy { it.userId }
    val peerProfile = profilesById[peerId]
    val currentUserProfile = profilesById[currentSession.userId]
    mutableState.value = mutableState.value.copy(
        peerName = peerProfile?.nickname ?: peerId,
        peerAvatarUrl = peerProfile?.avatarUrl,
        currentUserAvatarUrl = currentUserProfile?.avatarUrl,
        senderProfiles = profilesById,
        mentionMembers = emptyList()
    )
}
```

关键变化：
- `refreshProfiles()` → `ensureProfiles()` with `remoteVersions` from messages
- 构建 `senderProfiles` Map（单聊也填充），统一后续头像渲染路径
- `senderIds` 中已包含 peerId（如果对方发过消息），额外加 peerId 确保即使对方没发消息也能获取其 profile

### Step 2: ChatDisplayPolicy.bubbleAvatar() — 统一头像取值路径

文件: `app/src/main/java/com/buyansong/im/chat/ChatDisplayPolicy.kt` (~line 37)

当单聊也填充了 `senderProfiles` Map 后，incoming 消息的头像统一从 `senderProfile` 取值：

```kotlin
// 当前：
if (message.conversationType == ConversationType.GROUP) {
    return BubbleAvatar(
        displayName = senderProfile?.nickname ?: message.senderId,
        avatarUrl = senderProfile?.avatarUrl
    )
}
return BubbleAvatar(displayName = peerName, avatarUrl = peerAvatarUrl)

// 改为：
return BubbleAvatar(
    displayName = senderProfile?.nickname ?: peerName,
    avatarUrl = senderProfile?.avatarUrl ?: peerAvatarUrl
)
```

保留 `peerAvatarUrl` / `peerName` 作为 fallback，覆盖 `senderProfiles` 尚未包含 peer profile 的场景（如首次加载网络请求未完成时）。

### Step 3: ConversationListViewModel.refresh() — 补充 remoteVersions

文件: `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt` (~line 252)

从已加载的会话数据中提取 peer 的 `profileVersion`，传给 `ensureProfiles`：

```kotlin
// 当前：
profileRepository.ensureProfiles(
    accessToken = validSession.accessToken,
    userIds = conversations
        .filterNot { it.type == ConversationType.GROUP || it.conversationId.startsWith("group:") }
        .map { it.peerIdForCurrentSession() }
        .plus(mentionedUserIdsFor(conversations))
        .plus(recalledUserIdsFor(conversations))
)

// 改为：
val singleChatPeerIds = conversations
    .filterNot { it.type == ConversationType.GROUP || it.conversationId.startsWith("group:") }
    .map { it.peerIdForCurrentSession() }
val allUserIds = singleChatPeerIds
    .plus(mentionedUserIdsFor(conversations))
    .plus(recalledUserIdsFor(conversations))
    .distinct()
val remoteVersions = singleChatPeerIds
    .mapNotNull { peerId ->
        profileRepository.localProfile(peerId)?.profileVersion?.let { peerId to it }
    }
    .toMap()
profileRepository.ensureProfiles(
    accessToken = validSession.accessToken,
    userIds = allUserIds,
    remoteVersions = remoteVersions
)
```

### Step 4: 提取公共的成员头像回填方法

文件: `app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt`

新增 `backfillFromProfiles` 方法，统一 `ChatViewModel` 和 `GroupInfoViewModel` 中重复的回填逻辑：

```kotlin
fun backfillFromProfiles(
    members: List<GroupMember>,
    profilesById: Map<String, UserProfile>
): List<GroupMember> {
    if (members.isEmpty()) return members
    return members.map { member ->
        val profile = profilesById[member.userId]
        member.copy(
            displayName = profile?.nickname?.takeIf { it.isNotBlank() } ?: member.displayName,
            avatarUrl = profile?.avatarUrl ?: member.avatarUrl
        )
    }
}
```

然后在 `ChatViewModel.refreshProfiles()` 和 `GroupInfoViewModel.backfillMembersFromLocal()` 中调用此方法替代内联逻辑。

## 涉及文件

| 文件 | 修改 |
|------|------|
| `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` | 单聊路径改用 `ensureProfiles`，构建 `senderProfiles` |
| `app/src/main/java/com/buyansong/im/chat/ChatDisplayPolicy.kt` | 统一 incoming 消息头像取值，去掉群聊/单聊分支 |
| `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt` | `ensureProfiles` 调用补充 `remoteVersions` |
| `app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt` | 新增 `backfillFromProfiles` 公共方法 |
| `app/src/main/java/com/buyansong/im/group/GroupInfoViewModel.kt` | `backfillMembersFromLocal` 改用 `ProfileRepository.backfillFromProfiles` |

## Verification

1. 进入已有本地缓存的单聊 → 确认不再每次都发起网络请求（`ensureProfiles` 跳过版本一致的）
2. 修改对方头像后重新进入单聊 → 确认头像能更新
3. 群聊行为与修改前完全一致
4. 会话列表下拉刷新 → 已缓存的用户头像也能被更新（之前不会）
5. 运行 `ProfileRepositoryTest`、`ChatViewModelTest`、`ChatDisplayPolicyTest`

## Code Evidence

- [ChatViewModel.kt:606-674](app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt#L606-L674) — `refreshProfiles()` 双路径
- [ChatDisplayPolicy.kt:37-56](app/src/main/java/com/buyansong/im/chat/ChatDisplayPolicy.kt#L37-L56) — `bubbleAvatar()` 群聊/单聊分支
- [ConversationListViewModel.kt:252-259](app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt#L252-L259) — `ensureProfiles` 缺 `remoteVersions`
- [ProfileRepository.kt:77-112](app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt#L77-L112) — `ensureProfiles` 无版本时退化为只拉缺失
- [ProfileRepository.kt:58-75](app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt#L58-L75) — `refreshProfiles` 始终请求网络
- [GroupInfoViewModel.kt:105-118](app/src/main/java/com/buyansong/im/group/GroupInfoViewModel.kt#L105-L118) — 重复的回填逻辑
