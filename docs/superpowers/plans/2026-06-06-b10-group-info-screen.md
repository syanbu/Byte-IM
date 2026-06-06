# B10 群聊成员展示 + 群聊名称修改 — 设计方案

> 状态：仅设计文档，未开始实现。
> 关联：[status/B10-group-chat-and-mention.md](../../status/B10-group-chat-and-mention.md) 中"群成员完整 UI 未闭环"的待办项；数据层、协议层（`GroupInfo` / `GroupMember` 模型、`GET /groups/{groupId}/members`、`PATCH /groups/{groupId}`）已经全部就绪，本次只动 UI/状态层。

## Context

群聊会话的右上角目前只有一个"..."按钮，点开后是一个 `AlertDialog` 用来修改群名称，详见 [ChatScreen.kt:103, 205-218, 387-417](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L103)。`docs/bg/ProjectBg.md` 与 [status/B10-group-chat-and-mention.md](../../status/B10-group-chat-and-mention.md) 中明确指出："B10 群成员完整 UI、成员同步的长期体验、群管理能力仍未作为完整产品闭环完成"。本次任务就是补齐这块体验：**用一个群聊信息页取代/扩展当前的"..."入口，集中展示群成员与可点击修改的群聊名称**。

数据层已经是完整的（`GroupInfo`、`GroupMember` 持久化在 `groups` / `group_members` 两张 SQLite 表中；`GET /groups/{groupId}/members` 与 `PATCH /groups/{groupId}` 都已经接好），本次改动**只动 UI/状态层，不动后端协议**。

## 目标

- 进入群聊后，右上角不再是"..."文字按钮，改为**群聊信息入口图标**（`Icons.Filled.Info` 或 `Icons.Filled.Group`，白底深色 `ByteImColors.TextPrimary`）。
- 群聊信息页（`GroupInfoScreen`）为一个新的全屏路由，承载两段内容：
  1. **成员网格**：`LazyVerticalGrid` 每行 5 列，每格 = 圆形头像（48dp）+ 昵称（1 行省略号）。点击任一成员跳转 `ContactProfileScreen`（与 `Contacts` 标签的交互一致）。
  2. **群聊名称卡片**（在网格下方）：复用 `ByteImListSurface` 的卡片样式，仿照 `MeScreen.kt:557-586` 的 `ProfileNameRow` 形态 —— 左侧"群聊名称"标签（weight 0.35f），右侧群名（weight 0.65f，`TextAlign.End`，`TextSecondary`），右侧加 `ChevronRightIcon`。点击 → 弹起 `AlertDialog`（复用 [ChatScreen.kt:387-417](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L387-L417) 的形态），保存后调 `groupRepository.renameGroup(...)`。
- 现有的"修改群名称"内嵌弹窗**从 ChatScreen 中移除**，避免两套入口。
- 不在本次范围内：群主/管理员标记、踢人、邀请、退出群、群头像上传。

## 复用的既有组件 / 数据

| 用途 | 路径 | 备注 |
|---|---|---|
| `AvatarImage(avatarUrl, displayName, modifier)` | [ui/AvatarImage.kt](../../app/src/main/java/com/codex/im/ui/AvatarImage.kt) | 已支持 `isGroup = true` 时的"群"字占位 |
| `ByteImTopBar` / `ByteImListSurface` / `ByteImDimensions` / `ByteImColors` | [ui/ByteImUi.kt](../../app/src/main/java/com/codex/im/ui/ByteImUi.kt) | 直接复用，不新增 token |
| `GroupInfo` / `GroupMember` / `GroupMemberRole` | [storage/StorageModels.kt:70-87,108-111](../../app/src/main/java/com/codex/im/storage/StorageModels.kt#L70) | 数据模型已存在 |
| `GroupRepository.syncMembers(accessToken, groupId)` | [group/GroupRepository.kt:80-89](../../app/src/main/java/com/codex/im/group/GroupRepository.kt#L80) | 一次调用同时刷新群元信息 + 成员列表到本地 |
| `GroupRepository.localMembers(groupId)` | [group/GroupRepository.kt:91](../../app/src/main/java/com/codex/im/group/GroupRepository.kt#L91) | 缓存读取，UI 首屏立即可见 |
| `GroupRepository.renameGroup(accessToken, groupId, name)` | [group/GroupRepository.kt:56-68](../../app/src/main/java/com/codex/im/group/GroupRepository.kt#L56) | 已做 trim/空名校验、持久化 `GroupInfo`、更新 `Conversation.peerName/title/avatarUrl` |
| 网格容器参考（列数改 5） | [chat/AlbumPickerScreen.kt:78-91](../../app/src/main/java/com/codex/im/chat/AlbumPickerScreen.kt#L78) | 唯一一个 `LazyVerticalGrid` |
| `AlertDialog` 改名模式 | [chat/ChatScreen.kt:387-417](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L387) | 直接复用 text/confirmButton/dismissButton 结构 |
| `ContactProfileScreen` 入口路由与回调风格 | [contacts/ContactProfileScreen.kt:40-79](../../app/src/main/java/com/codex/im/contacts/ContactProfileScreen.kt#L40) | 新页面的全屏骨架可一一对应 |
| `MeScreen.kt:557-586` 的 `ProfileNameRow` 卡片行 | [profile/MeScreen.kt:557-586](../../app/src/main/java/com/codex/im/profile/MeScreen.kt#L557) | "群聊名称"行直接照抄样式 |
| 字符串集中管理范例 | [profile/MeDisplayPolicy.kt](../../app/src/main/java/com/codex/im/profile/MeDisplayPolicy.kt) | 新建 `GroupInfoDisplayPolicy.kt` 时与之并列 |
| 顶部后按钮优先级链范例 | [profile/MeBackPolicy.kt](../../app/src/main/java/com/codex/im/profile/MeBackPolicy.kt) | `GroupInfoScreen` 简单，只需单层 back，无需新建 policy |

## 改动清单

### 新增

1. **`app/src/main/java/com/codex/im/group/GroupInfoDisplayPolicy.kt`**
   集中管理中文文案：`topBarTitle = "群聊信息"`、`groupNameRowLabel = "群聊名称"`、`renameDialogTitle = "修改群名称"`、`saveLabel = "保存"`、`cancelLabel = "取消"`、`emptyHint = "该群暂无成员"`、`errorEmptyGroupName = "群名称不能为空"`。

2. **`app/src/main/java/com/codex/im/group/GroupInfoViewModel.kt`**
   镜像 `ContactProfileViewModel` 的形态。
   - 状态 `data class GroupInfoUiState(val group: GroupInfo?, val members: List<GroupMember>, val isLoading: Boolean, val errorMessage: String?, val isSaving: Boolean, val showRenameDialog: Boolean, val draftGroupName: String)`。
   - 构造参数：`session: AuthSession`, `groupId: String`, `groupRepository: GroupRepository`, `validSessionProvider: ValidSessionProvider`, `coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO`。
   - 方法：
     - `start()` / `stop()` —— 生命周期，与 `ContactProfileViewModel` 对齐（用于从 `MainActivity` 的 `composable` 块中 `LaunchedEffect` 调用）。
     - `load()` —— 优先 `groupRepository.localMembers(groupId)` 立即填 UI，再异步 `groupRepository.syncMembers(token, groupId)` 刷新；同时通过 `groupRepository` 的 `localMembers` 旁的 `groupDao.findGroup(groupId)` 读取 `GroupInfo`（或 `syncMembers` 返回的 `result.group`）。
     - `startRename()` / `cancelRename()` / `updateDraftGroupName(name: String)`。
     - `confirmRename()` —— trim 校验 → `groupRepository.renameGroup(token, groupId, trimmed)` → 成功时把新 `GroupInfo` 写回 state 并关闭对话框。
   - 错误处理：空名 → `errorMessage = errorEmptyGroupName`；HTTP 失败 → `errorMessage = result.message`。

3. **`app/src/main/java/com/codex/im/group/GroupInfoScreen.kt`**
   全屏 Compose 页面，结构与 `ContactProfileScreen` 对称：
   ```
   Scaffold(containerColor = AppBackground) {
     Column {
       ByteImTopBar(title = "群聊信息", onBack = onBack)
       Spacer(12.dp)
       GroupInfoBody(state, onRetry, onOpenUserProfile, onClickGroupName)
     }
   }
   ```
   - **成员网格区**：`LazyVerticalGrid(columns = GridCells.Fixed(5), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp))`。每个格子用 `Modifier.aspectRatio(1f).clickable { onOpenUserProfile(member.userId) }`：
     ```kotlin
     Column(horizontalAlignment = Alignment.CenterHorizontally) {
       AvatarImage(avatarUrl = member.avatarUrl, displayName = member.displayName, modifier = Modifier.size(48.dp))
       Spacer(6.dp)
       Text(member.displayName, style = bodySmall, color = TextPrimary, maxLines = 1, overflow = Ellipsis, textAlign = TextAlign.Center)
     }
     ```
   - **空态**：`members.isEmpty() && !isLoading` 时显示一段居中 `Text`（"该群暂无成员"，或与 `ContactProfileFailureBlock` 同款的失败态 `TextButton("重试")`）。
   - **群聊名称卡片**（在网格下方，包裹在 `ByteImListSurface` 中）：
     ```kotlin
     Row(
       modifier = Modifier
         .fillMaxWidth()
         .height(ByteImDimensions.ListItemHeight)
         .clickable(onClick = onClickGroupName)
         .padding(horizontal = ByteImDimensions.EdgePadding),
       verticalAlignment = Alignment.CenterVertically,
       horizontalArrangement = Arrangement.spacedBy(12.dp)
     ) {
       Text("群聊名称", modifier = Modifier.weight(0.35f), style = bodyLarge, color = TextPrimary)
       Text(group.name, modifier = Modifier.weight(0.65f), style = bodyLarge, color = TextSecondary, textAlign = TextAlign.End, maxLines = 1, overflow = Ellipsis)
       Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
     }
     ```
   - **改名 `AlertDialog`**（仅在 `state.showRenameDialog` 为真时渲染，与 [ChatScreen.kt:387-417](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L387) 形态完全一致）：`TextField(value = state.draftGroupName, onValueChange = ..., singleLine = true)`，confirmButton → `viewModel.confirmRename()`，dismissButton → `viewModel.cancelRename()`。保存期间 `confirmButton` 显示"保存中"且禁用。
   - 入口签名：
     ```kotlin
     @Composable
     fun GroupInfoScreen(
       viewModel: GroupInfoViewModel,
       state: GroupInfoUiState,
       modifier: Modifier = Modifier,
       onBack: () -> Unit,
       onOpenUserProfile: (userId: String) -> Unit
     )
     ```

### 修改

4. **`app/src/main/java/com/codex/im/SelfHostedImRoute.kt`**
   新增 `data object GroupInfo : SelfHostedImRoute("group-info/{groupId}")`，仿照 `ContactProfile` 给出 `GROUP_ID_ARG = "groupId"`、`pattern`、`createRoute(groupId: String): String?` 工具方法。

5. **`app/src/main/java/com/codex/im/MainActivity.kt`**
   - 导入 `GroupInfoScreen` / `GroupInfoViewModel` / `GroupInfoDisplayPolicy`。
   - 在 `NavHost` 中新增 `composable(route = SelfHostedImRoute.GroupInfo.pattern) { entry -> ... }` 块（参考 [MainActivity.kt:580-606](../../app/src/main/java/com/codex/im/MainActivity.kt#L580) `ContactProfile` 的写法）：
     - 从 `entry.arguments` 取 `groupId`，空值时 `LaunchedEffect(Unit) { navController.popBackStack() }`。
     - 用 `remember(session.userId, groupId) { GroupInfoViewModel(...) }` 构造 VM。
     - 渲染 `GroupInfoScreen(...)`，`onBack = { navController.popBackStack() }`，`onOpenUserProfile` 与 Chat 同款的逻辑：`userId == session.userId` 时导航到 `Me`，否则 `SelfHostedImRoute.ContactProfile.createRoute(userId)`。
   - 修改 `composable(SelfHostedImRoute.Chat.pattern)` 块（[MainActivity.kt:608-643](../../app/src/main/java/com/codex/im/MainActivity.kt#L608)）：在 `ChatScreen(...)` 调用上新增参数 `onOpenGroupInfo = { gid -> SelfHostedImRoute.GroupInfo.createRoute(gid)?.let(navController::navigate) }`。

6. **`app/src/main/java/com/codex/im/chat/ChatScreen.kt`**
   - 顶部 action 块（[ChatScreen.kt:201-218](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L201)）：
     - 把 `Text("...", ...)` 改为 `IconButton(onClick = onOpenGroupInfo) { Icon(Icons.Filled.Info, contentDescription = "群聊信息", tint = ByteImColors.TextPrimary) }`。
   - 函数签名追加 `onOpenGroupInfo: () -> Unit`。
   - **删除** `var showGroupRename by remember { mutableStateOf(false) }`（[ChatScreen.kt:103](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L103)）、`var groupNameDraft by remember { mutableStateOf(state.peerName) }`（[ChatScreen.kt:108](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L108)）、`LaunchedEffect(state.peerName) { ... }`（[ChatScreen.kt:172-176](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L172)）、以及整段 `if (showGroupRename) { AlertDialog(...) }`（[ChatScreen.kt:387-417](../../app/src/main/java/com/codex/im/chat/ChatScreen.kt#L387)）。`ChatViewModel.renameGroup(...)` 保留不动（仍可被 `GroupInfoViewModel.confirmRename` 之外的其他场景复用，未来如要做"群主在成员页改名"也省得再接一次）。

### 字符串资源

若 `ic_chevron_right` 与 `R.drawable.ic_chevron_right` 已在项目其它页面使用则直接复用（`MeScreen.kt`、`ContactProfileScreen.kt` 等都用过），不需要新增 drawable。
"群聊信息" / "群聊名称" / "修改群名称" / "保存" / "取消" / "保存中" 等所有 UI 文案放到 `GroupInfoDisplayPolicy.kt` 中作为 `const val`，与 `MeDisplayPolicy` 同款约定。

## 验证

### 单元 / 静态
- `mvn -q compile`（或 Android Studio 的 Gradle sync + `assembleDebug`）确保编译通过。
- 在 `GroupInfoViewModelTest`（mock `GroupRepository` / `ValidSessionProvider`）中覆盖：空名拒绝、成功改名刷新 state、网络失败显示 `errorMessage`、`load()` 用 `syncMembers` 刷新并回填 `members` 与 `group`。项目其余 viewmodel 已有类似测试模式（`ContactProfileViewModelTest` 等），遵循其风格。

### 端到端（手动 / `mock-test`）
1. 启动 mock server：`cd mock-server && mvn -q exec:java`。
2. 用 `mock-test/seed_local_messages.py` 创建一个有 6+ 成员的群（脚本当前仅做消息播种，必要时临时往 `groups` / `group_members` 注入）。
3. 在 Android app 中登录 → 进入该群聊 → 验证：
   - 右上角是"群聊信息"图标（不再是"..."），点击后进入 `GroupInfoScreen`。
   - 网格正确显示所有成员，每行 5 个，超过 5 个自动换行。头像能加载（来自 mock server 的 `avatar_url`），昵称 1 行省略。
   - 点击任一成员 → 跳到 `ContactProfileScreen`，再返回仍在 `GroupInfoScreen`。
   - 点击"群聊名称"卡片 → 弹起 `AlertDialog`，清空再点保存 → 显示"群名称不能为空"。
   - 输入新名称点保存 → 弹窗关闭 → 卡片右侧新名称立即生效；返回 ChatScreen → 顶栏标题也已是新名称（因为 `GroupRepository.renameGroup` 同步更新了 `Conversation` 行；如未生效，确认 `ChatViewModel.start()` 在 `composable` 重新进入时会重读 `conversation.title`）。
   - 后退键 / 顶栏返回箭头回到 `ChatScreen`，原消息流不受影响。
4. 边界：
   - 1 人群（仅自己）→ 网格只显示自己一格，群名仍可改。
   - 成员无头像 → 显示首字母占位（`AvatarPlaceholderPolicy` 已处理）。
   - 改名期间断网 → 弹窗"保存中"按钮禁用状态直到错误信息出现，错误信息以 `MessageToastPopup` 风格呈现（参考 `ConversationListViewModel` 的 error 模式，或仅在弹窗内 `Text` 显示）。

### 回归
- 退出群聊、再次进入同一个群聊 → 成员网格优先以本地缓存 `groupRepository.localMembers(...)` 渲染，避免空白闪烁。
- 单聊（`conversationId` 以 `single:` 开头）右上角**不应**出现新图标（沿用现有 `if (state.peerId.startsWith("group:"))` 的判定即可）。

## 不在本次范围内（明确划界）

- 群主 / 管理员的"群主"角标。
- 添加 / 移除成员、退出群、转让群、解散群。
- 群头像上传、群公告、二维码邀请。
- B10 文档中提到的"长期成员同步"（如 WebSocket 推送成员变更）—— 本次仅用现有的 `GET /groups/{groupId}/members` HTTP 拉取。
- 单元格的"@TA"长按菜单。
