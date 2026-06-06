# 通讯录缓存与资料增量刷新优化

## 目前的问题

通讯录页现在已经具备“先显示缓存，再后台刷新”的基础能力，但缓存和刷新策略还不够接近微信这类成熟 IM 的体验。

主要问题是：

1. 好友 ID 只缓存在 `ContactRepository` 的内存字段里，App 冷启动、进程被杀或重新创建仓库后，这份好友列表会丢失。
2. 如果内存里没有好友 ID，通讯录必须先请求 `/friends/me`，拿到好友 ID 后才能组装列表。
3. 每次进入通讯录刷新时，会对完整好友列表调用 `/users/batch` 拉取 profile。假设用户有 500 个好友，即使只有 1 个好友改了昵称或头像，也会倾向于刷新全部 500 个好友资料。
4. 顶层 Tab 来回切换时，通讯录页面可能重新触发 `start()` / `stop()` 流程，带来重复刷新和界面重新出现的感觉。
5. UI 使用 `LazyColumn`，屏幕层不会一次性真正渲染 500 行，但 ViewModel 层仍然会一次性构造完整 `state.items`，资料刷新也是全量批处理。

用户体感上，这会表现为：第一次进入通讯录需要等待远端数据；后续在消息页和通讯录页之间切换时，列表仍可能重新刷新、跳动，或者头像昵称稍后整体更新。

## 为什么会这个问题

当前联系人数据流大致是：

```kotlin
val cachedContactIds = contactRepository.cachedFriendUserIds()
if (cachedContactIds.isNotEmpty()) {
    mutableState.value = mutableState.value.copy(items = buildItems(cachedContactIds))
}

val contactIds = contactRepository.friendUserIds(validSession.accessToken)
profileRepository.refreshProfiles(validSession.accessToken, contactIds)
mutableState.value = mutableState.value.copy(items = buildItems(contactIds))
```

这里有几个关键原因：

1. `UserProfile` 已经有本地 SQLite 缓存，但好友 ID 列表没有持久化存储。也就是说，本地可能知道某个用户的昵称和头像，却不知道“这个用户是不是当前账号的好友”。
2. `ContactRepository.cachedFriendUserIds()` 只是内存缓存，不是账号级本地数据库缓存，所以不能支撑冷启动后的秒开。
3. `/friends/me` 当前主要返回好友 ID 列表，没有返回每个好友资料的版本信息。客户端无法判断哪个好友资料变了，只能保守地请求全部好友 profile。
4. `ProfileRepository.refreshProfiles(accessToken, contactIds)` 接收的是完整好友 ID 列表，所以资料刷新粒度是“全部联系人”，不是“缺失或变化的联系人”。
5. `ContactListScreen` dispose 时会调用 `viewModel.stop()`，而当前停止逻辑会让下一次进入更容易重新刷新。顶层导航也没有明确保存和恢复 Tab 状态。

因此，当前方案虽然比“完全等待远端返回后再展示”更好，但仍然不是完整的本地优先、增量同步模型。

## 优化设计

本轮优化目标是：通讯录第一次加载后，后续打开和 Tab 切换尽量直接展示稳定的本地快照；后台只刷新真正变化的联系人资料；暂不做服务端好友分页。

### 1. 持久化好友列表和资料版本

新增账号级本地表，例如 `friend_contacts`：

```sql
CREATE TABLE IF NOT EXISTS friend_contacts (
  owner_user_id TEXT NOT NULL,
  friend_user_id TEXT NOT NULL,
  profile_updated_at INTEGER NOT NULL,
  sort_order INTEGER NOT NULL,
  PRIMARY KEY(owner_user_id, friend_user_id)
)
```

用途：

- `owner_user_id`：当前登录账号。
- `friend_user_id`：好友用户 ID。
- `profile_updated_at`：服务端返回的好友资料版本。
- `sort_order`：通讯录顺序，先保持服务端返回顺序。

这样 App 冷启动后，即使还没有请求 server，也能先从本地读出好友 ID，再结合本地 `UserProfile` 秒开通讯录。

### 2. `/friends/me` 返回轻量好友元数据

让 `/friends/me` 不只返回：

```json
{
  "friendUserIds": ["15000000003", "15000000004"]
}
```

还返回每个好友的资料版本：

```json
{
  "friends": [
    {
      "userId": "15000000003",
      "profileUpdatedAt": 3000
    },
    {
      "userId": "15000000004",
      "profileUpdatedAt": 4000
    }
  ]
}
```

这里优先使用 `profileUpdatedAt` / `updatedAt`，不引入 hash 字段。原因是版本时间更容易调试，也已经存在于当前 profile 数据模型中。

当用户修改昵称、头像、性别、签名等资料时，服务端更新该用户的 `profileUpdatedAt`。客户端下次刷新好友列表时，只需要比较版本即可知道资料是否变化。

### 3. 只刷新缺失或变化的 profile

客户端拿到远端好友元数据后，按下面规则判断是否需要请求 `/users/batch`：

1. 本地没有这个好友的 `UserProfile`：需要拉取。
2. 本地有 `UserProfile`，但远端 `profileUpdatedAt > local.updatedAt`：需要拉取。
3. 本地有 `UserProfile`，且版本一致或本地更新：不需要拉取。

示例：

```kotlin
private fun changedProfileIds(contacts: List<FriendContact>): List<String> {
    return contacts
        .filter { contact ->
            val local = profileRepository.localProfile(contact.userId)
            local == null || contact.profileUpdatedAt > local.updatedAt
        }
        .map { it.userId }
}
```

这样，如果用户 A 有 500 个好友，只有好友 B 修改了昵称和头像，那么本次只需要：

1. 请求 `/friends/me` 拿 500 个好友的轻量版本信息。
2. 比较本地版本。
3. 只对 B 调用 `/users/batch`。

不再需要每次刷新全部 500 个好友的完整资料。

### 4. 首屏优先，剩余分批刷新

为了让通讯录打开更稳，profile 刷新按优先级处理：

1. 先从本地 `friend_contacts` + `user_profiles` 构造完整列表。
2. 后台请求 `/friends/me` 更新好友列表和版本信息。
3. 找出缺失或变化的联系人。
4. 优先刷新前 20 个变化联系人。
5. 剩余变化联系人按每批 10 个继续后台刷新。

建议常量：

```kotlin
private companion object {
    const val INITIAL_PROFILE_REFRESH_LIMIT = 20
    const val NEXT_PROFILE_REFRESH_LIMIT = 10
}
```

这里的“前 20 个”不是服务端分页，而是客户端优先刷新首屏附近的数据。好友 ID 仍然可以全量同步，profile 资料不再全量同步。

### 5. 保留通讯录 Tab 状态

顶层 Tab 切换应该更像“显示已经存在的页面”，而不是“重新进入一个页面”。

优化方向：

1. 顶层导航使用 `saveState = true` 和 `restoreState = true`。
2. `ContactListViewModel.stop()` 只取消正在进行的刷新任务，不把 `started` 重置成未启动。
3. 切回通讯录时保留旧的 `state.items`，后台刷新不能先清空列表。
4. 只有好友列表顺序、好友增删、资料版本变化时，才更新对应的列表内容。

这样消息页和通讯录页来回切换时，用户看到的是稳定的旧列表，后台再静默补新数据。

### 6. 本轮不做服务端好友分页

本轮暂不改成 `/friends/me?cursor=...&limit=...` 这种服务端分页。

原因是当前最明显的开销不是“好友 ID 太多无法返回”，而是“每次拿到好友 ID 后都刷新全部 profile”。先引入 profile 版本比较和本地好友持久化，可以用较小改动获得主要体验收益。

后续如果好友量增长到几千或几万，再考虑服务端好友分页。

### 预期效果

优化后通讯录刷新流程变成：

```text
打开通讯录
-> 读取本地 friend_contacts
-> 读取本地 user_profiles
-> 立即显示联系人列表
-> 后台请求 /friends/me
-> 比较 profileUpdatedAt
-> 只请求缺失或变化的 profiles
-> 局部更新联系人昵称/头像
```

用户体验目标：

- 冷启动后只要本地有旧数据，通讯录可以先显示旧快照。
- 消息页和通讯录页来回切换不明显卡顿、不整页跳出。
- 只有好友资料真的变化时，才拉取完整 profile。
- 500 个好友中只有 1 个变更时，只刷新这 1 个好友的资料。
- 服务端分页留到后续再做。
