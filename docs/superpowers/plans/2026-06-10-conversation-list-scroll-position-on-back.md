# Bug 修复：从聊天返回消息列表后，列表不显示最新消息

> 状态：方案待确认
> 日期：2026-06-10
> 核心问题：从单聊/群聊界面按 Back 返回消息列表时，列表滚动位置停留在离开时的旧位置，不会自动滚到最新消息所在位置，需要上划才能看到。

---

## 0. 根因分析

### 时序链路

1. 用户在消息列表页 → `snapshotFlow` 持续将 `LazyColumn` 滚动位置写入 `ConversationListUiState.firstVisibleItemIndex / firstVisibleItemScrollOffset`
2. 用户进入聊天 → `ConversationListScreen` 离开组合 → `viewModel.stop()` 取消 `conversationUpdates.collect` → 滚动位置冻结在最后保存的值
3. 用户在聊天中，新消息到达 → `MessageRepository.notifyConversationChanged()` 发射信号 → **但没有 collector**（`stop()` 已取消），ViewModel 的 `state.items` 不更新
4. 用户按 Back 返回消息列表 → `ConversationListScreen` 重新进入组合 → `viewModel.start()` → `refresh()` 加载最新数据 → `updateItems()` 更新 `items`
5. `rememberLazyListState` 创建新实例，使用 `state.firstVisibleItemIndex` 和 `state.firstVisibleItemScrollOffset` 作为初始值 → **滚动位置恢复到旧值**

### 核心矛盾

会话列表按 `lastMessageTime` 降序排列。新消息会导致会话排序变化（例如用户刚聊天的会话应该排到 index=0），但 `LazyColumn` 的滚动位置被恢复为离开时保存的位置（如 index=5），导致最新消息所在会话在视口上方不可见。

### 涉及文件

| 文件 | 作用 |
|---|---|
| `ConversationListScreen.kt` | 消息列表 UI，管理 `rememberLazyListState` 和 `snapshotFlow` 滚动位置追踪 |
| `ConversationListViewModel.kt` | 消息列表 ViewModel，管理 `UiState`（含 `items` + `firstVisibleItemIndex/Offset`）|
| `MessageRepository.kt` | 消息/会话数据仓库，`notifyConversationChanged()` 驱动列表刷新 |
| `ChatScreen.kt` | 聊天界面，`DisposableEffect.onDispose` 调用 `viewModel.stop()` |
| `MainActivity.kt` | 导航宿主，管理 ViewModel 实例生命周期 |

---

## 1. 修复方案

**核心思路**：从聊天返回消息列表时，如果数据发生变化（会话排序改变或有新消息），应将滚动位置重置到顶部（index=0, offset=0），让用户看到最新消息。

### 步骤 1 — `ConversationListUiState` 增加 `shouldResetScrollPosition` 标志

在 `ConversationListUiState` 中新增一个标志位：

```kotlin
data class ConversationListUiState(
    val items: List<ConversationListItem> = emptyList(),
    val connectionStatus: String = "未连接",
    val navigationTargetPeerId: String? = null,
    val navigationTargetConversationId: String? = null,
    val isLoadingMore: Boolean = false,
    val hasMoreConversations: Boolean = true,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val shouldResetScrollPosition: Boolean = false  // ← 新增
)
```

**Why**：需要一个从 ViewModel 到 UI 的单向信号，告诉 `ConversationListScreen` "这次数据刷新后需要将列表滚回顶部"。使用 Boolean 而不是 Event/Channel 是因为 Compose 侧需要能在重组时读取该状态。

### 步骤 2 — `ConversationListViewModel.start()` 在重新启动时设置重置标志

修改 `start()` 方法，在 `started == false` 分支（即从 `stop()` 后重新启动）中，刷新数据后设置 `shouldResetScrollPosition = true`：

```kotlin
fun start() {
    if (started) {
        scope.launch(dispatcher) {
            refresh()
        }
        return
    }
    started = true
    jobs += scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
        connection.states.collect { state ->
            mutableState.value = mutableState.value.copy(connectionStatus = state.toStatusText())
        }
    }
    jobs += scope.launch(dispatcher) {
        repository.conversationUpdates.collect {
            refresh()
        }
    }
    connectIfNeeded()
    jobs += scope.launch(dispatcher) {
        refresh()
        // 从 stop() 后重新启动：列表可能因新消息导致排序变化，
        // 需要重置滚动位置让用户看到最新消息
        mutableState.value = mutableState.value.copy(shouldResetScrollPosition = true)
    }
}
```

**Why**：只在 `start()` 的非 `started` 路径设置标志，因为此路径意味着用户从其他页面（聊天）返回。已 `started` 的路径（如 tab 切换回来）不触发重置——此时列表已在视口中并实时更新。

### 步骤 3 — `ConversationListScreen` 响应 `shouldResetScrollPosition`

在 `ConversationListScreen` 中添加 `LaunchedEffect` 监听 `shouldResetScrollPosition`：

```kotlin
val listState = rememberLazyListState(
    initialFirstVisibleItemIndex = state.firstVisibleItemIndex,
    initialFirstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
)

// 新增：响应滚动位置重置信号
LaunchedEffect(state.shouldResetScrollPosition) {
    if (state.shouldResetScrollPosition) {
        listState.scrollToItem(0, 0)
        viewModel.consumeScrollReset()
    }
}
```

**Why**：`LaunchedEffect` 的 key 是 `state.shouldResetScrollPosition`，当其从 `false` 变为 `true` 时触发。`scrollToItem(0, 0)` 是即时跳转（无动画），因为用户从聊天返回时期望立即看到最新消息。使用 `scrollToItem` 而不是 `animateScrollToItem` 避免滚动动画造成视觉干扰。

### 步骤 4 — `ConversationListViewModel` 新增 `consumeScrollReset()` 方法

```kotlin
fun consumeScrollReset() {
    mutableState.value = mutableState.value.copy(
        shouldResetScrollPosition = false,
        firstVisibleItemIndex = 0,
        firstVisibleItemScrollOffset = 0
    )
}
```

**Why**：消费重置信号，同时将 ViewModel 中保存的滚动位置同步归零，避免后续 `snapshotFlow` 的 `distinctUntilChanged` 误判（如果只归零 `shouldResetScrollPosition` 而不归零 index/offset，下次滚动时 `updateScrollPosition` 会把新位置写回，但 `rememberLazyListState` 不会被重新初始化，不会产生问题；但保持一致性更好）。

### 步骤 5 — `updateItems()` 在常规数据刷新时不再重置滚动位置

当前 `updateItems()` 的 `copy()` 不包含 `shouldResetScrollPosition`，所以不会改变其值。这一步无需修改代码，但需要确认：`refresh()` 中调用的 `updateItems()` 不会意外覆盖 `shouldResetScrollPosition`。

当前 `updateItems()` 代码：

```kotlin
mutableState.value = mutableState.value.copy(
    items = items,
    isLoadingMore = isLoadingMore,
    hasMoreConversations = hasMoreConversations
)
```

✅ 没有覆盖 `shouldResetScrollPosition`，无需修改。

---

## 2. 不在本次范围

- ❌ 滚动到"刚打开的会话"所在位置（而非顶部）——更精细的定位策略可作为后续优化
- ❌ `rememberLazyListState` 初始化参数的移除（让初始位置始终从 0 开始）——这会导致 tab 切换时丢失滚动位置
- ❌ 将 `ConversationListViewModel.stop()` 改为不取消 `conversationUpdates.collect`——会增加不必要的后台刷新开销
- ❌ 使用 Compose Navigation 的 `saveState`/`restoreState` 保留 composable 状态——当前架构中 Chat 和 Conversations 不是同级 tab，不适用

---

## 3. 完整改动清单

| 文件 | 改动 |
|---|---|
| `app/.../conversation/ConversationListViewModel.kt` | (a) `ConversationListUiState` 新增 `shouldResetScrollPosition: Boolean = false`<br>(b) `start()` 在非 `started` 分支的 `refresh()` 之后设置 `shouldResetScrollPosition = true`<br>(c) 新增 `consumeScrollReset()` 方法 |
| `app/.../conversation/ConversationListScreen.kt` | 新增 `LaunchedEffect(state.shouldResetScrollPosition)` 监听并调用 `listState.scrollToItem(0, 0)` + `viewModel.consumeScrollReset()` |

仅 2 个文件，4 处改动。

---

## 4. 验证步骤

1. 打开消息列表，滚动到列表中间位置（如第 5 条会话）
2. 点击一个会话进入聊天
3. 在聊天中发送/接收新消息（确保该会话排序会变化）
4. 按 Back 返回消息列表
5. ✅ 列表应自动滚到顶部，显示最新消息的会话
6. 再次滚动到中间位置，切换到"联系人" tab 再切回"消息" tab
7. ✅ 滚动位置应保持不变（tab 切换不触发重置）
8. 打开聊天后不产生新消息直接 Back
9. ✅ 列表仍应滚到顶部（保持一致行为，避免条件判断的边界问题）

---

## 5. 替代方案（未采纳）

### 方案 B：`start()` 直接重置滚动位置 state

在 `start()` 的 `refresh()` 后直接将 `firstVisibleItemIndex` 和 `firstVisibleItemScrollOffset` 置零，依赖 `rememberLazyListState` 的下一次初始化。

**不采纳原因**：`rememberLazyListState` 只在首次组合时使用 `initial*` 参数。如果 composable 未被完全移除（例如某些导航配置），`listState` 不会重新创建，归零 state 不生效。使用 `scrollToItem()` 是确定性的，不依赖 `rememberLazyListState` 的生命周期。

### 方案 C：`snapshotFlow` 延迟启动

将 `snapshotFlow` 的收集延迟到 `start()` 完成后，避免在数据刷新和滚动重置之间产生竞态。

**不采纳原因**：增加复杂度，且 `LaunchedEffect(state.shouldResetScrollPosition)` 的 `scrollToItem()` 会在下一个帧执行，自然在 `refresh()` 完成之后，不存在竞态问题。

### 方案 D：不在 `stop()` 中取消 `conversationUpdates.collect`

让 ViewModel 在用户离开消息列表期间继续监听数据变化并更新 items。

**不采纳原因**：不必要的后台 CPU/IO 开销；且即使 items 更新了，滚动位置仍需处理，方案 D 不解决核心问题。
