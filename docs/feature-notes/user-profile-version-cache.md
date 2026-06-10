# 用户资料版本缓存机制说明

## 当前实现状态（2026-06-10）

当前项目已经实现用户资料的版本化缓存机制。该机制用于解决 IM 场景中头像、昵称、性别、签名等资料在多页面重复展示时的同步问题。

它的核心目标是：

- 用户资料变化后，其他用户可以在聊天、通讯录、群成员、资料页等入口看到新资料。
- 高频页面不重复拉取所有用户资料，只拉本地缺失或远端版本更新的数据。
- 本地缓存不会被旧的远端响应覆盖。
- 通讯录冷启动时可以展示明确的加载态，但资料刷新本身不控制 UI。

本文说明当前项目中的用户资料版本缓存机制，重点解释：

- `profileVersion` 是什么
- 客户端如何判断资料是否需要刷新
- `ensureProfiles` 和 `refreshProfiles` 的区别
- 单聊、群聊、通讯录、资料页分别如何使用该机制
- 为什么需要区分“没传字段”和“清空字段”

这份说明基于当前项目实际代码，而不是抽象方案。

## 1. 为什么需要用户资料版本缓存

在 IM 里，用户资料会出现在很多地方：

- 消息首页会话列表
- 单聊标题和头像
- 群聊消息发送者头像和昵称
- 群成员列表
- 通讯录列表
- 用户资料页
- 当前用户的 Me 页面

如果每个页面都自己直接请求完整资料，会带来几个问题：

- 页面之间逻辑重复，容易出现一个页面刷新了、另一个页面没刷新的情况。
- 通讯录或群成员数量多时，容易产生大量重复请求。
- 如果旧响应晚于新响应写入本地，可能把新资料覆盖成旧资料。

所以当前实现把用户资料作为账号级本地缓存处理，并用 `profileVersion` 判断资料是否过期。

## 2. profileVersion 的含义

服务端 `users` 表中维护 `profile_version` 字段。

```sql
profile_version INTEGER NOT NULL DEFAULT 0
```

当用户通过 `PUT /users/me` 修改资料时，服务端会让该用户的 `profile_version += 1`。该值只递增，不回退。

服务端会在资料响应中返回：

```json
{
  "userId": "15000000001",
  "nickname": "buyansong",
  "avatarUrl": "https://...",
  "profileVersion": 12
}
```

客户端本地 `user_profiles` 表也保存同名版本字段：

```sql
profile_version INTEGER NOT NULL DEFAULT 0
```

判断规则很简单：

```text
远端 profileVersion > 本地 profileVersion：资料已变化，需要拉取并写入本地
远端 profileVersion <= 本地 profileVersion：本地资料不落后，不覆盖
本地没有该用户资料：必须拉取
```

## 3. 本地缓存写入规则

客户端不会无条件覆盖本地资料。写入前会比较版本：

```kotlin
local == null || remote.profileVersion > local.profileVersion
```

这样可以避免下面这种问题：

```text
1. 用户 B 已经更新到 version=12
2. A 本地已有 B 的 version=12
3. 某个旧请求返回了 B 的 version=11
4. 客户端拒绝用 version=11 覆盖本地 version=12
```

这个规则适用于 batch 拉取、单个资料刷新、当前用户资料刷新等写入路径。

## 4. ensureProfiles 和 refreshProfiles 的区别

当前实现里，`ProfileRepository` 有两类语义不同的接口。

### ensureProfiles

`ensureProfiles` 用于高频列表场景。它的语义是“确保本地有资料，并且在有远端版本提示时不落后”。

```kotlin
suspend fun ensureProfiles(
    accessToken: String,
    userIds: List<String>,
    remoteVersions: Map<String, Long> = emptyMap()
): List<UserProfile>
```

它的行为是：

- 有 `remoteVersions` 时，只拉本地缺失或 `remoteVersion > local.profileVersion` 的用户。
- 没有 `remoteVersions` 时，只拉本地缺失的用户。
- 拉到远端资料后，仍然按 `profileVersion` 决定是否 upsert。

适用场景：

- 会话列表补齐头像昵称
- 群聊根据消息里的 `senderProfileVersion` 补齐发送者资料
- 群成员根据 `profileVersion` 补齐成员资料
- 通讯录列表根据好友资料变更提示补齐资料

### refreshProfile / refreshProfiles

`refreshProfile` 和 `refreshProfiles` 用于用户主动查看的场景。它们的语义是“即使本地已有缓存，也请求远端”。

适用场景：

- 用户点开好友资料页
- 用户进入单聊，需要刷新对方头像昵称
- Me 页面刷新当前用户资料

这里不能简单转发到 `ensureProfiles`。否则本地已有缓存时不会请求远端，用户 A 就可能看不到用户 B 刚修改的昵称或头像。

## 5. 各页面如何判断资料是否变化

### 通讯录

通讯录先请求好友列表，再根据好友列表里的资料变更提示判断哪些联系人可能变化。

当前实现中，好友列表使用 `profileUpdatedAt` 做提示：

```text
contact.profileUpdatedAt > local.updatedAt
```

更理想的长期方案是让 `/friends/me` 直接返回每个好友的 `profileVersion`：

```json
{
  "userId": "15000000001",
  "profileVersion": 12
}
```

客户端就可以用：

```text
friend.profileVersion > local.profileVersion
```

这样通讯录只需要一次轻量请求，就能知道哪些好友资料变化了，再 batch 拉取变化的用户资料。

#### `/friends/me` 的 hint 必须来自用户资料表

通讯录依赖 `/friends/me` 返回的资料变更提示来决定是否刷新好友资料。这个提示必须来自真实的用户资料更新时间，当前 mock-server 对应的是 `users.updated_at`。

如果服务端返回的 `profileUpdatedAt` 恒为 `0`，客户端在本地已经有好友资料缓存时会判断“远端没有更新”，从而跳过 batch 刷新。此时就会出现一个容易误判的问题：单聊和资料页已经能看到新昵称/新头像，但通讯录仍停留在旧缓存。

在 mock-server 中，`FriendService(FriendStore, LongSupplier)` 两参数构造函数会使用 `userId -> 0L` 作为默认资料更新时间提供者。因此 `MockImServer` 主路径必须使用三参数构造函数，并传入：

```java
userStore::profileUpdatedAtByPhone
```

两参数构造函数只适合测试或不需要资料变更 hint 的场景，不能用于真实 `/friends/me` 链路。

### 单聊

单聊没有稳定的远端 version hint。用户进入单聊属于主动查看，因此当前实现会强制刷新当前用户和对方用户的资料。

```text
进入 A 和 B 的单聊
-> refreshProfiles(A, B)
-> 请求远端
-> 按 profileVersion 决定是否写入本地
```

这样即使 B 改资料后没有给 A 发送新消息，A 再次进入单聊也能看到新资料。

### 群聊

群聊可以从两个地方获得 version hint：

- 消息体里的 `senderProfileVersion`
- 群成员接口返回的成员 `profileVersion`

客户端会把这些版本提示合并，然后调用 `ensureProfiles`：

```text
本地没有资料：拉取
远端版本大于本地版本：拉取
远端版本不大于本地版本：不拉取
```

这样群聊不会因为每次打开都重复拉取全部成员资料。

### 用户资料页

资料页是用户主动查看某个人，所以走强制刷新。

```text
打开 B 的资料页
-> refreshProfile(B)
-> 请求远端
-> 远端版本更新时写入本地
```

这和通讯录列表不同。通讯录列表追求批量和省请求，资料页追求用户看到最新信息。

## 6. 服务端提供哪些 version hint

当前服务端已经在多个响应里暴露资料版本：

| 场景 | 字段 | 用途 |
|---|---|---|
| `GET /users/me` | `profileVersion` | 当前用户资料版本 |
| `GET /users/{id}` | `profileVersion` | 单个用户资料版本 |
| `POST /users/batch` | `profileVersion` | 批量用户资料版本 |
| WebSocket 收消息 | `senderProfileVersion` | 判断消息发送者资料是否过期 |
| 群成员接口 | `profileVersion` | 判断群成员资料是否过期 |
| `GET /friends/me` | `profileUpdatedAt` | 通讯录过渡期使用，来源必须是 `users.updated_at` |

通讯录目前仍使用 `profileUpdatedAt` 作为变更提示。后续可以统一改成 `profileVersion`。在过渡期里，`profileUpdatedAt` 不能使用默认 `0` fallback，否则通讯录无法判断哪个好友资料发生过变化。

## 7. 通讯录冷启动加载态

通讯录的 loading 是页面状态，不属于资料刷新服务。

当前策略是：

- 本地没有好友缓存，并且正在请求 `/friends/me`：显示灰色背景和“正在加载...”。
- 本地已有好友缓存：立即展示缓存列表，后台刷新好友列表和变化资料。
- 本地有好友 ID 但部分资料缺失：先显示 userId 或默认头像，后台拉取资料。
- 资料刷新失败：不清空已有列表，保留本地快照。

这样通讯录冷启动时有明确反馈，二次进入或有缓存时又不会整页闪烁。

## 8. 资料更新接口的 patch 语义

用户资料更新采用“只传要修改的字段”的语义。

例如只改签名时，请求体可以是：

```json
{
  "signature": "hello"
}
```

只改性别时，请求体可以是：

```json
{
  "gender": "MALE"
}
```

未传字段表示不变，不等于清空字段。

这个语义很重要。否则客户端只改性别时，如果把未修改的 `avatarObjectKey` 序列化成 `null`，服务端可能会误以为要清空头像对象 key，导致后续头像元数据不一致。

当前实现里，客户端只序列化非 null 字段；服务端对缺失字段保持原值。

## 9. N+1 优化

用户资料刷新机制同时解决了两类 N+1 问题。

客户端本地查询：

```text
原来：每个 userId 查一次 user_profiles
现在：WHERE user_id IN (?, ?, ...)
```

服务端批量查用户：

```text
原来：每个手机号 findByPhone 一次
现在：WHERE phone IN (?, ?, ...)
```

这保证通讯录、群成员、会话列表等批量场景不会随着人数线性放大数据库查询次数。

## 10. 当前机制的边界

当前机制不包含以下能力：

- 不做 `USER_PROFILE_UPDATED` WebSocket 主动推送。
- 不做跨页面 in-flight 请求合并。
- 不做好友备注名 `remarkName`。
- 通讯录 version hint 仍处在 `profileUpdatedAt` 到 `profileVersion` 的过渡阶段；当前 `profileUpdatedAt` 必须由 `users.updated_at` 提供，不能使用默认 `0` fallback。

这些能力可以后续单独扩展，不影响当前版本缓存机制的基本闭环。

## 11. 可以如何向外说明

可以用一句话概括：

```text
用户资料缓存通过 profileVersion 做增量校验：列表页用 version hint 避免重复拉取，资料页和单聊用强制刷新保证用户主动查看时拿到最新资料，所有写入都拒绝旧版本覆盖新版本。
```

更完整一点：

```text
服务端为每个用户资料维护单调递增的 profileVersion。客户端本地缓存资料和版本号，收到消息、群成员或通讯录返回的版本提示后，只拉本地缺失或远端版本更新的用户资料。用户主动进入单聊或资料页时会强制请求远端，再按版本决定是否写入本地。这样既减少重复请求，又保证用户主动查看时能看到最新资料。
```
