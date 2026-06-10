# 聊天消息时间分隔符：超过间隔阈值自动显示时间

> 状态：方案待确认
> 日期：2026-06-10
> 核心诉求：当两条相邻消息的时间间隔超过阈值时，在它们之间插入一条时间分隔符，帮助用户感知会话节奏

---

## 0. 设计要点

### 0.1 当前状态

目前聊天界面只在**首次进入会话时**在顶部显示第一条消息的时间（`ChatHistoryTopTime`），消息之间没有任何时间提示。

### 0.2 新策略

遍历消息列表，当相邻两条消息（无论是谁发的）的 `createdAt` 间隔超过阈值时，在它们之间插入一条居中的时间分隔符。

**阈值建议**：5 分钟（`TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L`）。理由见下方讨论。

### 0.3 时间格式策略

| 时间范围 | 显示格式 | 示例 |
|---|---|---|
| 今天 | `HH:mm` | `14:30` |
| 昨天 | `昨天 HH:mm` | `昨天 14:30` |
| 今年更早 | `M月D日 HH:mm` | `3月15日 14:30` |
| 去年及更早 | `yyyy年M月D日 HH:mm` | `2025年3月15日 14:30` |

与现有 `topTimelineTimeText` 的 `"HH:mm"` 格式相比，增加了跨天/跨年的判断，因为时间分隔符可能出现在任何日期。

### 0.4 关于阈值：5 分钟 vs 15 分钟

| 阈值 | 优点 | 缺点 |
|---|---|---|
| 5 分钟 | 与微信/Telegram 一致；用户心智预期；能有效标记"会话中断" | 快节奏群聊中可能稍频繁 |
| 10 分钟 | 折中方案，减少分隔符出现频率 | 比主流应用长，可能漏掉一些"中断再续" |
| 15 分钟 | 分隔符极少，界面最干净 | 很多实际已中断的对话得不到时间提示，丢失上下文感 |

**推荐 5 分钟**。若觉得群聊场景太频繁，可后续按会话类型（单聊/群聊）设置不同阈值，但首期统一 5 分钟即可。

---

## 1. 改造步骤

### 步骤 1 — ChatDisplayPolicy：添加时间分隔符判断逻辑

在 `ChatDisplayPolicy` 中新增：

**1a. 常量**

```kotlin
private const val TIME_SEPARATOR_THRESHOLD_MS = 5 * 60 * 1000L  // 5 分钟
```

**1b. 判断方法**

```kotlin
/**
 * 判断当前消息之前是否需要显示时间分隔符。
 * 规则：如果当前消息是列表中的第一条，或与前一条消息的时间间隔超过阈值，则显示。
 */
fun shouldShowTimeSeparator(prevMessage: ChatMessage?, currentMessage: ChatMessage): Boolean {
    if (prevMessage == null) return true  // 第一条消息前由 ChatHistoryTopTime 处理，此分支理论上不会走到
    return currentMessage.createdAt - prevMessage.createdAt > TIME_SEPARATOR_THRESHOLD_MS
}
```

**1c. 时间格式化方法**

替换现有 `topTimelineTimeText`，使其支持跨天/跨年：

```kotlin
fun timeSeparatorText(createdAt: Long): String {
    val messageDate = Date(createdAt)
    val now = Date()
    val calMessage = java.util.Calendar.getInstance().apply { time = messageDate }
    val calNow = java.util.Calendar.getInstance().apply { time = now }

    return when {
        isSameDay(calMessage, calNow) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(messageDate)
        isYesterday(calMessage, calNow) ->
            "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(messageDate)}"
        calMessage.get(java.util.Calendar.YEAR) == calNow.get(java.util.Calendar.YEAR) ->
            SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(messageDate)
        else ->
            SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(messageDate)
    }
}

private fun isSameDay(a: java.util.Calendar, b: java.util.Calendar): Boolean {
    return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun isYesterday(a: java.util.Calendar, b: java.util.Calendar): Boolean {
    val copy = b.clone() as java.util.Calendar
    copy.add(java.util.Calendar.DAY_OF_YEAR, -1)
    return isSameDay(a, copy)
}
```

> 注意：现有的 `topTimelineTimeText` 只格式化第一条消息时间，永远在"今天"上下文内，所以 `HH:mm` 足够。但时间分隔符可能出现在加载的历史消息中（可能是昨天、去年），所以需要更完整的格式。可将 `topTimelineTimeText` 改为调用 `timeSeparatorText`，统一逻辑。

**验收**：`ChatDisplayPolicyTest` 覆盖：
1. 间隔 < 阈值 → `shouldShowTimeSeparator` 返回 `false`
2. 间隔 > 阈值 → 返回 `true`
3. 间隔 = 阈值 → 返回 `false`（严格大于）
4. 时间格式化：今天/昨天/今年/跨年各一 case

---

### 步骤 2 — ChatScreen：在消息列表中渲染时间分隔符

当前 `ChatScreen.kt` L331 的 `itemsIndexed` 渲染逻辑中，在 `ChatMessageRowKind.BUBBLE` 分支内，**消息气泡之前**插入时间分隔符：

```kotlin
itemsIndexed(state.messages, key = { _, msg -> msg.messageId }) { index, message ->
    when (ChatDisplayPolicy.rowKind(message)) {
        ChatMessageRowKind.CENTERED_NOTICE -> RecalledMessageNotice(
            text = ChatDisplayPolicy.recalledMessageText(
                message = message,
                currentUserId = viewModel.currentUserId,
                senderDisplayName = recalledSenderDisplayName(message, state)
            )
        )
        ChatMessageRowKind.BUBBLE -> Column(modifier = Modifier.fillMaxWidth()) {
            // ★ 新增：时间分隔符
            val prevMessage = state.messages.getOrNull(index - 1)
            if (ChatDisplayPolicy.shouldShowTimeSeparator(prevMessage, message)) {
                ChatMessageTimeSeparator(
                    text = ChatDisplayPolicy.timeSeparatorText(message.createdAt)
                )
            }
            // 原有 ChatMessageRow ...
            ChatMessageRow(
                message = message,
                // ... 其余参数不变
            )
            // ... 原有 GroupReadIndicator 逻辑不变
        }
    }
}
```

**关键设计决策——分隔符放在 Column 内部还是作为独立 item？**

放在 Column 内部（即作为 BUBBLE 行的一部分），理由：
1. **不影响 `itemsIndexed` 的 key 稳定性**——如果将分隔符作为独立 item 插入，需要构建一个 `(TimeSeparator | Message)` 的联合列表，key 管理复杂
2. **不影响 `ChatAutoScrollPolicy`**——`scrollToLatestIndex` 基于 `state.messages.size` 计算，插入额外 item 会导致偏移错位
3. **RecalledMessage 不显示时间分隔符**——撤回消息是系统通知，上下文时间感由相邻气泡承担

**验收**：在聊天界面发送一条消息 → 等 5 分钟 → 再发一条 → 两条消息之间出现时间分隔符。

---

### 步骤 3 — 新增 ChatMessageTimeSeparator 组件

在 `ChatScreen.kt` 中新增一个简单的 Composable：

```kotlin
@Composable
private fun ChatMessageTimeSeparator(
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextSecondary
        )
    }
}
```

> 与 `ChatHistoryTopTime` / `ByteImSystemNotice` 不同，时间分隔符**不加背景色圆角矩形**，只显示灰色文字。这是微信/Telegram 的做法——分隔符比顶部的"首次时间"视觉更轻，避免界面过于碎裂。

**验收**：时间分隔符视觉上比 `ChatHistoryTopTime`（带背景色）更轻，文字居中、灰色、小号。

---

### 步骤 4 — 移除 ChatHistoryTopTime 的冗余显示

当前 `ChatHistoryTopTime` 在 LazyColumn 顶部显示第一条消息的时间。新增时间分隔符后，第一条消息前也会触发 `shouldShowTimeSeparator(prevMessage=null, message)` 但我们在步骤 2 中让 `prevMessage == null` 时由 `ChatHistoryTopTime` 处理，不重复显示。

具体做法：`shouldShowTimeSeparator` 中 `prevMessage == null` 返回 `false`（不显示行内分隔符），顶部的 `ChatHistoryTopTime` 仍然独立保留。这样两条路径互不干扰。

```kotlin
fun shouldShowTimeSeparator(prevMessage: ChatMessage?, currentMessage: ChatMessage): Boolean {
    if (prevMessage == null) return false  // 第一条消息由顶部 ChatHistoryTopTime 处理
    return currentMessage.createdAt - prevMessage.createdAt > TIME_SEPARATOR_THRESHOLD_MS
}
```

**同时**，将 `topTimelineTimeText` 改为调用统一的 `timeSeparatorText`，这样顶部的"首次时间"也支持跨天格式：

```kotlin
fun topTimelineTimeText(createdAt: Long): String = timeSeparatorText(createdAt)
```

**验收**：打开一个历史会话 → 顶部时间正确显示（含跨天格式）→ 消息列表中时间间隔 > 5 分钟的位置出现行内时间分隔符 → 第一条消息前不出现重复时间。

---

## 2. 不在本次范围

- ❌ 按会话类型（单聊/群聊）设置不同阈值
- ❌ 时间分隔符的"智能聚合"（如 5 分钟间隔内的连续消息若跨越整点，不在整点额外插分隔符）
- ❌ 消息气泡上的长按查看精确时间（当前已有 action menu，不属于时间分隔符功能）
- ❌ "新消息"分隔线（类似 WhatsApp 的 "X 条新消息" 提示）

---

## 3. 关键文件路径

### 客户端
- `app/.../chat/ChatDisplayPolicy.kt` — `shouldShowTimeSeparator` + `timeSeparatorText` + 常量
- `app/.../chat/ChatScreen.kt` — `itemsIndexed` 中插入分隔符渲染 + `ChatMessageTimeSeparator` 组件
- `app/.../ui/ByteImUi.kt` — `ByteImSystemNotice`（参考样式，不修改）

### 测试
- `app/.../chat/ChatDisplayPolicyTest.kt`（如已有则追加，否则新建）

---

## 4. 实现注意事项

1. **`createdAt` 是毫秒时间戳**——`ChatMessage.createdAt: Long` 是毫秒，所以阈值用 `5 * 60 * 1000L`。
2. **`itemsIndexed` 的 index 从 0 开始**——`index - 1` 在第一条消息时返回 `-1`，`getOrNull(-1)` 返回 `null`，安全。
3. **`shouldShowTimeSeparator` 是纯函数**——只依赖 `ChatMessage.createdAt`，不依赖 UI 状态，方便单测。
4. **`timeSeparatorText` 依赖"现在"**——为了可测试性，可增加 `now: Long = System.currentTimeMillis()` 参数（默认值），单测时注入固定时间。
5. **Recalled 消息不触发分隔符**——撤回消息行是 `CENTERED_NOTICE`，不在 `BUBBLE` 分支中，自然跳过。
