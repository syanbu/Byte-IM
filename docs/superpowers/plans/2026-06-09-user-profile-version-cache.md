# IM 客户端/服务端"用户资料缓存与 version 一致性"调研与改造方案

> 状态：调研完成 + 改造方案已确认  
> 日期：2026-06-09  
> 范围确认：**P0 + P1**（profileVersion 字段 + 缓存判断 + WebSocket 消息/群成员带 version）  
> 显式排除：P2 推送、remarkName

## Context

IM 客户端当前用 `user_profiles` SQLite 表缓存用户资料，但**没有单调递增的 `profileVersion` 字段**——只有 `updated_at`（毫秒时间戳）和 `avatar_updated_at`（仅头像粒度）。mock-server 端 `users` 表同样如此。

后果：
- 消息体、WebSocket 帧、会话列表、群成员接口都**不下发** sender 的 profileVersion；
- 服务端 `PUT /users/me` 更新资料时**没有**递增计数器（只更新 `updated_at`），也不向其他在线 socket 广播资料变更；
- 客户端刷新用户资料完全靠"打开页面时按需拉一次 `POST /users/batch`"，**没有 `remote > local` 的版本比较**——所以即便本地缓存落后，也不会主动失效；
- 唯一一处"新鲜度"判断在 `ContactListViewModel.changedProfileIds`（L135），比较的是 `friend_contacts.profileUpdatedAt > user_profiles.updatedAt`，但只覆盖联系人页。

目标：让 user/profile 表带 `profileVersion` 计数器，服务端递增，客户端在消息/会话/群成员/@ 等场景拿到 `remoteProfileVersion > localProfileVersion` 时**只**批量重拉过期的那批。

---

## 1. 当前实现现状

### 1.1 客户端本地表

| 表 | 路径 | 关键字段 | 是否有 profileVersion |
|---|---|---|---|
| `user_profiles` | `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt:99-114` | `user_id, phone, nickname, avatar_url, avatar_updated_at, updated_at, gender, signature` | ❌ 无 |
| `friend_contacts` | `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt:116-134` | `owner_user_id, friend_user_id, profile_updated_at, sort_order` | ❌ 无（只有 `profile_updated_at` 时间戳） |

数据类 `UserProfile`（`app/src/main/java/com/buyansong/im/storage/StorageModels.kt:59-68`）字段：`userId, phone, nickname, avatarUrl?, avatarUpdatedAt, updatedAt, gender?, signature?`，**无 `profileVersion`、无 `remarkName`**。

### 1.2 DAO / Repository / Flow

- `UserProfileDao` 全部为**同步**方法：`app/src/main/java/com/buyansong/im/storage/UserProfileDao.kt:3-13`，无 `Flow<...>` 暴露。
- `AndroidUserProfileDao.findByUserIds` 是 **N+1**（每 id 一次 `SQLiteDatabase.query`）：`app/src/main/java/com/buyansong/im/storage/AndroidUserProfileDao.kt:37-42`。
- `ProfileRepository`（`app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt`）：
  - `refreshProfile(token, userId)` **永远**打 `/users/{id}`，不查本地。
  - `refreshProfiles(token, ids)` 做 `distinct()` 去重后**一次性** `POST /users/batch`，然后 `upsertAll`。
  - **不做** `remoteVersion > localVersion` 判断。

### 1.3 协议/接口下发情况

| 通道 | 是否带 profileVersion |
|---|---|
| WebSocket `RECEIVE_MESSAGE` 消息体 | ❌ 不带 `senderNickname`/`senderAvatarUrl`/`senderProfileVersion`，只带 `senderId` |
| `POST /users/batch` 响应 | ❌ 不带 `profileVersion`，但带 `profileUpdatedAt` / `updatedAt` 毫秒时间戳 |
| `GET /groups/{id}/members` 响应 | ❌ 群成员对象只有 `userId/displayName(=userId)/role/joinedAt/updatedAt(群时间)` |
| `GET /conversations` | ❌ mock-server **没有这个端点** |
| `USER_PROFILE_UPDATED` 推送 | ❌ `ImCommand` 枚举里没有这个 cmd；`UserStore.updateProfile` 不广播 |

### 1.4 服务端 `users` 表

`mock-server/src/main/java/com/buyansong/imserver/auth/UserStore.java:222-262`：

```sql
CREATE TABLE users (
  phone VARCHAR(11) PRIMARY KEY,
  ...
  avatar_updated_at BIGINT NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL DEFAULT 0,
  ...
)
```

`UserStore.updateProfile` 在任意字段变化时把 `updated_at = nowMillis`（`UserStore.java:95-128`），头像变化额外 bump `avatar_updated_at`；**没有计数器**。

### 1.5 UI 资料来源（消息/会话/群成员/@）

| 场景 | 文件:行 | 资料来源 |
|---|---|---|
| 消息气泡 | `app/src/main/java/com/buyansong/im/chat/ChatDisplayPolicy.kt:36` `bubbleAvatar` | 单聊：`Conversation.peerName/peerAvatarUrl`；群聊：`UserProfile.nickname/avatarUrl`（来自 `state.senderProfiles`） |
| 会话列表 | `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt:316` `Conversation.toItem` | SINGLE：`UserProfileDao.localProfile(peerId)`；GROUP：`Conversation.avatarUrl/title` |
| 群成员 | `app/src/main/java/com/buyansong/im/group/GroupInfoScreen.kt:177` `GroupMemberCell` | 优先 `UserProfileDao`，fallback 服务端返回的 `displayName` |
| @提醒 | `app/src/main/java/com/buyansong/im/chat/ChatMentionPolicy.kt:96` `mentionsForMessage` | 同步读 `state.mentionMembers`（已在内存的 `GroupMember` 列表） |
| 联系人页 | `app/src/main/java/com/buyansong/im/contacts/ContactListViewModel.kt:135` `changedProfileIds` | **唯一一处** 比较 `contact.profileUpdatedAt > local.updatedAt` |

---

## 2. 已支持的部分

- ✅ 客户端有 `user_profiles` 缓存表和 `UserProfileDao`（同步接口 + 内存实现 + Android SQLite 实现）
- ✅ 客户端有 `ProfileRepository.refreshProfiles(token, ids)`：去重 + 一次 `POST /users/batch` + `upsertAll`
- ✅ 客户端有 6 处调用方（ChatVM/ConvListVM/GroupInfoVM/GroupCreateVM/ContactListVM 等），都走 batch 路径
- ✅ 客户端 `@` 提醒是**纯内存**读取，不打接口
- ✅ 服务端有 `PUT /users/me` 更新资料
- ✅ 服务端有 `POST /users/batch` 批量接口
- ✅ 服务端在 `users.updated_at` 上有时间戳粒度的版本信号

## 3. 缺失或有风险的部分

| # | 风险 | 严重度 |
|---|---|---|
| R1 | 客户端 `UserProfile` / `user_profiles` / DAO / Parser **完全无 `profileVersion` 字段** | 高 |
| R2 | 服务端 `users` 表 / `UserRecord` / `addProfileFields` JSON **完全无 `profileVersion` 字段** | 高 |
| R3 | 服务端更新资料**不递增计数器**（只有 `updated_at` 时间戳） | 高 |
| R4 | `POST /users/batch` 响应**不带** `profileVersion`，客户端无法做"是否过期"判断 | 高 |
| R5 | WebSocket `RECEIVE_MESSAGE` 消息体**不带** `senderProfileVersion` —— 群聊新来一条陌生人消息无法触发"过期重拉" | 中 |
| R6 | 群成员 `GET /groups/{id}/members` 响应**不带** `memberProfileVersion` | 中 |
| R7 | mock-server **没有 `USER_PROFILE_UPDATED` 推送事件**——好友改了昵称/头像，自己收不到通知 | 中 |
| R8 | `ProfileRepository.refreshProfiles` **不比较** `remote > local`，每次都覆盖本地（即便本地较新也会被旧值覆盖） | 高 |
| R9 | `AndroidUserProfileDao.findByUserIds` 是 **N+1**（每 id 一次 `query`） | 中（性能） |
| R10 | 6 个 ViewModel 各自调 `refreshProfiles`，**无跨屏去重/合并** —— 同时打开会话列表 + 群详情可能打两次 `/users/batch` | 中 |
| R11 | `friend_contacts` 表与 `user_profiles` 表靠 `userId` 关联，**无外键**约束；删除用户/退出登录时两边可能不同步 | 低 |
| R12 | `ProfileRepository.refreshProfile`（单点 `/users/{id}`）**永不打本地**——`ContactProfileViewModel` 每次进详情页都打接口 | 低 |
| R13 | mock-server `POST /users/batch` 内部是 `for (phone : phones) findByPhone(phone)` **N+1** | 中（性能） |
| R14 | 数据库迁移用 **DROP ALL**（`app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt:170-182`），新增 `profile_version` 列时如果不改 `onUpgrade`，开发期数据会丢 | 低（开发期） |

---

## 4. 已确认范围（用户决策后）

| 维度 | 决定 |
|---|---|
| 服务端 version 信号 | **新增 `profileVersion` 计数器**（PUT /users/me 任意字段变化时 +1；不回退） |
| 范围 | **P0 + P1** 全做（profileVersion 字段 + 缓存判断 + WebSocket 消息体带 senderProfileVersion + 群成员接口带 memberProfileVersion） |
| 显式排除 | **P2** 暂不做（USER_PROFILE_UPDATED 推送、`AndroidUserProfileDao` N+1、跨屏去重、mock-server `POST /users/batch` 内部 N+1） |
| `remarkName` | **本次不加**（仅做 profileVersion） |

---

## 5. 最终改造步骤

按依赖顺序，每个步骤独立可提交、可回归。

### 步骤 1 — 服务端：加 `profile_version` 列与 DTO
- `mock-server/src/main/java/com/buyansong/imserver/auth/UserStore.java:226-238` `CREATE TABLE` 新增 `profile_version INTEGER NOT NULL DEFAULT 0`
- `mock-server/src/main/java/com/buyansong/imserver/auth/UserStore.java:288-313` `ensureUserProfileColumns` 同步新增 `ALTER TABLE ADD COLUMN profile_version INTEGER NOT NULL DEFAULT 0`
- `mock-server/src/main/java/com/buyansong/imserver/auth/UserStore.java:95-128` `updateProfile` 内 `nextVersion = current.get().profileVersion() + 1`，UPDATE 写入
- `mock-server/src/main/java/com/buyansong/imserver/auth/UserRecord.java` 新增 `long profileVersion`，构造时默认 `0L`
- `mock-server/src/main/java/com/buyansong/imserver/auth/AuthService.java:245-272` `addProfileFields` 加 `data.addProperty("profileVersion", record.profileVersion())`

**验收**：sqlite3 查 `users.profile_version` 列存在；`PUT /users/me` 后再 `GET /users/me` 响应里 `profileVersion` 比之前大 1。

### 步骤 2 — 客户端：数据类 + 表 + 迁移 + DAO + Parser
- `app/src/main/java/com/buyansong/im/storage/StorageModels.kt:59-68` `UserProfile` 加 `val profileVersion: Long = 0L`
- `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt:99-114` `onCreate` 加 `profile_version INTEGER NOT NULL DEFAULT 0`
- `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt:170-182` **必须改** `onUpgrade` —— 当前是 DROP ALL，改为按 `oldVersion` 分支 `ALTER TABLE user_profiles ADD COLUMN profile_version INTEGER NOT NULL DEFAULT 0`
- `app/src/main/java/com/buyansong/im/storage/AndroidUserProfileDao.kt:44-71` `toValues()` / `toUserProfile()` 同步读写 `profile_version`
- `app/src/main/java/com/buyansong/im/profile/ProfileJsonParser.kt:42-54` 解析 `profileVersion`，缺省 `0L`

**验收**：app 启动后 `adb shell run-as com.buyansong.im sqlite3 databases/im.db "PRAGMA table_info(user_profiles)"` 能看到 `profile_version` 列；旧库升级路径走 `ALTER TABLE` 而非 `DROP`。

### 步骤 3 — 客户端：核心缓存判断
- `app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt:58-76` `refreshProfiles` 改为：先批量拉远端，**再用本地版本过滤**，仅 upsert `remote.profileVersion > local.profileVersion` 的项
- `app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt:44-55` `refreshProfile` 同样：先读本地 version，相同或更新则跳过接口

**验收**：在 `app/src/test/` 新增 `ProfileRepositoryTest`，4 个 case：
1) 本地空 → 全部 upsert
2) 本地 `profileVersion=5`，远端 `=5` → 不 upsert
3) 本地 `=3`，远端 `=5` → upsert
4) ids 含重复/空字符串 → 实际一次 `ProfileApi.batch` 调用

### 步骤 4 — 客户端：调用方适配"只拉缺失/过期"
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt:465-521` `refreshProfiles` 现状是无脑全拉，改为只把"本地缺失 或 本地 version 较旧"的 senderId 加入 batch 列表
- `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt:229-275` `refresh` 同样：先读本地 version，过滤后再 batch
- `app/src/main/java/com/buyansong/im/group/GroupInfoViewModel.kt:120-135` `backfillMembersRemote`：用 DAO 中已存 version 过滤
- `app/src/main/java/com/buyansong/im/group/GroupCreateViewModel.kt:139`、`app/src/main/java/com/buyansong/im/contacts/ContactListViewModel.kt:122-130` 同样过滤
- 注意：此时消息体 / 群成员响应还没有 version，所以"过期"判断**只在** DAO 内旧记录 vs 无记录之间；真正的"远端 vs 本地"判断要在步骤 5/6/7/8 完成后才能跑通

### 步骤 5 — 服务端：WebSocket 消息体带 `senderProfileVersion`
- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java:91-170` `handleSendMessage` 在拼 `RECEIVE_MESSAGE` 帧前查 `UserStore.findByPhone(senderId)` 拿 `profileVersion`，塞进 JSON：`data.addProperty("senderProfileVersion", sender.profileVersion())`
- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` 群消息分支同样塞
- 客户端 `app/src/main/java/com/buyansong/im/storage/StorageModels.kt` `ChatMessage` 加 `senderProfileVersion: Long? = null`
- 客户端 ChatMessage 解析处同步加字段（搜 `senderId`、`senderNickname` 等在 JSON parser 中的位置）

### 步骤 6 — 客户端：消息到来时按 `senderProfileVersion` 触发重拉
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt:465-521` `refreshProfiles`：
  ```kotlin
  val staleSenders = state.messages.mapNotNull { msg ->
      val local = profileRepository.localProfile(msg.senderId)?.profileVersion ?: 0L
      val remote = msg.senderProfileVersion ?: 0L
      if (remote > local) msg.senderId else null
  }.distinct()
  if (staleSenders.isNotEmpty()) profileRepository.refreshProfiles(token, staleSenders)
  ```
- 同样处理 "消息列表滚动加载历史消息" / "WebSocket 收到新消息 push" 两个入口

### 步骤 7 — 服务端：群成员接口带 `memberProfileVersion`
- `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java:82-108` `membersJson` join `users` 表，把每个 member 的 `nickname / avatarUrl / profileVersion` 一并返回
- 服务端 `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` 群消息分支可选：拼 `senderProfileVersion` 的同时把 `senderNickname / senderAvatarUrl` 也塞上（一步到位让"陌生人消息"也能展示）

### 步骤 8 — 客户端：群成员刷新时按 `memberProfileVersion` 触发重拉
- `app/src/main/java/com/buyansong/im/group/GroupInfoViewModel.kt:120-135` `backfillMembersRemote`：在拿到服务端返回的 `GroupMember` 列表后，对每个 `member.userId` 比较 `member.profileVersion > local.profileVersion`，把过期的并入 `refreshProfiles` 列表
- 客户端 `GroupMember` DTO 加 `profileVersion: Long = 0L`：`app/src/main/java/com/buyansong/im/storage/StorageModels.kt`
- 客户端 `GroupJsonParser`（如有）同步加解析

### 步骤 9 — 端到端验证
- 单元测试：`app/src/test/` 的 `ProfileRepositoryTest` 4 个 case 全过
- 集成：`mock-test/seed_local_messages.py` 灌数据，启动 mock-server + Android
- 手工：
  1. 用户 A 改昵称 → `GET /users/{A}` 看 `profileVersion` 增 1
  2. 客户端 B 发消息给 A → 收到 `RECEIVE_MESSAGE` 中 `senderProfileVersion=N` → 本地 `user_profiles.profile_version` 是 N-1 → 自动 batch 拉 A → 更新
  3. 打开群详情 → 服务端群成员响应每个都带 `profileVersion` → 客户端只 batch 那些"过期"的用户

### 显式不在本次范围
- ❌ `USER_PROFILE_UPDATED` 推送事件
- ❌ `AndroidUserProfileDao.findByUserIds` N+1 优化
- ❌ `ProfileRepository` 跨屏 in-flight coalescing
- ❌ mock-server `POST /users/batch` 内部 N+1 优化
- ❌ `remarkName` 字段、备注名 UI

---

## 6. 关键文件路径速查

### 服务端
- `mock-server/src/main/java/com/buyansong/imserver/auth/UserStore.java` — 表结构、updateProfile
- `mock-server/src/main/java/com/buyansong/imserver/auth/UserRecord.java` — DTO
- `mock-server/src/main/java/com/buyansong/imserver/auth/AuthService.java` — `addProfileFields` L245-272、`updateProfile` L151-190
- `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` — `handleSendMessage` L91-170
- `mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java` — `membersJson` L82-108
- `mock-server/src/main/java/com/buyansong/imserver/friend/FriendService.java` — `friendsJson` L34-52
- `mock-server/src/main/java/com/buyansong/imserver/protocol/ImCommand.java` — 命令码枚举

### 客户端
- `app/src/main/java/com/buyansong/im/storage/StorageModels.kt` — `UserProfile` / `ChatMessage` / `GroupMember` / `Conversation`
- `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt` — 建表 + 迁移
- `app/src/main/java/com/buyansong/im/storage/UserProfileDao.kt` — 接口
- `app/src/main/java/com/buyansong/im/storage/AndroidUserProfileDao.kt` — SQLite 实现
- `app/src/main/java/com/buyansong/im/profile/ProfileApi.kt` — 接口
- `app/src/main/java/com/buyansong/im/profile/OkHttpProfileApi.kt` — HTTP 实现
- `app/src/main/java/com/buyansong/im/profile/ProfileJsonParser.kt` — JSON 解析
- `app/src/main/java/com/buyansong/im/profile/ProfileRepository.kt` — `refreshProfiles` L58-76、`refreshProfile` L44
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` — `refreshProfiles` L465-521
- `app/src/main/java/com/buyansong/im/chat/ChatDisplayPolicy.kt` — `bubbleAvatar` L36
- `app/src/main/java/com/buyansong/im/chat/ChatMentionPolicy.kt` — @ 提醒
- `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt` — `toItem` L316、`refresh` L229-275
- `app/src/main/java/com/buyansong/im/group/GroupInfoViewModel.kt` — `backfillMembersRemote` L120-135
- `app/src/main/java/com/buyansong/im/contacts/ContactListViewModel.kt` — `changedProfileIds` L135、`refreshChangedProfiles` L109
