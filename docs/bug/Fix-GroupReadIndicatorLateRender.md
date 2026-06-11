# Bug: 群聊已读标记延迟渲染导致消息跳动

## 问题描述

群聊中，当 A 发了消息，B 已读后，A 的消息气泡右下角出现 "X人已读" 标记。当 B 随后发了新消息，A 从会话列表进入群聊页面时，"X人已读" 标记会比消息列表**晚渲染**，导致该标记下方的消息（B 的新消息）因已读标记的出现而向下跳动一段位置。

## 根因分析

### 1. 布局结构

`ChatScreen.kt:369-413` 中，每个 LazyColumn 消息项的结构：

```
Column(fillMaxWidth) {            ← 每个 LazyColumn item
    TimeSeparator?                ← 条件渲染的时间分隔线
    ChatMessageRow                ← 消息气泡行（头像 + 气泡内容）
    GroupReadIndicator?           ← 条件渲染的 "X人已读" 标记
}
```

`GroupReadIndicator` 渲染在 `ChatMessageRow` **下方**，是一个独立的 Composable（`GroupReadIndicator.kt`）。当它出现时，会给当前 item 增加约 16dp 高度（2dp topPadding + ~14dp 文字高度）。

### 2. 竞态条件（核心原因）

`ChatViewModel.kt:242-272` 的 `start()` 方法中：

```
start()
  ├── openCurrentConversation()
  ├── startGroupReadObservation()     ← ① 重置 latestGroupReadCursors = emptyList()，异步启动 Flow 收集
  ├── connectIfNeeded()
  ├── launch { conversationUpdates.collect { ... } }
  └── launch {
        refreshInitialPage()
        refreshProfiles()
        recomputeGroupReadIndicator()  ← ② 此时 latestGroupReadCursors 仍为 emptyList()
        scheduleMissingThumbnailRetries()
      }
```

**竞态过程**：

1. ① `startGroupReadObservation()` 将 `latestGroupReadCursors` 重置为 `emptyList()`，然后启动协程去收集 `observeGroupReadCursors(groupId)` Flow
2. ② 同一协程中 `recomputeGroupReadIndicator()` 被调用，没有传入显式 cursors 参数，fallback 到 `latestGroupReadCursors`——此时仍为 `emptyList()`
3. 因此 `readersOf()` 返回空 → `groupReadCountForLatest = 0` → `GroupReadIndicator` **不渲染**
4. 后续 `observeGroupReadCursors` Flow 从 SQLite 发出真实的 cursor 数据
5. 触发 `recomputeGroupReadIndicator(cursors)` → `groupReadCountForLatest > 0`
6. Compose 重组，为消息项**新增** `GroupReadIndicator`，item 高度增加约 16dp
7. 下方的消息（B 的新消息）被向下推出 ~16dp → **视觉跳动**

### 3. 同一问题存在于 `selectPeer()`

`ChatViewModel.kt:288-317` 中存在完全相同的竞态：

```kotlin
fun selectPeer(peerId: String) {
    // ...state reset...
    scope.launch(dispatcher) {
        openCurrentConversation()
        startGroupReadObservation()    // ← 同样的竞态
        refreshProfiles()
        refreshInitialPage()
        recomputeGroupReadIndicator()  // ← 同样拿不到 cursors
        scheduleMissingThumbnailRetries(immediate = true)
    }
}
```

### 4. 无滚动补偿机制

`ChatAutoScrollPolicy.kt` 仅处理以下场景的滚动补偿：

- 初始锚定（PRE_MEASURE_ANCHOR_TO_LATEST）
- 新消息到达（ANIMATE_TO_LATEST）
- IME 键盘弹起（imeExpansionScrollDeltaPx）
- 更多操作面板展开（moreActionsExpansionScrollDeltaPx）

**不处理** LazyColumn item 因状态更新导致的高度变化。

## 修复方案：同步等待首次 cursor 加载

### 原理

`AndroidGroupReadCursorDao.kt:56-63` 中 `observeByGroup()` 的实现：

```kotlin
override fun observeByGroup(groupId: String): Flow<List<GroupReadCursor>> {
    return flow.asSharedFlow()
        .onStart { emit(findByGroup(groupId)) }   // ← 首次 emit 就是 SQLite 同步查询结果
        .distinctUntilChanged()
}
```

由于 `onStart` 中直接 emit `findByGroup()` 的结果（同步 SQLite 查询），对返回的 Flow 调用 `.first()` 可**立即**获得当前数据库中的 cursor 值，无需等待网络。

### 修改内容

#### 文件：`ChatViewModel.kt`

**修改 1：将 `startGroupReadObservation()` 改为 suspend 函数**

当前（line 726-745）：

```kotlin
private fun startGroupReadObservation() {
    // ...
    latestGroupReadCursors = emptyList()           // ← 问题根源：先重置为空
    groupReadObservationJob = scope.launch {        // ← 异步启动，不等待
        repository.observeGroupReadCursors(groupId).collect { cursors ->
            latestGroupReadCursors = cursors
            recomputeGroupReadIndicator(cursors)
        }
    }
}
```

改为：

```kotlin
private suspend fun startGroupReadObservation() {
    // ...
    val initialCursors = repository.observeGroupReadCursors(groupId).first()  // ← 等待首次 emit
    latestGroupReadCursors = initialCursors                                     // ← 直接设为真实数据
    // 继续启动持续观察协程
    groupReadObservationJob = scope.launch {
        repository.observeGroupReadCursors(groupId).collect { cursors ->
            latestGroupReadCursors = cursors
            recomputeGroupReadIndicator(cursors)
        }
    }
}
```

**修改 2：调整 `start()` 中的调用顺序**

将 `startGroupReadObservation()` 移入初始刷新协程内，使其在 `recomputeGroupReadIndicator()` 之前完成：

```kotlin
fun start() {
    if (started) return
    started = true
    openCurrentConversation()
    connectIfNeeded()
    // ...conversationUpdates, recallFailures 协程不变...
    jobs += scope.launch(dispatcher) {
        startGroupReadObservation()       // ← suspend，等待 cursors 加载完成
        refreshInitialPage()
        refreshProfiles()
        recomputeGroupReadIndicator()     // ← 此时 latestGroupReadCursors 已有数据
        scheduleMissingThumbnailRetries(immediate = true)
    }
}
```

**修改 3：`selectPeer()` 同理**

`startGroupReadObservation()` 改为 suspend 后，`selectPeer()` 中的调用自然按顺序执行，无需额外修改。

**修改 4：添加 import**

```kotlin
import kotlinx.coroutines.flow.first
```

### 不修改的文件

| 文件 | 原因 |
|------|------|
| `ChatScreen.kt` | UI 层无需改动，渲染逻辑本身正确 |
| `GroupReadIndicator.kt` | 组件本身正确，问题在数据到达时序 |
| `ChatAutoScrollPolicy.kt` | 不需要滚动补偿——修复后首次渲染即包含正确的已读标记 |
| `AndroidGroupReadCursorDao.kt` | 已有的 `onStart { emit(findByGroup()) }` 保证 `.first()` 立即返回 |
| `GroupReadReceiptPolicy.kt` | 纯逻辑层无问题 |

## 被否决的方案

| 方案 | 否决原因 |
|------|---------|
| A: 为 GroupReadIndicator 预留空间 | count=0 时出现空白间隙，视觉回退 |
| C: 添加滚动补偿 | Compose 不保证状态变化与滚动在同一帧生效，可能仍有闪烁 |
| D: 将 GroupReadIndicator 移入 ChatMessageRow | 不解决根因——item 高度仍会变化 |

## 验证步骤

1. 群聊中 A 发消息，B 已读
2. B 再发一条消息
3. A 从会话列表进入群聊
4. **验证**：进入时消息列表无跳动，A 的最新消息下方直接显示 "X人已读" 标记
5. 重复步骤 1-4，但 B 未读 A 的消息
6. **验证**：进入时无已读标记、无跳动
7. 单聊场景回归：进入单聊页面，已读/未读对勾正常显示
8. 运行现有测试：`ChatViewModelGroupReadReceiptTest` 等
9. 可新增测试：验证 `start()` 后 `groupReadCountForLatest` 首次即为正确值
