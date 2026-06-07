# 通讯录 / 群聊 — 已加入群聊列表 设计方案

> 状态：仅设计文档，未开始实现。
> 关联：[status/B10-group-chat-and-mention.md](../../status/B10-group-chat-and-mention.md) 中 _"Group list/member sync for groups created while another member is offline or on another device"_ 与 _"Full group member display"_ 的待办；服务端 `GET /groups`、Android `DefaultGroupRepository.syncGroups`、本地 `groups` / `group_members` 两张 SQLite 表均已就绪，本次只动 UI / 状态层 + 一个本地 DAO 读法。

## Context

当前 通讯录 顶部的 `ContactEntryBlock` 含两个静态占位条目：`新的朋友`、`群聊`，它们的 `onClick = {}`，见 [contacts/ContactListScreen.kt:250-266](../../app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt#L250-L266)。点 `群聊` 当前没有任何反应。

需求：点击 `群聊` → 进入 **"已加入的群聊"** 列表页 → 列出当前用户作为成员的所有群 → 点任一行进入对应群聊聊天界面。

底层数据流早已就绪：

- 服务端 `GET /groups` 已按"成员资格"过滤返回，见 [mock-server/.../GroupService.java:70-80, 169-180](../../mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java#L70-L80)；路由在 [HttpAuthHandler.java:231-234](../../mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java#L231-L234)。
- Android 侧 [group/GroupRepository.kt:70-78](../../app/src/main/java/com/buyansong/im/group/GroupRepository.kt#L70-L78) 的 `DefaultGroupRepository.syncGroups(token)` 已经会拉这个接口并把 `GroupInfo` upsert 到本地 `groups` 表；[conversation/ConversationListViewModel.kt:239](../../app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt#L239) 在刷新会话列表时就会调用一次，所以 `groups` 表通常已经有缓存数据。
- Chat 路由 `chat/{conversationId}` 已识别 `group:<groupId>`，见 [SelfHostedImRoute.kt:25-37](../../app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt#L25-L37)、[group/GroupCreateNavigationPolicy.kt](../../app/src/main/java/com/buyansong/im/group/GroupCreateNavigationPolicy.kt)、[MainActivity.kt:608-643](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L608-L643)；创建群聊成功后就走这条路径。
- `ChatViewModel` 已识别 `group:` 前缀并走群聊流程：`sendGroupText` / `openConversationById` / `historyPageByConversationId` / `renameGroup`，见 [chat/ChatViewModel.kt:147,546,554,568](../../app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt#L546)。

**所以本次只是把已经持久化的数据暴露为一个新页面 + 一个新的导航出口。不动协议，不改 `messages` / `conversations` / `group_members` 三张表的 schema（只在 `groups` 表加一列）。**

为什么不复用 `ConversationListViewModel`：它只列出 _"有过会话行的群"_，而服务端 `GET /groups` 返回的是 _"我是成员的全部群"_。两者集合不同——按 `groups` 表为准更符合 _"已加入"_ 的语义；同时也更轻量（不读 `messages` / `conversations`）。

## 目标

- 通讯录顶部的 `群聊` 行从纯占位变为可点击入口；点击 → 跳转到新增的 `JoinedGroupsScreen`。
- `JoinedGroupsScreen` 顶部为 `ByteImTopBar(title = "群聊", onBack = ...)`；正文为列表，每行 = 群头像 + 群名称 + 一行辅助文本 `"N 位成员"`（WeChat 风格的最小可用版）。
- 列表数据源：先读本地 `groups` 表立即出（不闪白屏），同时异步调 `groupRepository.syncGroups(token)` 拉远端覆盖。
- 列表为空时居中显示 `"暂无群聊"`（`bodyMedium`, `TextSecondary`）。
- 点任意行 → 调 `SelfHostedImRoute.Chat.createRoute("group:<groupId>")` + `MainActivity.navigateToChat(...)` 进入群聊。`ChatViewModel` 已完整支持群聊路径，无需任何改动。
- 顶部不加 `+ 发起群聊` 菜单、不加搜索框、不显示未读 / 最后一条消息预览、不做长按删除（一切附加能力均明确不在本次范围）。

数据流示意：

```
ContactListScreen "群聊" 行 (onOpenJoinedGroups)
        │ navigate
        ▼
JoinedGroupsScreen ── tap row ──▶ chat/group:<groupId>
        ▲                                  │
        │ JoinedGroupsViewModel.refresh()  │
        │   1) groupDao.groupsForUser(uid) │  (本地缓存,首屏立现)
        │   2) groupRepository.syncGroups  │  (远端 → upsert → 再次 emit)
        │                                  │
        └──── AndroidGroupDao  ◀───────────┘  ChatViewModel (已有的群聊全流程)
```

## 复用的既有组件 / 数据

| 用途 | 路径 | 备注 |
|---|---|---|
| `GET /groups` 按成员过滤 | [mock-server/.../GroupService.java:70-80, 169-180](../../mock-server/src/main/java/com/buyansong/imserver/group/GroupService.java#L70) | 已实现；返回 `groupId, name, ownerId, createdAt, updatedAt, memberUserIds` |
| `DefaultGroupRepository.syncGroups(token)` | [group/GroupRepository.kt:70-78](../../app/src/main/java/com/buyansong/im/group/GroupRepository.kt#L70-L78) | 拉远端 → 每个 `GroupInfo` upsert 到 `groupDao` + PATCH 任意已存在的 `conversations` 行 |
| `GroupDao.groupIdsForUser(userId)` | [storage/GroupDao.kt:11](../../app/src/main/java/com/buyansong/im/storage/GroupDao.kt#L11)、[storage/AndroidGroupDao.kt:52-68](../../app/src/main/java/com/buyansong/im/storage/AndroidGroupDao.kt#L52-L68) | 已带索引 `idx_group_members_user`，单字段查询；本次新增的 `groupsForUser` JOIN 复用同一索引 |
| `GroupInfo` / `GroupMember` 数据模型 | [storage/StorageModels.kt](../../app/src/main/java/com/buyansong/im/storage/StorageModels.kt) | `GroupInfo` 新增 `memberCount: Int = 0` 字段（默认 0 保兼容） |
| `chat/{conversationId}` 通用路由 | [SelfHostedImRoute.kt:25-37](../../app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt#L25-L37) | `Chat.createRoute("group:$gid")` 已可用 |
| `MainActivity.navigateToChat` 私有扩展 | [MainActivity.kt:679-686](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L679-L686) | 与"发起群聊创建成功后跳聊天"完全同款的导航 |
| `ByteImTopBar` / `ByteImListSurface` / `ByteImListRowPolicy.dividerStartPadding()` | [ui/ByteImUi.kt:7-10, 68-130, 147-159](../../app/src/main/java/com/buyansong/im/ui/ByteImUi.kt#L7) | 直接复用，不新增 token |
| `AvatarImage(avatarUrl, displayName, isGroup, modifier)` | [ui/AvatarImage.kt](../../app/src/main/java/com/buyansong/im/ui/AvatarImage.kt) | `isGroup = true` 渲染 `"群"` 字占位（与 `ConversationListScreen` 群聊行一致） |
| `ContactRow` 单行版式参考 | [contacts/ContactListScreen.kt:170-207](../../app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt#L170-L207) | `JoinedGroupRow` 直接镜像其结构（Row + ListItemHeight + Surface + clickable + 头像 + 两行文本） |
| `GroupCreateViewModel` 的 ViewModel 形态 | [group/GroupCreateViewModel.kt](../../app/src/main/java/com/buyansong/im/group/GroupCreateViewModel.kt) | `JoinedGroupsViewModel` 完全镜像它的 `start()` / `stop()` / `refresh()` / `MutableStateFlow` 模式 |
| `AccountScopedRepositories.groupRepository` 注入点 | [MainActivity.kt:340-343](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L340-L343) | 现有 `DefaultGroupRepository(OkHttpGroupApi, AndroidGroupDao, conversationDao)` 直接复用 |
| 测试模板（JUnit4 + InMemory DAO + Fake Api） | [test/.../group/GroupRepositoryTest.kt](../../app/src/test/java/com/buyansong/im/group/GroupRepositoryTest.kt)、[test/.../storage/GroupDaoContractTest.kt](../../app/src/test/java/com/buyansong/im/storage/GroupDaoContractTest.kt) | 新增 ViewModel + DAO 测试时同款 |

## 改动清单

### 新增

#### 1. `app/src/main/java/com/buyansong/im/group/JoinedGroupsScreen.kt`

全屏 Compose 页面。结构与 [contacts/ContactListScreen.kt](../../app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt) 对称：

```kotlin
@Composable
fun JoinedGroupsScreen(
    viewModel: JoinedGroupsViewModel,
    state: JoinedGroupsUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenGroup: (groupId: String) -> Unit
) {
    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    Column(modifier = modifier.fillMaxSize().background(ByteImColors.AppBackground)) {
        ByteImTopBar(title = "群聊", onBack = onBack, centerTitle = true,
                     containerColor = ByteImColors.Surface)
        ByteImListSurface(
            modifier = Modifier.weight(1f),
            containerColor = ByteImColors.AppBackground
        ) {
            if (state.items.isEmpty() && !state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无群聊", style = MaterialTheme.typography.bodyMedium,
                         color = ByteImColors.TextSecondary)
                }
                return@ByteImListSurface
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    HorizontalDivider(color = ByteImColors.Divider,
                        modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding()))
                }
                items(state.items, key = { it.groupId }) { item ->
                    JoinedGroupRow(item = item, onClick = { onOpenGroup(item.groupId) })
                    HorizontalDivider(color = ByteImColors.Divider,
                        modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding()))
                }
            }
        }
    }
}

@Composable
private fun JoinedGroupRow(item: JoinedGroupItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .background(ByteImColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = item.avatarUrl,
            displayName = item.name,
            isGroup = true,
            modifier = Modifier.size(ByteImDimensions.ListAvatarSize)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium,
                 color = ByteImColors.TextPrimary, fontWeight = FontWeight.Medium,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.memberCount > 0) {
                Text("${item.memberCount} 位成员", style = MaterialTheme.typography.bodyMedium,
                     color = ByteImColors.TextSecondary, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
```

#### 2. `app/src/main/java/com/buyansong/im/group/JoinedGroupsViewModel.kt`

镜像 [group/GroupCreateViewModel.kt](../../app/src/main/java/com/buyansong/im/group/GroupCreateViewModel.kt) 的形态：

```kotlin
data class JoinedGroupItem(
    val groupId: String,
    val name: String,
    val avatarUrl: String?,
    val memberCount: Int
)

data class JoinedGroupsUiState(
    val items: List<JoinedGroupItem> = emptyList(),
    val isLoading: Boolean = false
)

class JoinedGroupsViewModel(
    private val session: AuthSession,
    private val groupRepository: GroupRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(JoinedGroupsUiState())
    val state: StateFlow<JoinedGroupsUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var started = false

    fun start() {
        if (started) { refresh(); return }
        started = true
        refresh()
    }

    fun stop() {
        refreshJob?.cancel(); refreshJob = null; started = false
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch(dispatcher) {
            // (1) 本地缓存优先
            mutableState.value = mutableState.value.copy(
                items = groupRepository.joinedGroups(session.userId).toItems(),
                isLoading = true
            )
            // (2) 异步拉远端
            val validSession = validSessionProvider() ?: run {
                mutableState.value = mutableState.value.copy(isLoading = false)
                return@launch
            }
            groupRepository.syncGroups(validSession.accessToken)
            // (3) 重新读本地，emit 最终结果
            mutableState.value = mutableState.value.copy(
                items = groupRepository.joinedGroups(session.userId).toItems(),
                isLoading = false
            )
        }
    }

    private fun List<GroupInfo>.toItems(): List<JoinedGroupItem> =
        map { JoinedGroupItem(it.groupId, it.name, it.avatarUrl, it.memberCount) }
}
```

要点：
- `refresh()` 三段式：先 emit 本地缓存（首屏立现）→ 调网络 → emit 远端覆盖。
- `syncGroups` 失败时返回 `emptyList()`（见 [GroupRepository.kt:76](../../app/src/main/java/com/buyansong/im/group/GroupRepository.kt#L76)），不会清空本地，第 (3) 步重读本地依然能拿到上次缓存。
- `validSession == null`（token 失效）跳过网络步直接收尾，避免空白闪烁。

#### 3. `app/src/test/java/com/buyansong/im/group/JoinedGroupsViewModelTest.kt`

JUnit 4 + `runTest` / `StandardTestDispatcher` / `runCurrent`，参照 [test/.../conversation/ConversationListViewModelTest.kt](../../app/src/test/java/com/buyansong/im/conversation/ConversationListViewModelTest.kt) 的 Fixture 风格：

- Fixture：`InMemoryGroupDao` + `InMemoryConversationDao` + `FakeGroupApi`（参照 [GroupRepositoryTest.kt:131-147](../../app/src/test/java/com/buyansong/im/group/GroupRepositoryTest.kt#L131-L147) 的 `FakeGroupApi`，返回固定 `GroupListResult.Success(groups)`）+ `DefaultGroupRepository` + `JoinedGroupsViewModel`。
- Test 1 `localCacheRendersBeforeSyncReturns`：预先 `groupDao.upsertGroup(...)` + `replaceMembers(...)` 写两个群（含当前用户），`fakeGroupApi.groupsResult = GroupListResult.Failure(...)`。`start()` + `runCurrent()` 后,断言 `state.items.size == 2`,顺序按 `updatedAt DESC`。
- Test 2 `syncAddsRemoteGroupsToList`：本地无任何群，`fakeGroupApi.groupsResult = GroupListResult.Success(listOf(GroupInfo(...), GroupInfo(...)))`，每个含 `memberCount = 3`。`start()` + `runCurrent()`,断言最终 `state.items.size == 2`,`memberCount == 3`,`isLoading == false`。
- Test 3 `syncFailureKeepsLocalCache`：本地写一个群，`fakeGroupApi.groupsResult = GroupListResult.Failure("network")`。`start()` 后 state 仍含本地那个群。
- Test 4 `userWithoutMembershipShowsEmpty`：本地有群但当前用户不是任何群的 member。`state.items.isEmpty() == true`。

#### 4. `app/src/test/java/com/buyansong/im/storage/GroupDaoContractTest.kt` 内追加

新增 `@Test fun groupsForUserFiltersByMembershipAndSortsByUpdatedAtDesc()`：
- 预先 `upsertGroup(g1, updatedAt=1000)` / `upsertGroup(g2, updatedAt=2000)` / `upsertGroup(g3, updatedAt=3000)`。
- `replaceMembers(g1, listOf(user="alice", "bob"))`、`replaceMembers(g2, listOf(user="bob"))`、`replaceMembers(g3, listOf(user="carol"))`。
- 断言 `dao.groupsForUser("bob") == listOf(g2, g1)`（按 `updatedAt` 倒序）。

### 修改

#### 5. `app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt`

新增 `data object JoinedGroups : SelfHostedImRoute("joined-groups")`。无 args，不需要 `createRoute` 工具方法（直接用 `route` 即可）。

#### 6. `app/src/main/java/com/buyansong/im/storage/StorageModels.kt`

`data class GroupInfo` 新增字段 `val memberCount: Int = 0`，默认 0 保证现有调用点不需要改：

```kotlin
data class GroupInfo(
    val groupId: String,
    val name: String,
    val avatarUrl: String?,
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val memberCount: Int = 0  // 新增
)
```

#### 7. `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`

- `groups` 表 schema 加列：`member_count INTEGER NOT NULL DEFAULT 0`。
- `DATABASE_VERSION` 从 `8` 改为 `9`。`onUpgrade` 现行是 drop + recreate（demo 数据，无生产迁移负担），无需写迁移 SQL。

#### 8. `app/src/main/java/com/buyansong/im/storage/GroupDao.kt`

接口加：

```kotlin
fun groupsForUser(userId: String): List<GroupInfo>
```

`InMemoryGroupDao` 实现：

```kotlin
override fun groupsForUser(userId: String): List<GroupInfo> {
    return membersByGroup
        .filterValues { members -> members.any { it.userId == userId } }
        .keys
        .mapNotNull { groups[it] }
        .sortedByDescending { it.updatedAt }
}
```

#### 9. `app/src/main/java/com/buyansong/im/storage/AndroidGroupDao.kt`

- `upsertGroup` / `findGroup`：读写新增的 `member_count` 列。
- 新增 `groupsForUser` 一次 `rawQuery`：

```kotlin
override fun groupsForUser(userId: String): List<GroupInfo> {
    val cursor = database.rawQuery(
        """
        SELECT g.group_id, g.name, g.avatar_url, g.owner_id, g.created_at, g.updated_at, g.member_count
        FROM groups g
        JOIN group_members m ON m.group_id = g.group_id
        WHERE m.user_id = ?
        ORDER BY g.updated_at DESC
        """.trimIndent(),
        arrayOf(userId)
    )
    return cursor.use { /* 映射成 List<GroupInfo> */ }
}
```

复用现有 `idx_group_members_user` 索引（[ImDatabaseHelper.kt:166](../../app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt#L166)）；JOIN 走 `groups.group_id` 主键，无需新增索引。

#### 10. `app/src/main/java/com/buyansong/im/group/GroupApi.kt`

`GroupJsonParser.toGroupInfo()` 解析 `memberUserIds.size`（若存在）写入 `memberCount`：

```kotlin
private fun JsonObject.toGroupInfo(): GroupInfo {
    val groupId = requiredString("groupId")
    return GroupInfo(
        groupId = groupId,
        name = optionalString("name") ?: "群聊",
        avatarUrl = optionalString("avatarUrl") ?: optionalString("avatar_url"),
        ownerId = requiredString("ownerId"),
        createdAt = optionalLong("createdAt") ?: 0L,
        updatedAt = optionalLong("updatedAt") ?: optionalLong("createdAt") ?: 0L,
        memberCount = optionalArray("memberUserIds")?.size() ?: 0  // 新增
    )
}
```

这是单点改动；`parseGroup` / `parseGroups` / `parseCreateGroup` / `parseMembers` 因为都走 `toGroupInfo()` 自动生效。

#### 11. `app/src/main/java/com/buyansong/im/group/GroupRepository.kt`

`GroupRepository` 接口加：

```kotlin
fun joinedGroups(userId: String): List<GroupInfo>
```

`DefaultGroupRepository` 实现：

```kotlin
override fun joinedGroups(userId: String): List<GroupInfo> =
    groupDao.groupsForUser(userId)
```

不调网络（网络刷新走已有的 `syncGroups`）。`persist(...)` / `persistGroupInfo(...)` 调 `groupDao.upsertGroup(group)` 时把 `memberCount` 一起写进去（已经透传，无需额外改动）。

#### 12. `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`

- `ContactListScreen` 函数签名追加参数 `onOpenJoinedGroups: () -> Unit`。
- `ContactEntryBlock()` 改为 `ContactEntryBlock(onOpenJoinedGroups: () -> Unit)`，把 `群聊` 行的 `onClick = {}` 替换为 `onClick = onOpenJoinedGroups`。`新的朋友` 行保持空 onClick（不在本次范围）。
- 调用位置（同文件内的 `LazyColumn` 第一项）改为 `ContactEntryBlock(onOpenJoinedGroups = onOpenJoinedGroups)`。

#### 13. `app/src/main/java/com/buyansong/im/MainActivity.kt`

(1) `Contacts` 路由 `composable` 块（[MainActivity.kt:497-526](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L497-L526)）：`ContactListScreen(...)` 调用追加参数

```kotlin
onOpenJoinedGroups = {
    navController.navigate(SelfHostedImRoute.JoinedGroups.route) {
        launchSingleTop = true
    }
}
```

模式与现有 `onStartGroupChat`（[MainActivity.kt:504-508](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L504-L508)）一致。

(2) 新增 `composable(SelfHostedImRoute.JoinedGroups.route)` 块，结构参照 [MainActivity.kt:528-548](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L528-L548) 的 `GroupCreate`：

```kotlin
composable(SelfHostedImRoute.JoinedGroups.route) {
    val joinedGroupsViewModel = remember(session.userId) {
        JoinedGroupsViewModel(
            session = session,
            groupRepository = groupRepository,
            validSessionProvider = validSessionProvider
        )
    }
    val joinedGroupsState by joinedGroupsViewModel.state.collectAsState()
    JoinedGroupsScreen(
        viewModel = joinedGroupsViewModel,
        state = joinedGroupsState,
        onBack = { navController.popBackStack() },
        onOpenGroup = { groupId ->
            SelfHostedImRoute.Chat.createRoute("group:$groupId")
                ?.let(navController::navigateToChat)
        }
    )
}
```

### 不动

- 通讯录 `新的朋友` 行 —— 仍是占位。
- `ConversationListViewModel` / `GroupCreateViewModel` / `ChatViewModel` / `ChatScreen` —— 完全不动。
- 所有协议代码（`SEND_MESSAGE` / `MESSAGE_ACK` / `RECEIVE_MESSAGE` / `DELIVERY_ACK`、`GroupApi.createGroup` / `renameGroup` / `members`、WebSocket 命令）。
- SQLite `messages` / `conversations` / `group_members` 三张表的 schema（仅 `groups` 表加一列）。
- Mock-server 任何文件 —— `GET /groups` 已返回所需 JSON。

## 验证

### 单元 / 静态

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.group.JoinedGroupsViewModelTest --tests com.buyansong.im.storage.GroupDaoContractTest --tests com.buyansong.im.group.GroupRepositoryTest --console=plain
```

期望：全部 pass。`GroupRepositoryTest` 现有用例须保持绿（`GroupInfo.memberCount` 默认 0 + `joinedGroups` 是新接口方法，签名兼容；`InMemoryGroupDao.groupsForUser` 实现纯过滤无副作用）。

之后跑一遍全模块回归：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

### 端到端 / 手动

前置：Mock-server 已启动（`cd mock-server && mvn -q exec:java`）且至少存在一个群。可用 [mock-test/seed_local_messages.py](../../mock-test/seed_local_messages.py) 预置数据，或直接在 app 内用 `发起群聊` 创建。

1. 用账号 A 登录，**通讯录** Tab → 点 `群聊` 行。
2. 进入 _已加入群聊_ 页：标题居中显示 `群聊`，左上有返回箭头。
3. 列表显示 A 是成员的所有群，每行 = 群头像（"群"字占位）+ 群名称 + `N 位成员` 辅助文本。
4. 点任一行 → 进入 `chat/group:<groupId>` 群聊界面：顶部群名正确、历史消息加载、@ 选择器、发送框可用。
5. 在群聊里发一条消息 → 返回到 _已加入群聊_ → 返回到通讯录，栈正确（每层都对应一次 `popBackStack`）。
6. 退出账号 A，登录账号 B（B 不是任何群成员）→ 通讯录 → 点 `群聊` → 列表为空，居中显示 `暂无群聊`。
7. 用账号 A（在另一台设备或在 mock-server 命令行）把 B 加入新群 → B 重新进入 _已加入群聊_ → 新群应当出现（重进会触发 `syncGroups` 拉远端）。

### 回归

- 切换账号后列表正确隔离：[MainActivity.kt:311](../../app/src/main/java/com/buyansong/im/MainActivity.kt#L311) 的 `AccountScopedDatabaseName.forUser(userId)` 已经按 userId 分库，`JoinedGroupsViewModel` 的 `remember(session.userId)` 保证 VM 重建。
- 离线进入页面：因为 `refresh()` 先 emit 本地、再调网络，无网时仍能显示本地缓存。
- 进入 _已加入群聊_ → 立即返回 → 再次进入：不应有空白闪烁（首次 emit 来自本地缓存）。
- 现有 `发起群聊` 流程不变：[GroupCreateNavigationPolicy.kt](../../app/src/main/java/com/buyansong/im/group/GroupCreateNavigationPolicy.kt) 创建成功后仍直接跳 `chat/group:<groupId>`，不经过 _已加入群聊_。
- `ConversationListViewModel.refresh()` 仍按原路径调 `syncGroups`，与新页面无耦合。

## 风险与缓解

- **`GroupInfo.memberCount` 新增字段触发 `DATABASE_VERSION` 升级。** 项目 `onUpgrade` 是 drop + recreate（仅 demo 数据），无生产迁移负担。复测一次清装即可。受影响的所有 `GroupInfo` 构造点（`GroupJsonParser.toGroupInfo` / `GroupRepositoryTest.kt` 内的 fixture / 任何手写的 `GroupInfo(...)`）由于新字段有默认值 `= 0`，旧调用无需修改。
- **用户加入了某群但本地从未收到消息，`conversations` 表无对应行。** 本方案不依赖 `conversations`，只读 `groups` + `group_members`，因此与该缺口解耦。进入聊天后首条消息会按现有 [message/MessageRepository.kt](../../app/src/main/java/com/buyansong/im/message/MessageRepository.kt) 路径补建 `Conversation` 行。
- **`GET /groups` 失败时 `syncGroups` 返回 `emptyList()` 并不会清空本地表。** ViewModel `refresh()` 第 (3) 步重读本地缓存，因此远端失败下 UI 仍显示上次已知列表，不会变空。
- **多账号切换缓存串扰。** 数据库本就按 userId 分库；`JoinedGroupsViewModel` 用 `remember(session.userId)` 包一层即可。
- **`groupsForUser` 不会复用 `groups` 表的索引。** JOIN 通过 `idx_group_members_user`（对 `user_id` 过滤）+ `groups.group_id` 主键查找；`ORDER BY g.updated_at DESC` 走文件排序。在典型成员所属群数 < 100 的量级下无可观感知延迟；如未来量级上去，可加一条 `CREATE INDEX idx_groups_updated_at ON groups(updated_at DESC)`，本次不做。
- **首屏缓存路径在 IO dispatcher 上跑。** `refresh()` 的两段 emit 都在 `Dispatchers.IO`，UI 状态变更通过 `MutableStateFlow` 切回主线程渲染，与 `ContactListViewModel.refresh()` 一致。

## 不在本次范围内（明确划界）

- 通讯录 `新的朋友` 行的交互（仍是占位）。
- _已加入群聊_ 顶部的 `+ 发起群聊` 菜单。
- _已加入群聊_ 顶部的搜索框 / 按群名过滤。
- 行内未读 / 最后一条消息预览 / @我提示（这是 `消息` Tab 的语义，本页只做"加入了哪些群"）。
- 长按删除 / 退出群、群头像编辑、群主转让、踢人邀请、群公告。
- `GET /groups` 的服务端字段扩展（如 `avatarUrl`、`memberCount` 字段化，本次只是在 Android 侧从 `memberUserIds.size` 推算）。
- WebSocket 推送的群成员变更实时刷新（本次只在 `JoinedGroupsViewModel.start()` 一次性 `syncGroups`）。
- B10 长期待办 _"groups created while another member is offline or on another device"_ 的服务端补齐（已经能用，本次只是把它在 UI 暴露出来）。

## 实施顺序建议（单次 session，约 1.5–3 小时）

1. `GroupInfo.memberCount` + `ImDatabaseHelper` schema/版本号 + `AndroidGroupDao.upsertGroup`/`findGroup` 透传新列；`GroupDao.groupsForUser` 在两个实现里加上 → 跑 `GroupDaoContractTest`。
2. `GroupApi.GroupJsonParser.toGroupInfo` 解析 `memberUserIds.size` → 跑 `GroupRepositoryTest`，确认现有用例仍绿。
3. `GroupRepository.joinedGroups` 转发到 DAO。
4. `SelfHostedImRoute.JoinedGroups` + `JoinedGroupsViewModel` + `JoinedGroupsViewModelTest` → 跑测试。
5. `JoinedGroupsScreen`（纯 UI，无逻辑）。
6. `ContactListScreen` 接 `onOpenJoinedGroups` + `MainActivity` 接路由/导航。
7. `.\gradlew.bat :app:testDebugUnitTest` 全量回归 + 手动跑端到端清单。
