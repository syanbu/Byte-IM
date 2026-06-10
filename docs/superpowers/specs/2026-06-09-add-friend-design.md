# 添加好友（Friend Request）功能 — 设计规范（SPEC）

> 日期：2026-06-09
> 对应计划文件：`docs/superpowers/plans/2026-06-09-add-friend-implementation.md`
> 调研来源：plan 阶段输出 `/home/buyansong/.claude/plans/docs-docs-lovely-walrus.md`

## 1. Context

当前 `ByteIM`（SelfHostedIm）Android 客户端**没有任何"添加好友 / 好友申请"流程**：

- 通讯录（`ContactListScreen`）里"**新的朋友**"行 `onClick = {}`（占位）
- 顶栏 `+` 弹出的 `ConversationCreateMenu` 中"**添加朋友**"项 `onClick = onDismiss`（占位）
- Mock server 端只有 `GET /friends/me` 拉取已经是好友的列表，没有任何"发起申请 / 接受 / 拒绝"端点
- 客户端 `ContactApi` 只有 `friends(accessToken)` 一个方法

本次要补齐这条链路，让"加好友"形成闭环。整体沿用项目现有的
`Api(三件套) → Repository → ViewModel(StateFlow) → Screen(Compose) → NavHost` 模式。

## 2. 用户故事 / User Flow

### Flow A：从通讯录进入"新的朋友"页
1. 用户在底部 tab 点 `通讯录`，看到 `ContactListScreen`。
2. 列表顶部 "**新的朋友**" 行右上角如有未读申请，显示一个红色 badge（数字徽标）。
3. 点击 "新的朋友" → 进入 `NewFriendsScreen`（新路由 `new-friends`）。
4. 页面默认展示**两个区块**：
   - **新的朋友（incoming）**：他人发给我的、待我处理的申请。每行展示对方头像/昵称/ID/验证消息/申请时间，右下方两个按钮：「接受」「拒绝」。
     - 点击「接受」→ 调 `acceptFriendRequest(requestId)`；成功后该行从列表移除（或者变成"已接受"灰条，参考 §6 决定），并把对方写进 `friend_contacts` 缓存（`ContactRepository.refreshFriends` 后能看到）。
     - 点击「拒绝」→ 调 `rejectFriendRequest(requestId)`；成功后该行变成"已拒绝"灰条，3 秒后从列表移除（或立刻移除，参考 §6）。
   - **我发出的（outgoing）**：我给别人发的、还在处理中的申请。每行展示对方头像/昵称/ID/状态标签（待确认 / 已接受 / 已拒绝），**只读**。
5. 空状态：每段分别有"暂无新好友申请 / 暂无发出的申请"占位文案。

### Flow B：从右上角 `+` 进入"添加好友"
1. 在 `ContactListScreen` 顶栏点 `+` → 弹出 `ConversationCreateMenu`。
2. 点 "**添加朋友**" → 跳转到 `AddFriendScreen`（新路由 `add-friend`）。
3. 页面是单输入框 + 按钮：
   - 输入框 placeholder："请输入对方手机号/用户ID"
   - 右侧 "搜索" 按钮（或者输入框右侧图标）。
   - 下方留一个"搜索结果"区域。命中用户 → 展示一行（头像/昵称/ID + "添加"按钮）；不命中 → 灰色"未找到该用户"。
   - **不在同一页直接发请求**——点 "添加" 弹出 `ModalBottomSheet`（沿用 `GroupReadDetailSheet` 模式），里面可填验证消息 + 「确认发送」按钮。
4. 提交后：
   - 成功 → toast/inline 提示"已发送申请"，弹层关闭，输入框清空，可继续添加下一个人。
   - 失败（重复申请、已是好友、自申请、用户不存在等）→ 弹层里显示服务器返回的 `message`，不关闭。
5. 顶部返回键回到上一层（Flow A 路径同样能通过 `+` 进入 Flow B，反之亦然）。

> 备注：会话列表（Messages tab）顶栏的 `+` 也共用同一个 `ConversationCreateMenu`。
> 这次只动"添加朋友"那个 item；会话列表里的"添加朋友"也跳到同一个 `AddFriendScreen`。

## 3. 数据模型

### 3.1 客户端持久化（SQLite 新表）

新表 `friend_requests`，放在 `ImDatabaseHelper.onCreate`：
```sql
CREATE TABLE IF NOT EXISTS friend_requests (
  owner_user_id      TEXT NOT NULL,   -- 哪个登录账号（区别多账号）
  request_id         TEXT NOT NULL,   -- 服务端生成的唯一 ID
  direction          INTEGER NOT NULL, -- 0 = incoming（他人→我），1 = outgoing（我→他人）
  peer_user_id       TEXT NOT NULL,   -- 对方 userId
  peer_nickname      TEXT,            -- 缓存对方昵称（可选，刷新时回填）
  peer_avatar_url    TEXT,
  verify_message     TEXT,            -- 申请附言
  status             INTEGER NOT NULL, -- 0 = pending, 1 = accepted, 2 = rejected, 3 = expired
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL,
  PRIMARY KEY(owner_user_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_friend_requests_owner_created
  ON friend_requests(owner_user_id, created_at DESC);
```

`ImDatabaseHelper.DATABASE_VERSION` 由 10 → 11，`onUpgrade` 沿用项目现有的"drop-all + onCreate"策略
（与 `friend_contacts` 同样的处理），无需写迁移 SQL。

`onUpgrade` 中 `DROP TABLE IF EXISTS friend_requests;` 也要加上（与现有 drop 列表一致）。

### 3.2 客户端领域模型

放 `app/src/main/java/com/buyansong/im/storage/StorageModels.kt` 或新建
`FriendRequestModels.kt`（沿用 `FriendContactDao.kt` 模式把 DAO 和数据类放一起更合适）：

```kotlin
enum class FriendRequestDirection { INCOMING, OUTGOING }
enum class FriendRequestStatus { PENDING, ACCEPTED, REJECTED, EXPIRED }

data class FriendRequest(
    val requestId: String,
    val direction: FriendRequestDirection,
    val peerUserId: String,
    val peerNickname: String?,
    val peerAvatarUrl: String?,
    val verifyMessage: String?,
    val status: FriendRequestStatus,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 3.3 DAO 接口

```kotlin
interface FriendRequestDao {
    fun listForOwner(ownerUserId: String): List<FriendRequest>
    fun replaceForOwner(ownerUserId: String, requests: List<FriendRequest>)
    fun upsert(ownerUserId: String, request: FriendRequest)
    fun remove(ownerUserId: String, requestId: String)
    fun unreadIncomingCount(ownerUserId: String): Int   // 用于 badge
}
```
实现：`InMemoryFriendRequestDao`（测试默认）+ `AndroidFriendRequestDao`（SQLite）。

### 3.4 Mock server 表

新建 `mock-server/data/mock-im-friend-requests.sqlite`（与 `mock-im-friends.sqlite` 平级），用 JDBC：
```sql
CREATE TABLE IF NOT EXISTS friend_requests (
  request_id   TEXT PRIMARY KEY,
  from_user_id TEXT NOT NULL,
  to_user_id   TEXT NOT NULL,
  message      TEXT,
  status       INTEGER NOT NULL,  -- 0 pending, 1 accepted, 2 rejected
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX idx_friend_requests_to_status  ON friend_requests(to_user_id, status);
CREATE INDEX idx_friend_requests_from_status ON friend_requests(from_user_id, status);
```
**不与 `friendships` 自动联动**：接受申请时由 service 同时写 `friend_requests.status=1` 和
`friendships(owner, peer)`（两个方向各一条），与现有 `addMutualFriendship` 同语义。

## 4. API 协议

### 4.1 客户端 → Mock server（HTTP，`Authorization: Bearer <accessToken>`）

| Method | Path | 用途 | 请求体 | 响应 |
|---|---|---|---|---|
| GET | `/friend-requests/me` | 拉取与当前用户相关的全部申请 | — | `200 {code:0, data:{incoming:[...], outgoing:[...]}}` |
| POST | `/friend-requests` | 发起好友申请 | `{toUserId, message?}` | `200 {code:0, data:{requestId, ...}}`；错误：`4001 用户不存在` / `4002 不能加自己` / `4003 已经是好友` / `4004 已有待处理的申请` |
| POST | `/friend-requests/{id}/accept` | 接受 | — | `200 {code:0}` |
| POST | `/friend-requests/{id}/reject` | 拒绝 | — | `200 {code:0}` |
| GET | `/users/search?userId=xxx` | 精确查一个用户（Flow B 用） | — | `200 {code:0, data:{userId, nickname, avatarUrl, ...}}`；不存在返回 `4001` |

> `/users/search` 选 query 参数而不是 `/users/{userId}` 是为了保留将来扩到 LIKE 模糊查询的余地；
> 第一版只实现精确匹配。

### 4.2 响应 JSON 形态（friend request）
```json
{
  "requestId": "fr-1718000000000-abc",
  "fromUserId": "15000000001",
  "toUserId":   "15000000000",
  "message":    "我是 Bob",
  "status": 0,
  "createdAt": 1718000000000,
  "updatedAt": 1718000000000
}
```
解析后客户端做 `direction` 推断：`fromUserId == selfUserId → OUTGOING`，否则 `INCOMING`。

## 5. 客户端架构（沿用项目既有模式）

### 5.1 新增文件清单

```
app/src/main/java/com/buyansong/im/
├── friends/                                                # 新包
│   ├── FriendRequestApi.kt                  # 接口 + sealed Result
│   ├── OkHttpFriendRequestApi.kt            # OkHttp 实现
│   ├── FriendRequestJsonParser.kt           # Gson 解析（与 ContactJsonParser 同样的 helper）
│   ├── FriendRequestRepository.kt           # 网络 + DAO 协调
│   ├── NewFriendsViewModel.kt               # 列表页 VM
│   ├── NewFriendsScreen.kt                  # 列表页 Composable
│   ├── NewFriendsDisplayPolicy.kt           # 所有 zh-CN 字符串
│   ├── AddFriendViewModel.kt                # 搜索 + 申请 VM
│   ├── AddFriendScreen.kt                   # 搜索 + 申请 Composable
│   ├── AddFriendDisplayPolicy.kt            # 字符串
│   ├── AddFriendVerifySheet.kt              # 申请时填写验证消息的 ModalBottomSheet
│   └── UserSearchApi.kt / OkHttpUserSearchApi.kt / UserSearchJsonParser.kt
│       # 也可以合并到 FriendRequestApi.kt，参考 GroupApi 既有做法
└── storage/
    └── FriendRequestDao.kt                  # 接口 + InMemory + Android(SQLite)

mock-server/src/main/java/com/buyansong/imserver/
├── friend/
│   ├── FriendRequestStore.java              # JDBC DAO
│   └── FriendRequestService.java            # 业务校验 + JSON 序列化
└── netty/
    └── HttpAuthHandler.java                 # 注册新路由（既有文件加几行）
```

### 5.2 状态形状（与 ContactProfileViewModel 等保持一致）

```kotlin
data class NewFriendsUiState(
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val pendingRequestId: String? = null,    // 正在处理 accept/reject 的行（按钮转圈）
)

data class AddFriendUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResult: UserProfile? = null,
    val searchMissed: Boolean = false,
    val isSending: Boolean = false,
    val sendError: String? = null,
    val sendSucceededFor: String? = null,    // peerUserId；用于触发 toast
    val showVerifySheet: Boolean = false,
    val verifyMessage: String = "",
)
```

### 5.3 导航

`SelfHostedImRoute.kt` 新增：
```kotlin
data object NewFriends : SelfHostedImRoute("new-friends")
data object AddFriend  : SelfHostedImRoute("add-friend")
```
两者均无 path 参数。

### 5.4 现有代码改动（最小集合）

| 文件 | 改动 |
|---|---|
| `SelfHostedImRoute.kt` | 加上面两个 `data object` |
| `MainActivity.AuthenticatedImNavHost` | 在 `composable(...)` 链中加两段；为 `ContactListScreen` 注入 `onOpenNewFriends` 和 `onAddFriend`；给 `ConversationListScreen` 的 `ConversationCreateMenu` 也注入 `onAddFriend` |
| `ContactListScreen.kt` | `ContactListScreen(...)` 签名加 `onOpenNewFriends: () -> Unit`；`ContactEntryBlock` 把它接到"新的朋友"行 `onClick`；"新的朋友"行右侧条件性渲染小红点 badge（`unreadIncoming > 0`） |
| `ui/ConversationCreateMenu.kt` | `ConversationCreateMenu(...)` 签名加 `onAddFriend: () -> Unit`；"添加朋友" `DropdownMenuItem.onClick` 调它 |
| `MainActivity.kt` 里的 `AccountScopedRepositories.create(...)` | 多构造一个 `FriendRequestRepository(api, dao)`；`OkHttpFriendRequestApi(httpBaseUrl)` 和 `OkHttpUserSearchApi(httpBaseUrl)` 注入 |
| `storage/ImDatabaseHelper.kt` | `DATABASE_VERSION = 11`；`onCreate` 加 `createFriendRequestsTable`；`onUpgrade` 加 `DROP TABLE IF EXISTS friend_requests;` |
| `contacts/ContactListViewModel.kt` | 可选：暴露 `unreadIncomingCount`（仅读 `friendRequestDao.unreadIncomingCount(ownerUserId)`）以便 badge 显示。如果不想让 `ContactListViewModel` 依赖 `FriendRequestDao`，可以单独在 `MainActivity` 里开一个轻量 VM / `produceState` 读这个值 |

## 6. UI 详细规格

### 6.1 `NewFriendsScreen`
- 顶栏 `ByteImTopBar(title = "新的朋友", onBack = navController::popBackStack)`。
- 主体 `LazyColumn`，两个 section：
  - **新的朋友** section header（粗体 14sp + 计数 `(n)`）。
  - 列表项：左侧 `AvatarImage(url = peerAvatarUrl)`；中间 `Column { Text(peerNickname ?: peerUserId, style = titleMedium) ; Text("ID: $peerUserId", style = bodySmall, color = TextSecondary) ; Text(verifyMessage ?: "", style = bodySmall, color = TextSecondary, maxLines = 1) }`；右侧 `Row { TextButton("接受") ; TextButton("拒绝", colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) }`。
  - 状态非 `PENDING` 的行整体灰显（`alpha = 0.5f`）。
  - section 间距 16dp，项与项之间 `HorizontalDivider` 缩进到头像右边缘（沿用 `ByteImListRowPolicy.dividerStartPadding()`）。
- **我发出的** section header + 列表项（右侧用 `Text("待确认" / "已接受" / "已拒绝", color = ...)` 标签代替按钮）。
- 空态：当 `incoming.isEmpty() && !isRefreshing` → section 下方一个 `Box(Modifier.fillMaxWidth().padding(32.dp))` 居中显示 "暂无新好友申请"（灰色 bodyMedium）。
- 加载 / 错误：与 `ContactProfileViewModel` 一致，`isRefreshing = true` 时顶部一个 2dp `LinearProgressIndicator`；`errorMessage != null` 时一个内联重试条。

### 6.2 `AddFriendScreen`
- 顶栏 `ByteImTopBar(title = "添加朋友", onBack = navController::popBackStack)`。
- 主体 `Column`：
  - 顶部 16dp padding 内一个 `OutlinedTextField(value = query, onValueChange = vm::onQueryChange, placeholder = "请输入对方手机号/用户ID", trailingIcon = { IconButton(onClick = vm::search) { Icon(Icons.Default.Search, ...) } }, singleLine = true, modifier = Modifier.fillMaxWidth())`。
  - 下方 8dp 间距 + 搜索结果区域：
    - 命中：`Row` 显示头像/昵称/ID + 右侧 `Button("添加", onClick = vm::openVerifySheet)`。
    - 未命中：`Text("未找到该用户 $query", color = TextSecondary)`。
  - 底部弹层（`if (state.showVerifySheet) AddFriendVerifySheet(...)`），sheet 内：
    - 顶部"添加 $nickname"标题。
    - `OutlinedTextField(value = verifyMessage, onValueChange = vm::onVerifyMessageChange, placeholder = "请输入验证消息（可选）", modifier = Modifier.fillMaxWidth())`。
    - 底部 `Row`：`TextButton("取消")` + `Button("发送", enabled = !isSending)`。
- `BackHandler(enabled = state.showVerifySheet) { vm.closeVerifySheet() }`。

### 6.3 联系人页 "新的朋友" badge
- 在 `ContactEntryItem` 右侧加一个 `Box(Modifier.size(8.dp).background(Color.Red, CircleShape))` 当 `unreadIncoming > 0` 时显示。简单版：只要 `> 0` 就显示一个红点，不显示数字。

## 7. 错误码约定（mock server 端）

| code | 含义 |
|---|---|
| 0 | 成功 |
| 4001 | 用户不存在 |
| 4002 | 不能添加自己 |
| 4003 | 已经是好友 |
| 4004 | 已有待处理申请 |

> 注意：项目现有 `ContactJsonParser` 在 `code != null && code != 0` 时返回 `Failure`。
> 这次新写的 `FriendRequestJsonParser` 与之一致。

## 8. 单元测试计划（沿用项目 JUnit 4 风格）

| 测试文件 | 关键 case |
|---|---|
| `FriendRequestJsonParserTest.kt` | 解析 `incoming/outgoing`；`code != 0` → Failure |
| `FriendRequestDaoTest.kt` | `InMemoryFriendRequestDao` 全接口：list / replace / upsert / remove / unreadCount |
| `NewFriendsViewModelTest.kt` | `start()` 后从 DAO 拉取 + 网络刷新；点击 accept/reject 触发对应 API + 失败时 `errorMessage` |
| `AddFriendViewModelTest.kt` | 输入 → `search()` → `Ok / 未找到`；`openVerifySheet` → 修改 `verifyMessage` → `send()` → 成功清除 / 失败带错误 |
| `NewFriendsDisplayPolicyTest.kt` | 字符串常量存在（非空） |
| `AddFriendDisplayPolicyTest.kt` | 同上 |
| mock server：`FriendRequestServiceTest.java` | 各错误码触发；接受/拒绝后 `friendships` 写入正确 |

## 9. 不在本期范围（明确划出去）

- **WebSocket 推送新申请 / 处理结果**——只做"打开页面/下拉刷新时拉一次"。后续如需实时可在 `MessageRepository` 旁加一个 `friendRequestAlerts: SharedFlow`。
- **好友申请过期机制**——schema 留了 `EXPIRED` 枚举但不实现过期清理逻辑。
- **黑名单 / 隐私设置**。
- **从群成员里发起私加好友**。
- **`ConversationListScreen`（消息 tab）的 `+` 弹层**：仅"添加朋友"那个 item 接入新页面，"发起群聊"和"发起单聊"维持原样。
