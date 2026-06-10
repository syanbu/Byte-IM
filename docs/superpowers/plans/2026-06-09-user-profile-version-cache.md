# 用户资料缓存：profileVersion + 统一刷新接口 + 消除 N+1

> 状态：方案已确认
> 日期：2026-06-10
> 核心诉求：抽象一个统一刷新接口，让单聊/群聊/消息列表/联系人/联系人详情等场景走同一套逻辑，只拉 version 变了的数据，避免 N+1

---

## 0. 设计要点

### 0.1 profileVersion 计数器

服务端 `users` 表新增 `profile_version INTEGER NOT NULL DEFAULT 0`。`PUT /users/me` 任意字段变化时 `profile_version += 1`。不回退。

### 0.2 统一刷新接口

所有调用方统一走 `ProfileRepository.ensureProfiles()`：

```kotlin
suspend fun ensureProfiles(
    accessToken: String,
    userIds: List<String>,
    remoteVersions: Map<String, Long> = emptyMap()  // userId → 远端 profileVersion
): List<UserProfile>
```

- **有 version hints**（消息体带了 `senderProfileVersion`、群成员带了 `memberProfileVersion`）：只拉 `remote > local` 的用户
- **无 version hints**（会话列表、联系人等）：拉本地缺失的用户，batch 返回后按 version 判断是否 upsert
- **永远不覆盖** `remote.profileVersion <= local.profileVersion` 的记录

### 0.3 消除 N+1

| 位置 | 现状 | 改法 |
|---|---|---|
| 客户端 `AndroidUserProfileDao.findByUserIds` | 每个 userId 一次 `query` | `WHERE user_id IN (?, ?, …)` 单次查询 |
| 服务端 `UserStore.findByPhones` | 每个手机号一次 `findByPhone` | `WHERE phone IN (?, ?, …)` 单次查询 |

### 0.4 DATABASE_VERSION

demo 阶段，`onUpgrade` 不做增量迁移。只需将 `DATABASE_VERSION` 从 10 → 11，触发 `onCreate` 重建所有表即可。

---

## 1. 改造步骤

### 步骤 1 — 服务端：profile_version 列 + 计数器 + 全量暴露

**1a. UserRecord 加字段**

`UserRecord.java`（record 声明）新增 `long profileVersion`，紧凑构造函数加默认 `0L`：

```java
public record UserRecord(
    String phone, String salt, String passwordHash,
    String nickname, String avatarUrl, String avatarObjectKey,
    long avatarUpdatedAt, long updatedAt, long createdAt,
    String gender, String signature,
    long profileVersion                                    // ← 新增
) {
    public UserRecord(String phone, String salt, String passwordHash, long createdAt) {
        this(phone, salt, passwordHash, phone, null, null, 0L, createdAt, createdAt, null, null, 0L);
    }
}
```

**1b. UserStore — 建表 + 所有 SQL**

| 方法 | 改动 |
|---|---|
| `initialize()` CREATE TABLE | 加 `profile_version INTEGER NOT NULL DEFAULT 0` |
| `ensureUserProfileColumns()` | 加 `ALTER TABLE users ADD COLUMN profile_version INTEGER NOT NULL DEFAULT 0` |
| `insert()` | INSERT 列表加 `profile_version`，`statement.setLong(12, record.profileVersion())` |
| `findByPhone()` | SELECT 列表加 `profile_version` |
| `readUser()` | 构造 `UserRecord` 时传入 `resultSet.getLong("profile_version")` |
| `updateProfile()` | ① 先读当前 `profileVersion`；② `nextVersion = current + 1`；③ UPDATE SET 加 `profile_version = ?` |

`updateProfile` 改动示例：

```java
public synchronized Optional<UserRecord> updateProfile(...) {
    Optional<UserRecord> current = findByPhone(phone);
    if (current.isEmpty()) return Optional.empty();
    long nextVersion = current.get().profileVersion() + 1;   // ← 递增
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(
             """
             UPDATE users SET nickname=?, avatar_url=?, avatar_object_key=?,
                 avatar_updated_at=?, updated_at=?, gender=?, signature=?,
                 profile_version=?
             WHERE phone=?
             """
         )) {
        // ... 原有 setXxx ...
        statement.setLong(8, nextVersion);   // ← profile_version
        statement.setString(9, phone);
        statement.executeUpdate();
        return findByPhone(phone);
    } ...
}
```

**1c. AuthService — addProfileFields 暴露**

```java
private void addProfileFields(JsonObject data, UserRecord record) {
    // ... 原有字段 ...
    data.addProperty("profileVersion", record.profileVersion());  // ← 新增
}
```

**1d. AuthService — 所有 new UserRecord 调用点补 profileVersion**

| 位置 | 改动 |
|---|---|
| `register()` L57-69 | 构造加 `0L` |
| `success()` fallback L193-205 | 构造加 `0L` |

**1e. UserStore.findByPhones — 消除 N+1**

```java
public synchronized List<UserRecord> findByPhones(List<String> phones) {
    if (phones.isEmpty()) return List.of();
    String placeholders = String.join(",", phones.stream().map(_ -> "?").toList());
    try (Connection connection = connect();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT phone, salt, password_hash, nickname, avatar_url, avatar_object_key, " +
             "avatar_updated_at, updated_at, created_at, gender, signature, profile_version " +
             "FROM users WHERE phone IN (" + placeholders + ")"
         )) {
        for (int i = 0; i < phones.size(); i++) statement.setString(i + 1, phones.get(i));
        try (ResultSet rs = statement.executeQuery()) {
            List<UserRecord> records = new ArrayList<>();
            while (rs.next()) records.add(readUser(rs));
            return records;
        }
    } catch (SQLException error) {
        throw new IllegalStateException("Unable to find users by phones", error);
    }
}
```

> 注意：`readUser()` 需要在步骤 1b 中已加 `profile_version` 列的读取。

**验收**：`PUT /users/me` 改昵称后 `GET /users/me` 响应中 `profileVersion` 比之前大 1。

---

### 步骤 2 — 客户端：数据层 + N+1 修复

**2a. UserProfile 加字段**

`StorageModels.kt`:

```kotlin
data class UserProfile(
    val userId: String,
    val phone: String,
    val nickname: String,
    val avatarUrl: String?,
    val avatarUpdatedAt: Long,
    val updatedAt: Long,
    val gender: Gender? = null,
    val signature: String? = null,
    val profileVersion: Long = 0L                               // ← 新增
)
```

**2b. ImDatabaseHelper — 建表 + 版本号**

- `createUserProfilesTable()` 加 `profile_version INTEGER NOT NULL DEFAULT 0`
- `DATABASE_VERSION` 从 `10` → `11`（demo 阶段 `onUpgrade` 无需改动，DROP ALL + `onCreate` 重建即可）

**2c. AndroidUserProfileDao — 读写 profile_version + 消除 N+1**

`toValues()`:

```kotlin
private fun UserProfile.toValues(): ContentValues = ContentValues().apply {
    // ... 原有字段 ...
    put("profile_version", profileVersion)                      // ← 新增
}
```

`toUserProfile()`:

```kotlin
private fun Cursor.toUserProfile(): UserProfile = UserProfile(
    // ... 原有字段 ...
    profileVersion = getLong(getColumnIndexOrThrow("profile_version"))  // ← 新增
)
```

`findByUserIds()` — 消除 N+1：

```kotlin
override fun findByUserIds(userIds: List<String>): List<UserProfile> {
    if (userIds.isEmpty()) return emptyList()
    val placeholders = userIds.indices.joinToString(",") { "?" }
    return database.query(
        "user_profiles", null,
        "user_id IN ($placeholders)", userIds.toTypedArray(),
        null, null, null
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toUserProfile()) }
    }
}
```

**2d. ProfileJsonParser — 解析 profileVersion**

```kotlin
private fun JsonObject.toUserProfile(): UserProfile = UserProfile(
    // ... 原有字段 ...
    profileVersion = optionalLong("profileVersion") ?: 0L       // ← 新增
)
```

**验收**：app 启动后 `PRAGMA table_info(user_profiles)` 能看到 `profile_version` 列；`POST /users/batch` 返回的 profileVersion 被正确写入本地。

---

### 步骤 3 — 客户端：统一刷新接口 `ensureProfiles`

在 `ProfileRepository` 中新增核心方法，替换现有 `refreshProfiles` / `refreshProfile`：

```kotlin
/**
 * 确保指定用户的资料在本地可用且最新。
 *
 * @param accessToken    访问令牌
 * @param userIds        需要确保的用户 ID 列表
 * @param remoteVersions 远端版本映射（userId → profileVersion），用于增量判断。
 *                       为空时只拉取本地缺失的用户资料。
 * @return 所有请求用户的本地资料
 */
suspend fun ensureProfiles(
    accessToken: String,
    userIds: List<String>,
    remoteVersions: Map<String, Long> = emptyMap()
): List<UserProfile> {
    val distinctIds = userIds.distinct().filter { it.isNotBlank() }
    if (distinctIds.isEmpty()) return emptyList()

    // 1. 批量读本地版本（单次 SQL，无 N+1）
    val localProfiles = userProfileDao.findByUserIds(distinctIds)
    val localVersionMap = localProfiles.associate { it.userId to it.profileVersion }

    // 2. 确定需要拉取的 ID
    val idsToFetch = if (remoteVersions.isNotEmpty()) {
        // 有远端 version hints：只拉 remote > local 或本地无记录的
        distinctIds.filter { id ->
            val remote = remoteVersions[id] ?: 0L
            val local = localVersionMap[id] ?: 0L
            remote > local                              // 本地缺失时 local=0，remote≥1 → 必拉
        }
    } else {
        // 无远端 hints：只拉本地缺失的
        distinctIds.filter { it !in localVersionMap }
    }

    if (idsToFetch.isEmpty()) return localProfiles

    // 3. batch 拉取
    val remoteProfiles = when (val result = profileApi.batch(accessToken, idsToFetch)) {
        is ProfileBatchResult.Success -> result.profiles
        is ProfileBatchResult.Failure -> emptyList()
    }

    // 4. 只 upsert version 更新的记录
    val toUpsert = remoteProfiles.filter { remote ->
        val localVersion = localVersionMap[remote.userId] ?: 0L
        remote.profileVersion > localVersion
    }
    if (toUpsert.isNotEmpty()) userProfileDao.upsertAll(toUpsert)

    // 5. 返回所有请求用户的最新本地资料
    return userProfileDao.findByUserIds(distinctIds)
}
```

**保留的兼容方法**（内部转发到 `ensureProfiles`）：

```kotlin
suspend fun refreshProfiles(accessToken: String, userIds: List<String>): List<UserProfile> =
    ensureProfiles(accessToken, userIds)

suspend fun refreshProfile(accessToken: String, userId: String): UserProfile? {
    val trimmed = userId.trim()
    if (trimmed.isEmpty()) return null
    return ensureProfiles(accessToken, listOf(trimmed)).firstOrNull()
}
```

**验收**：`ProfileRepositoryTest` 覆盖 4 个 case：
1. 本地无记录 → batch 拉取 + 全部 upsert
2. 本地 version=5，远端 version=5 → 不拉取不 upsert
3. 本地 version=3，远端 version=5 → 只拉取并 upsert 该用户
4. ids 含重复/空字符串 → 只调一次 batch

---

### 步骤 4 — 客户端：调用方迁移

所有调用方改为使用 `ensureProfiles`，根据场景决定是否传 `remoteVersions`。

#### 4a. ChatViewModel.refreshProfiles()

当前 L601+：无脑全拉 senderIds + memberIds。

改为：

```kotlin
private suspend fun refreshProfiles() {
    val currentSession = validSessionProvider() ?: session
    profileRepository.bootstrapSession(currentSession)
    val peerId = mutableState.value.peerId
    if (peerId.isGroupConversationId()) {
        val conversation = repository.conversation(peerId)
        val groupId = peerId.removePrefix("group:")
        val groupRepo = groupRepository
        val rawMentionMembers = if (groupRepo != null && currentSession.accessToken.isNotBlank()) {
            groupRepo.syncMembers(currentSession.accessToken, groupId)
        } else {
            groupRepo?.localMembers(groupId).orEmpty()
        }
        val senderIds = mutableState.value.messages
            .map { it.senderId }.filter { it.isNotBlank() }.distinct()
        val memberIds = rawMentionMembers.map { it.userId }
        val allIds = (senderIds + memberIds + currentSession.userId).distinct()

        // 从消息体提取 senderProfileVersion
        val remoteVersions = mutableState.value.messages
            .filter { it.senderProfileVersion != null }
            .associate { it.senderId to it.senderProfileVersion!! }

        // 从群成员提取 memberProfileVersion（步骤 5 完成后才有值）
        val memberVersions = rawMentionMembers
            .filter { it.profileVersion > 0 }
            .associate { it.userId to it.profileVersion }

        val allRemoteVersions = remoteVersions + memberVersions

        val remoteProfiles = if (currentSession.accessToken.isNotBlank()) {
            profileRepository.ensureProfiles(currentSession.accessToken, allIds, allRemoteVersions)
        } else emptyList()

        // ... 后续组装 senderProfiles / mentionMembers 逻辑不变 ...
    } else {
        // 单聊逻辑不变（只需 peer 的 profile）
        ...
    }
}
```

#### 4b. ConversationListViewModel

当前：全拉 peerIds。改为 `ensureProfiles(accessToken, peerIds)` — 无 version hints，只拉本地缺失的。

#### 4c. GroupInfoViewModel.backfillMembersRemote()

改为 `ensureProfiles(accessToken, memberIds, memberVersionMap)` — 群成员带 version hints（步骤 5 完成后）。

#### 4d. GroupCreateViewModel

改为 `ensureProfiles(accessToken, selectedIds)` — 无 version hints。

#### 4e. ContactListViewModel.refreshChangedProfiles()

改为 `ensureProfiles(accessToken, changedIds)` — 原来的 `profileUpdatedAt > local.updatedAt` 比较逻辑由 `ensureProfiles` 的 version 比较替代。

#### 4f. ContactProfileViewModel

改为调用 `ensureProfiles(accessToken, listOf(userId))`，替代原来的 `refreshProfile`。

**验收**：各页面打开时网络请求量减少——重复进入同一会话不再发 batch 请求。

---

### 步骤 5 — 服务端：消息体 + 群成员带 profileVersion

**5a. WebSocket RECEIVE_MESSAGE 带 senderProfileVersion**

`MessageRouter.handleSendMessage()` / `handleGroupSendMessage()`：

需要注入 `UserStore` 依赖。修改 `MessageRouter` 主构造函数加 `UserStore userStore`，级联构造函数加 `new UserStore(...)` 或从调用方传入。

在拼 `RECEIVE_MESSAGE` 帧时：

```java
// 单聊 & 群聊通用
Optional<UserRecord> sender = userStore.findByPhone(senderUserId);
sender.ifPresent(s -> message.addProperty("senderProfileVersion", s.profileVersion()));
```

**5b. 群成员接口带 memberProfileVersion + nickname + avatarUrl**

`GroupService.membersJson()` 当前只返回 `userId/displayName(=userId)/role/joinedAt/updatedAt`。

需要注入 `UserStore` 依赖，在构建 member JSON 时查 `userStore.findByPhones(memberIds)` 一次（已修复 N+1，批量查）：

```java
public String membersJson(String groupId, String requesterId) {
    GroupRecord group = groupStore.findById(groupId).orElse(null);
    if (group == null || !group.memberUserIds().contains(requesterId)) { ... }
    // 批量查用户资料（单次 SQL，无 N+1）
    Map<String, UserRecord> userByPhone = new HashMap<>();
    for (UserRecord u : userStore.findByPhones(group.memberUserIds())) {
        userByPhone.put(u.phone(), u);
    }
    JsonArray members = new JsonArray();
    for (String memberUserId : group.memberUserIds()) {
        JsonObject member = new JsonObject();
        member.addProperty("groupId", group.groupId());
        member.addProperty("userId", memberUserId);
        UserRecord user = userByPhone.get(memberUserId);
        if (user != null) {
            member.addProperty("displayName", user.nickname());
            member.addProperty("avatarUrl", user.avatarUrl());
            member.addProperty("profileVersion", user.profileVersion());
        } else {
            member.addProperty("displayName", memberUserId);
        }
        member.addProperty("role", memberUserId.equals(group.ownerId()) ? "OWNER" : "MEMBER");
        member.addProperty("joinedAt", group.createdAt());
        member.addProperty("updatedAt", group.updatedAt());
        members.add(member);
    }
    // ...
}
```

**验收**：WebSocket 收到消息包含 `senderProfileVersion`；`GET /groups/{id}/members` 每个成员包含 `profileVersion` / `nickname` / `avatarUrl`。

---

### 步骤 6 — 客户端：解析 version hints

**6a. ChatMessage 加 senderProfileVersion**

`StorageModels.kt`:

```kotlin
data class ChatMessage(
    // ... 原有字段 ...
    val senderProfileVersion: Long? = null                      // ← 新增
)
```

在消息 JSON 解析处（`MessagePacketProcessor` 或 `MessageRepository` 中解析 WebSocket 帧的位置）加：

```kotlin
senderProfileVersion = jsonObject.optionalLong("senderProfileVersion")
```

**6b. GroupMember 加 profileVersion**

`StorageModels.kt`:

```kotlin
data class GroupMember(
    // ... 原有字段 ...
    val profileVersion: Long = 0L                               // ← 新增
)
```

`group_members` 表加 `profile_version INTEGER NOT NULL DEFAULT 0`（随 `DATABASE_VERSION` 递增重建）。

`AndroidGroupDao.toGroupMember()` / `GroupMember.toValues()` 同步加读写。

`GroupJsonParser.toGroupMember()` 加解析：

```kotlin
profileVersion = optionalLong("profileVersion") ?: 0L
```

**验收**：收到带 `senderProfileVersion` 的消息后，`ChatMessage.senderProfileVersion` 非空；打开群详情后，`GroupMember.profileVersion` 反映服务端值。

---

### 步骤 7 — 端到端验证

1. 用户 A 改昵称 → `GET /users/A` 的 `profileVersion` 增 1
2. 客户端 B 打开与 A 的单聊 → 收到消息带 `senderProfileVersion=N` → 本地 version=N-1 → 自动 batch 拉取 A → 更新
3. 客户端 B 再次打开同一会话 → `ensureProfiles` 判断 `remote=N == local=N` → 不发请求
4. 打开群详情 → 群成员响应每人带 `profileVersion` → 只 batch 那些"过期"的用户
5. 用户 C 从未在本地缓存 → `ensureProfiles` 发现本地缺失 → 拉取并缓存

---

## 2. 不在本次范围

- ❌ `USER_PROFILE_UPDATED` WebSocket 推送事件
- ❌ `ProfileRepository` 跨屏 in-flight coalescing（多次调用 `ensureProfiles` 合并为一次 batch）
- ❌ `remarkName` 字段、备注名 UI

---

## 3. 关键文件路径

### 服务端
- `mock-server/.../auth/UserStore.java` — 表结构、updateProfile、findByPhones
- `mock-server/.../auth/UserRecord.java` — DTO
- `mock-server/.../auth/AuthService.java` — addProfileFields、所有 UserRecord 构造点
- `mock-server/.../session/MessageRouter.java` — handleSendMessage（需注入 UserStore）
- `mock-server/.../group/GroupService.java` — membersJson（需注入 UserStore）

### 客户端
- `app/.../storage/StorageModels.kt` — UserProfile / ChatMessage / GroupMember
- `app/.../storage/ImDatabaseHelper.kt` — 建表 + DATABASE_VERSION
- `app/.../storage/AndroidUserProfileDao.kt` — findByUserIds N+1 修复
- `app/.../storage/AndroidGroupDao.kt` — GroupMember 读写
- `app/.../profile/ProfileRepository.kt` — ensureProfiles 核心逻辑
- `app/.../profile/ProfileJsonParser.kt` — 解析 profileVersion
- `app/.../chat/ChatViewModel.kt` — refreshProfiles 改用 ensureProfiles + version hints
- `app/.../group/GroupApi.kt` — GroupJsonParser 解析 memberProfileVersion
