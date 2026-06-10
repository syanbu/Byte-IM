# 添加好友（Friend Request）功能 — 实施计划（PLAN）

> 日期：2026-06-09
> 对应设计文件：`docs/superpowers/specs/2026-06-09-add-friend-design.md`

> **每一步都是 2~5 分钟的原子动作。** 频繁 commit。
> 下面以"按文件 / 提交"为粒度切分；执行时按顺序推进。

## 任务 0：前期准备

- [ ] **Step 0.1** 在 `docs/superpowers/specs/2026-06-09-add-friend-design.md` 写入 SPEC（已由 plan 阶段完成）
- [ ] **Step 0.2** 切到 `rename-redesign-ui` 分支的子分支 `feature/add-friend`
  - `git checkout -b feature/add-friend rename-redesign-ui`
- [ ] **Step 0.3** 先确认基线测试通过
  - `./gradlew :app:testDebugUnitTest`
  - 预期：BUILD SUCCESSFUL

---

## 任务 1：Mock Server 端 — `friend_requests` 表与 Store

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/friend/FriendRequestStore.java`

- [ ] **Step 1.1** 创建 `FriendRequestStore.java`，写好 JDBC 连接和 `CREATE TABLE IF NOT EXISTS` SQL（沿用 `FriendStore.java` 的 DataSource 模式）
- [ ] **Step 1.2** 实现 `int insert(FriendRequestRow r)`、`List<FriendRequestRow> listForUser(String userId)`、`Optional<FriendRequestRow> findById(String requestId)`、`int updateStatus(String requestId, int status)`
- [ ] **Step 1.3** 跑 `mvn -q -DskipTests compile` 确认通过
- [ ] **Step 1.4** Commit
  ```bash
  git add mock-server/src/main/java/com/buyansong/imserver/friend/FriendRequestStore.java
  git commit -m "feat(server): add FriendRequestStore with JDBC persistence"
  ```

---

## 任务 2：Mock Server 端 — Service + 业务校验

**Files:**
- Create: `mock-server/src/main/java/com/buyansong/imserver/friend/FriendRequestService.java`

- [ ] **Step 2.1** 写一个 JUnit 测试 `FriendRequestServiceTest.java`（mock 或用真实 `mock-im-friend-requests.sqlite` 临时文件）：
  - `createRequest(self, target, msg)`: 正常路径返回 `requestId`
  - 自申请 → `4002`
  - target 不存在 → `4001`
  - 已是好友（`FriendStore.friendsOf(self).contains(target)`）→ `4003`
  - 已存在 `from=self, to=target, status=0` 的 pending → `4004`
- [ ] **Step 2.2** 跑测试看红
  - `mvn -q -Dtest=FriendRequestServiceTest test`
  - 预期：编译失败或测试失败
- [ ] **Step 2.3** 实现 `FriendRequestService`：
  ```java
  public class FriendRequestService {
      private final FriendRequestStore store;
      private final FriendStore friendStore;          // 复用
      private final UserStore userStore;              // 用于 "用户不存在" 校验

      public Result create(String fromUserId, String toUserId, String message) { ... }
      public Result accept(String requestId, String operatorUserId) {
          // 1) request 必须存在且 to=operator 且 status=0
          // 2) store.updateStatus(..., 1)
          // 3) friendStore.addMutualFriendship(from, to, now)  ← 复用既有方法
      }
      public Result reject(String requestId, String operatorUserId) { ... }
      public List<FriendRequestRow> listAllForUser(String userId) { ... }
  }
  ```
- [ ] **Step 2.4** 让 `mvn -q -Dtest=FriendRequestServiceTest test` 全绿
- [ ] **Step 2.5** Commit
  ```bash
  git add mock-server/...
  git commit -m "feat(server): FriendRequestService with create/accept/reject + validation"
  ```

---

## 任务 3：Mock Server 端 — HTTP 路由

**Files:**
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java`

- [ ] **Step 3.1** 注入 `FriendRequestService`（构造器或者 setter）；`MockImServer` 启动时初始化并连接 `store` + `userStore` + `friendStore`
- [ ] **Step 3.2** 在 `HttpAuthHandler` 添加路由：
  - `GET /friend-requests/me` → `service.listAllForUser(userId)`，按 `fromUserId == userId` 拆成 `outgoing/incoming` 写入 JSON
  - `POST /friend-requests` → 读 body 解析 `toUserId, message`，调 `service.create(...)`
  - `POST /friend-requests/{id}/accept` → `service.accept(...)`
  - `POST /friend-requests/{id}/reject` → `service.reject(...)`
  - `GET /users/search?userId=xxx` → 精确查 `userStore.findById(...)`，命中返回 `UserProfile` JSON，否则 `4001`
- [ ] **Step 3.3** 手动用 `curl` 验证（在 mock-server 跑起来后）：
  - `curl -H "Authorization: Bearer <token>" http://127.0.0.1:8080/friend-requests/me` → `{code:0, data:{incoming:[], outgoing:[]}}`
  - `curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"toUserId":"15000000001","message":"hi"}' http://127.0.0.1:8080/friend-requests` → 200 + `requestId`
  - 重复发同样请求 → 4004
- [ ] **Step 3.4** Commit
  ```bash
  git add mock-server/...
  git commit -m "feat(server): wire /friend-requests + /users/search HTTP routes"
  ```

---

## 任务 4：客户端 — Domain Model + DAO

**Files:**
- Create: `app/src/main/java/com/buyansong/im/storage/FriendRequestDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`

- [ ] **Step 4.1** 写 `FriendRequestDaoTest.kt`，覆盖 `InMemoryFriendRequestDao` 的全接口
- [ ] **Step 4.2** 跑测试看红 → `./gradlew :app:testDebugUnitTest --tests FriendRequestDaoTest`
- [ ] **Step 4.3** 实现 `FriendRequest`、`FriendRequestDirection`、`FriendRequestStatus`、`FriendRequestDao` 接口、`InMemoryFriendRequestDao`
- [ ] **Step 4.4** 测试转绿
- [ ] **Step 4.5** 在 `ImDatabaseHelper`：
  - `DATABASE_VERSION = 11`
  - `onCreate` 加 `createFriendRequestsTable(db)`（建表 SQL 见 SPEC §3.1）
  - `onUpgrade` 加 `DROP TABLE IF EXISTS friend_requests;`
- [ ] **Step 4.6** 实现 `AndroidFriendRequestDao`（沿用 `AndroidFriendContactDao` 的写法，逐行 `Cursor` → `FriendRequest`）
- [ ] **Step 4.7** 跑全量 `:app:testDebugUnitTest`，确认没有回归
- [ ] **Step 4.8** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/storage/...
  git commit -m "feat(client): add FriendRequest domain model + DAO + SQLite table"
  ```

---

## 任务 5：客户端 — API 三件套

**Files:**
- Create: `app/src/main/java/com/buyansong/im/friends/FriendRequestApi.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/OkHttpFriendRequestApi.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/FriendRequestJsonParser.kt`

- [ ] **Step 5.1** 写 `FriendRequestJsonParserTest.kt`：
  - 解析 `incoming` / `outgoing` 数组（各一条）
  - 解析 `code != 0` → `Failure`
  - 解析 `4004` 错误码带 message
- [ ] **Step 5.2** 跑测试看红
- [ ] **Step 5.3** 实现：
  - `FriendRequestApi` 接口：`listMine`、`create`、`accept`、`reject`
  - `OkHttpFriendRequestApi`（参考 `OkHttpContactApi`，所有 4 个方法 `withContext(Dispatchers.IO)` + `Authorization: Bearer`）
  - `FriendRequestJsonParser`（与 `ContactJsonParser` 同样的 `optionalString/Int/Long/Array/Object` helper 私有扩展）
  - sealed `FriendRequestResult`：`Success`、`Failure(message)`、`Duplicate`、`SelfTarget`、`AlreadyFriend`（这几个细分用 `data class` 携带 message 即可，不必枚举所有情况）
- [ ] **Step 5.4** 测试全绿
- [ ] **Step 5.5** 同样的模式加 `UserSearchApi` + `OkHttpUserSearchApi` + `UserSearchJsonParser`（或者合并到 `FriendRequestApi` 一文件里，按 `GroupApi` 既有做法）
- [ ] **Step 5.6** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/friends/...
  git commit -m "feat(client): add friend request + user search API layer"
  ```

---

## 任务 6：客户端 — Repository

**Files:**
- Create: `app/src/main/java/com/buyansong/im/friends/FriendRequestRepository.kt`

- [ ] **Step 6.1** 写 `FriendRequestRepositoryTest.kt`：
  - `refresh()` 成功 → 调用 `dao.replaceForOwner(...)` 一次
  - `refresh()` 失败 → 仍然返回 cache，不抛异常
  - `create(toUserId, msg)`：成功 → 触发 `dao.upsert` 写入新 outgoing；返回 `Success`
  - `create()` 失败（`Failure` / `Duplicate` / `SelfTarget` / `AlreadyFriend`）→ 不写库，返回原 result
  - `accept(requestId)` 成功 → 写回 `status = ACCEPTED`；并触发 `ContactRepository.refreshFriends` 拉一次（或者提示 VM 自己触发）
  - `reject(requestId)` 成功 → 写回 `status = REJECTED`
- [ ] **Step 6.2** 跑测试看红
- [ ] **Step 6.3** 实现 `FriendRequestRepository(api, dao, ownerUserId, contactRepository)`：
  ```kotlin
  class FriendRequestRepository(
      private val api: FriendRequestApi,
      private val dao: FriendRequestDao,
      private val ownerUserId: String,
      private val contactRepository: ContactRepository? = null,  // 用于 accept 后刷新好友列表
      private val dispatcher: CoroutineDispatcher = Dispatchers.IO
  ) {
      fun cached(): List<FriendRequest> = dao.listForOwner(ownerUserId)
      suspend fun refresh(accessToken: String): Result<Unit> { ... }
      suspend fun create(accessToken: String, toUserId: String, message: String?): FriendRequestResult { ... }
      suspend fun accept(accessToken: String, requestId: String): FriendRequestResult { ... }
      suspend fun reject(accessToken: String, requestId: String): FriendRequestResult { ... }
      fun unreadIncomingCount(): Int = dao.unreadIncomingCount(ownerUserId)
  }
  ```
- [ ] **Step 6.4** 测试全绿
- [ ] **Step 6.5** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/friends/FriendRequestRepository.kt
  git test files
  git commit -m "feat(client): add FriendRequestRepository with cache + accept/reject"
  ```

---

## 任务 7：客户端 — 新路由 + MainActivity 注入

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt`
- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`

- [ ] **Step 7.1** 在 `SelfHostedImRoute` 加：
  ```kotlin
  data object NewFriends : SelfHostedImRoute("new-friends")
  data object AddFriend  : SelfHostedImRoute("add-friend")
  ```
- [ ] **Step 7.2** 在 `MainActivity` 的 `AccountScopedRepositories` 里加：
  - `val friendRequestApi = OkHttpFriendRequestApi(httpBaseUrl)`
  - `val friendRequestDao = AndroidFriendRequestDao(database)`
  - `val friendRequestRepository = FriendRequestRepository(friendRequestApi, friendRequestDao, ownerUserId = session.userId, contactRepository)`
- [ ] **Step 7.3** 在 `MainActivity` 的 `AuthenticatedImNavHost`：
  - 声明 `val newFriendsViewModel = remember(session.userId) { NewFriendsViewModel(...) }`
  - 声明 `val addFriendViewModel = remember(session.userId) { AddFriendViewModel(...) }`
  - 注入 `onOpenNewFriends = { navController.navigate(SelfHostedImRoute.NewFriends.route) { launchSingleTop = true } }` 到 `ContactListScreen`
  - 注入 `onAddFriend = { navController.navigate(SelfHostedImRoute.AddFriend.route) { launchSingleTop = true } }` 到 `ContactListScreen` 和 `ConversationListScreen`
  - 加两个 `composable(SelfHostedImRoute.NewFriends.route) { ... }` 和 `composable(SelfHostedImRoute.AddFriend.route) { ... }` 块
- [ ] **Step 7.4** `MainActivity` 现有 `ContactListScreen` 调用点（`composable(SelfHostedImRoute.Contacts.route)`）补上 `onOpenNewFriends` / `onAddFriend` 参数
- [ ] **Step 7.5** `ConversationListScreen` 调用点也补上 `onAddFriend`（具体看现有签名，可能需要新增参数）
- [ ] **Step 7.6** 编译通过（还没接 VM 实现，先用 `onOpenNewFriends = {}` 占位也行，但本任务的目标是接到 7.3 里的新 composable）
- [ ] **Step 7.7** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/SelfHostedImRoute.kt \
          app/src/main/java/com/buyansong/im/MainActivity.kt
  git commit -m "feat(client): register NewFriends + AddFriend routes, wire VM factories"
  ```

---

## 任务 8：客户端 — `NewFriendsViewModel` + 列表页 + DisplayPolicy

**Files:**
- Create: `app/src/main/java/com/buyansong/im/friends/NewFriendsViewModel.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/NewFriendsScreen.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/NewFriendsDisplayPolicy.kt`

- [ ] **Step 8.1** 写 `NewFriendsDisplayPolicyTest.kt`（assert 关键 `const val` 非空）
- [ ] **Step 8.2** 实现 `NewFriendsDisplayPolicy`（所有 zh-CN 字符串，参考 `ContactProfileDisplayPolicy` 风格）
- [ ] **Step 8.3** 写 `NewFriendsViewModelTest.kt`：
  - `start()` → 拉 cache → emit；接着 `refresh()` → API 成功 → `incoming/outgoing` 更新
  - `refresh()` 失败 → `errorMessage` 非空，cache 仍在
  - `accept(requestId)` 成功 → 该行从 `incoming` 移除（或变为 `ACCEPTED` 灰条，二选一，见 SPEC §6.1）
  - `accept()` 失败 → `errorMessage` 非空，该行保留
  - `reject(requestId)` 同上
- [ ] **Step 8.4** 跑测试看红
- [ ] **Step 8.5** 实现 `NewFriendsViewModel`（构造函数签名：session, repository, validSessionProvider, scope, dispatcher）
- [ ] **Step 8.6** 测试全绿
- [ ] **Step 8.7** 实现 `NewFriendsScreen` Composable（参考 `JoinedGroupsScreen` 结构）：
  - 参数 `(viewModel, state, onBack)`
  - 顶栏 `ByteImTopBar(title = NewFriendsDisplayPolicy.TITLE, onBack = onBack)`
  - 主体 `LazyColumn`，两个 section，参考 SPEC §6.1
  - `LaunchedEffect(viewModel) { viewModel.start() }`
  - `DisposableEffect(viewModel) { onDispose { viewModel.stop() } }`
  - `val state by viewModel.state.collectAsState()`
- [ ] **Step 8.8** 编译 + assembleDebug 确认 UI 渲染无 crash
  - `./gradlew :app:assembleDebug`
- [ ] **Step 8.9** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/friends/...
  git commit -m "feat(client): NewFriends list screen with accept/reject"
  ```

---

## 任务 9：客户端 — `AddFriendViewModel` + 搜索页 + 验证消息弹层

**Files:**
- Create: `app/src/main/java/com/buyansong/im/friends/AddFriendViewModel.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/AddFriendScreen.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/AddFriendVerifySheet.kt`
- Create: `app/src/main/java/com/buyansong/im/friends/AddFriendDisplayPolicy.kt`

- [ ] **Step 9.1** 写 `AddFriendDisplayPolicyTest.kt`
- [ ] **Step 9.2** 实现 `AddFriendDisplayPolicy`
- [ ] **Step 9.3** 写 `AddFriendViewModelTest.kt`：
  - `onQueryChange("15000")` → 状态更新
  - `search()` → API 命中 → `searchResult` 非空，`searchMissed = false`
  - `search()` → API 返回 `4001` → `searchResult = null`，`searchMissed = true`
  - `openVerifySheet()` → `showVerifySheet = true`
  - `closeVerifySheet()` → 重置 `verifyMessage`，`showVerifySheet = false`
  - `send()` 成功 → `showVerifySheet = false`，`query` 清空，`sendSucceededFor = peerUserId`，并触发 `friendRequestRepository.create(...)` 写库
  - `send()` 失败 → `sendError = message`，sheet 保持打开
- [ ] **Step 9.4** 跑测试看红
- [ ] **Step 9.5** 实现 `AddFriendViewModel`（签名：session, friendRequestRepository, userSearchApi, validSessionProvider, scope, dispatcher）
- [ ] **Step 9.6** 测试全绿
- [ ] **Step 9.7** 实现 `AddFriendScreen` Composable（参考 SPEC §6.2）：
  - 参数 `(viewModel, state, onBack)`
  - 顶栏 + 输入框 + 搜索结果
  - `BackHandler(enabled = state.showVerifySheet) { viewModel.closeVerifySheet() }`
- [ ] **Step 9.8** 实现 `AddFriendVerifySheet`（参考 `GroupReadDetailSheet` 用 `ModalBottomSheet`，`skipPartiallyExpanded = true`）
- [ ] **Step 9.9** 编译 + assembleDebug 通过
- [ ] **Step 9.10** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/friends/AddFriend*
  git commit -m "feat(client): AddFriend screen with search + verify-message sheet"
  ```

---

## 任务 10：联系人页 — 接入"新的朋友"和"添加朋友" + 红点 badge

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt`
- Modify: `app/src/main/java/com/buyansong/im/ui/ConversationCreateMenu.kt`

- [ ] **Step 10.1** `ContactListScreen(...)` 签名加 `onOpenNewFriends: () -> Unit` 和 `onAddFriend: () -> Unit`
- [ ] **Step 10.2** `ContactEntryBlock` 加 `onOpenNewFriends` 参数，把它接到 `ContactEntryItem(title = "新的朋友", onClick = onOpenNewFriends)`
- [ ] **Step 10.3** 改 `ContactEntryItem` 支持右侧红点：参数 `badge: Boolean = false`，如果为 true，在 `Row` 末尾渲染 `Box(Modifier.size(8.dp).background(ByteImColors.BadgeRed, CircleShape))`
- [ ] **Step 10.4** "新的朋友" 行 `badge = state.unreadIncoming > 0`
- [ ] **Step 10.5** `ContactsTopBar`（`ContactListScreen` 里的私有 composable）的 `ConversationCreateMenu` 调用加 `onAddFriend = onAddFriend` 参数
- [ ] **Step 10.6** `ConversationCreateMenu` 签名加 `onAddFriend: () -> Unit`，"添加朋友" `DropdownMenuItem.onClick` 调 `onAddFriend()`
- [ ] **Step 10.7** 在 `ContactListUiState`（在 `ContactListViewModel`）加 `unreadIncoming: Int = 0`；`ContactListViewModel.start()` 之后开个协程订阅 `friendRequestRepository.unreadIncomingCount`（用 `produceState` 也可以，更简单：每次 refresh 时同步值）
- [ ] **Step 10.8** 跑 `:app:testDebugUnitTest`，看 `ContactListViewModelTest` 是否要新增 case
- [ ] **Step 10.9** assembleDebug
- [ ] **Step 10.10** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/contacts/ContactListScreen.kt \
          app/src/main/java/com/buyansong/im/ui/ConversationCreateMenu.kt \
          app/src/main/java/com/buyansong/im/contacts/ContactListViewModel.kt
  git commit -m "feat(client): wire NewFriends + AddFriend entry points + unread badge"
  ```

---

## 任务 11：会话列表 — 接入"添加朋友"

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/conversation/ConversationListScreen.kt`（路径以仓库实际为准）

- [ ] **Step 11.1** 给 `ConversationListScreen` 的 `ConversationCreateMenu` 调用加 `onAddFriend`
- [ ] **Step 11.2** 该 Composable 签名补 `onAddFriend: () -> Unit`
- [ ] **Step 11.3** `MainActivity` 里 `composable(SelfHostedImRoute.Conversations.route)` 注入 `onAddFriend = { navController.navigate(SelfHostedImRoute.AddFriend.route) }`
- [ ] **Step 11.4** 编译通过
- [ ] **Step 11.5** Commit
  ```bash
  git add app/src/main/java/com/buyansong/im/conversation/... \
          app/src/main/java/com/buyansong/im/MainActivity.kt
  git commit -m "feat(client): wire AddFriend entry from Messages tab +"
  ```

---

## 任务 12：端到端验证

- [ ] **Step 12.1** 启动 mock server（`./start-mock-server.sh`）
- [ ] **Step 12.2** 启动两个客户端（用不同的 hub 账号，例如 `15000000000` 和 `15000000001`），登录
- [ ] **Step 12.3** 在 `15000000000` 端：
  - 通讯录 tab → 看到 "新的朋友" 行（无红点）
  - 顶栏 `+` → 点 "添加朋友" → 输入 `15000000001` → 搜索 → 命中 → 点 "添加" → 填 "hi" → 发送 → toast 提示成功
  - 通讯录 tab → "新的朋友" 仍是 0 标记（outgoing 不计 unread）
  - 切到 "新的朋友" 列表页 → 看到自己发的那条 outgoing，状态"待确认"
- [ ] **Step 12.4** 在 `15000000001` 端：
  - 通讯录 tab → "新的朋友" 行**出现红点**
  - 点进去 → 看到一条 incoming → 点 "接受" → 列表里该行消失（或变灰，按实现）
  - 切到通讯录 tab 主列表 → `15000000000` 已出现在好友列表中
  - 给 `15000000000` 发个 "你好" 消息 → 验证两端的 `friend_contacts` + 通讯录刷新逻辑没回归
- [ ] **Step 12.5** 异常路径：
  - 在 `15000000000` 端搜自己 → "未找到该用户"（或 `4002` 错误回显，参考 `FriendSearchResult` 决定）
  - 再向 `15000000001` 发一次申请 → 服务端 `4004`，弹层显示"已有待处理申请"
  - 重复 step 12.3 后再向 `15000000001` 发申请 → `4003` 提示"已经是好友"
- [ ] **Step 12.6** 跑全量单测
  - `./gradlew :app:testDebugUnitTest`
  - `cd mock-server && mvn -q test`
  - 预期：全部 PASS

---

## 任务 13：文档 + 状态更新

- [ ] **Step 13.1** 创建 `docs/status/2026-06-09-add-friend.md`，按 `docs/status/B1-auth.md` 的格式记录：功能范围、文件清单、API 端点、测试覆盖、已知限制
- [ ] **Step 13.2** 在 `docs/bg/DEVELOPMENT_STATUS.md` 把"添加好友"加到功能索引
- [ ] **Step 13.3** Commit
  ```bash
  git add docs/
  git commit -m "docs: add status doc for Add Friend feature"
  ```

---

## 验证（Verification）

执行完毕后按以下顺序自检：

1. `./gradlew :app:testDebugUnitTest` → 全绿
2. `cd mock-server && mvn -q test` → 全绿
3. `./gradlew :app:assembleDebug` → 成功
4. 端到端：两个 hub 账号登录，按任务 12 的清单走一遍
5. 检查 `git status` 干净，提交记录清晰（feature/add-friend 分支上 11~13 个 commit）
6. 提交 PR：`gh pr create --base rename-redesign-ui --title "feat: add friend request flow"`

---

## Self-Review Checklist

- [x] SPEC 覆盖：
  - 通讯录"新的朋友"行 → 列表页（incoming + outgoing）✓ 任务 8
  - 右上 `+` → 添加朋友页（搜索 + 验证消息弹层）✓ 任务 9
  - incoming 支持 accept/reject ✓ 任务 6/8
  - outgoing 只读 + 状态显示 ✓ 任务 8
  - 服务端校验重复/自申请/已是好友 ✓ 任务 2/3
- [x] 无 TBD/TODO：所有步骤都给出具体文件名 / 命令 / 代码片段
- [x] 类型一致：`FriendRequest`、`FriendRequestStatus`、`FriendRequestDirection`、`FriendRequestRepository`、`FriendRequestResult` 在所有任务里命名一致
- [x] 既有约定全部沿用：API 三件套、`InMemory*Dao` + `Android*Dao`、`produceState`/`MutableStateFlow`、`ByteImTopBar`、`ModalBottomSheet`、中文 `*DisplayPolicy` 常量
- [x] 数据库版本 10 → 11，onUpgrade drop-all 已有先例
- [x] 测试在前 / 实现在后 / 跑测试看红 → 写代码 → 转绿 的节奏
- [x] 频繁 commit
