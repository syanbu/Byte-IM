# Bug: 点击加号展开更多操作面板时，聊天界面不缩短

## 问题描述

在聊天界面点击输入栏的 "+" 按钮展开 `ChatMoreActionsPanel` 时，如果聊天界面有消息，聊天界面不会变短，无法像拉起键盘那样缩短聊天界面。

## 根因分析

### 当前布局结构

```
Box (fillMaxSize + imePadding)          ← 外层容器
└── Column (fillMaxSize)                ← 主纵向布局
    ├── ByteImTopBar                    ← 固定高度
    ├── LazyColumn (weight 1f)          ← 消息列表，弹性占据剩余空间
    └── ChatComposerBar                 ← 输入栏 + 更多操作面板
        ├── Row { TextField, emoji, + } ← 输入行
        └── ChatMoreActionsPanel        ← 条件展开：showMoreActions && !hasText
```

### 键盘弹起时的缩短机制（正常工作）

1. `WindowInsets.ime` 变化 → `imePadding()` 缩小 `Box` 内容区高度
2. `Column (fillMaxSize)` 跟随缩小 → `LazyColumn (weight 1f)` 被动缩小
3. **关键**: `LaunchedEffect(imeBottomPx, state.messages.size)` 检测到 IME 高度变化，调用 `ChatAutoScrollPolicy.imeExpansionScrollDeltaPx()` 计算 scroll delta，再调用 `listState.animateScrollBy()` **自动滚动**，保证底部消息仍可见

相关代码 `ChatScreen.kt:229-246`:
```kotlin
LaunchedEffect(imeBottomPx, lastVisibleMessageIndex) {
    if (imeBottomPx == 0) {
        lastVisibleIndexBeforeImeExpansion = lastVisibleMessageIndex
    }
}

LaunchedEffect(imeBottomPx, state.messages.size) {
    val imeScrollDeltaPx = ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
        previousImeBottomPx = previousImeBottomPx,
        currentImeBottomPx = imeBottomPx,
        messageCount = state.messages.size,
        lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeExpansion
    )
    if (imeScrollDeltaPx > 0) {
        listState.animateScrollBy(imeScrollDeltaPx.toFloat())
    }
    previousImeBottomPx = imeBottomPx
}
```

### 点击加号时的行为（有缺陷）

1. `onMoreActionsClick` 调用 `keyboardController?.hide()` + `showMoreActions = !showMoreActions`
2. `ChatMoreActionsPanel` 在 `ChatComposerBar` 内部展开，`ChatComposerBar` 高度增加约 120dp
3. `LazyColumn (weight 1f)` 理论上应缩短（`weight(1f)` 重新分配剩余空间）
4. **但缺少对应的自动滚动机制** —— `ChatAutoScrollPolicy` 仅处理 IME inset 变化，不处理 `showMoreActions` 变化

### 两种缩短机制的本质差异

| 维度 | 键盘弹起 | 加号面板展开 |
|------|----------|------------|
| 触发源 | `WindowInsets.ime` 系统级 inset | `showMoreActions` 应用级状态 |
| 约束变化 | `imePadding()` 改变 `Box` 的内容区约束 | `ChatComposerBar` 高度变化，`Column` 通过 `weight(1f)` 重新分配 |
| 自动滚动 | ✅ `LaunchedEffect(imeBottomPx)` | ❌ 无 |
| 视觉效果 | 消息列表明显上滚，底部消息保持可见 | 底部消息被静默截断，顶部消息不动，视觉上几乎无感知 |

### 核心问题

**`ChatMoreActionsPanel` 展开时，缺少类似 IME 的自动滚动补偿。** 当 `LazyColumn` 因 `weight(1f)` 缩短时，底部消息被截断但不滚动，用户视觉上感受不到聊天区域变短。

此外还有一个次要问题：如果用户先拉起键盘再点 "+"，键盘收起（`imePadding` 恢复）和面板展开（`ChatComposerBar` 变高）同时发生，两者对 `LazyColumn` 高度的影响方向相反，可能互相抵消，导致聊天区域几乎不变。

## 修复方案

### 方案 A：为 showMoreActions 添加自动滚动（推荐）

仿照 IME 的滚动补偿机制，在 `ChatScreen` 中新增一个 `LaunchedEffect(showMoreActions)`：

1. 当 `showMoreActions` 从 `false` 变为 `true` 时，计算 `ChatMoreActionsPanel` 的预期高度（约 120dp），将对应 px 值作为 scroll delta，调用 `listState.animateScrollBy(deltaPx)`
2. 当 `showMoreActions` 从 `true` 变为 `false` 时，无需反向滚动（`LazyColumn` 会自动恢复高度，用户看到的消息位置自然回落）

**需要修改的文件**:
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` — 新增 `LaunchedEffect(showMoreActions)` 处理滚动补偿
- `app/src/main/java/com/buyansong/im/chat/ChatAutoScrollPolicy.kt` — 可选，新增 `moreActionsScrollDeltaPx()` 方法以保持策略逻辑集中

**实现要点**:
- 使用 `LocalDensity` 将 `ChatMoreActionsPanel` 的预期高度（约 120dp: 18dp padding top + 64dp icon + 6dp spacing + ~16dp label + 18dp padding bottom + 1dp divider）转换为 px
- 仅当 `messageCount > 0` 且用户已滚动到底部附近时才触发滚动（与 IME 滚动策略保持一致，参考 `shouldAnchorLatestAfterImeExpansion` 的逻辑）
- 可考虑使用 `onSizeChanged` 或 `onGloballyPositioned` 获取面板实际高度，避免硬编码

### 方案 B：将 ChatMoreActionsPanel 改为 ModalBottomSheet

参考 `MentionPickerSheet` 的实现，将 `ChatMoreActionsPanel` 改为 `ModalBottomSheet`：

- 优点：彻底避免布局问题；提供拖拽关闭、半透明蒙层等更好 UX；与 `MentionPickerSheet` 风格一致
- 缺点：视觉风格变化较大（从内联面板变为底部弹出）；需要重构 `ChatComposerBar` 中面板的渲染逻辑

**需要修改的文件**:
- `app/src/main/java/com/buyansong/im/chat/ChatMoreActionsSheet.kt` — 改为 `ModalBottomSheet` 实现
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` — 将 `showMoreActions` 面板从 `ChatComposerBar` 内移到 `ChatScreen` 层级（与 `MentionPickerSheet` 同级），添加 `BackHandler`

## 验证步骤

1. 进入聊天界面（有消息），键盘未弹出
2. 点击 "+" 按钮，展开更多操作面板
3. **验证**: 聊天消息列表应明显缩短，底部消息应自动上滚保持可见
4. 点击面板外区域或返回键，面板关闭
5. **验证**: 聊天消息列表恢复原始高度，消息位置自然回落
6. 重复步骤 2-3，但先拉起键盘再点 "+"
7. **验证**: 键盘收起 + 面板展开后，聊天区域应仍然缩短（非互相抵消）
8. 聊天界面无消息时点 "+"，不应崩溃或异常滚动
