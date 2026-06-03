# 收到新消息时弹窗通知 · 设计文档

> 状态：设计稿，待用户 review
> 日期：2026-06-03
> 关联：`docs/DEVELOPMENT_STATUS.md`（Messages Tab Unread 体系）、`docs/messages-conversation-summary-and-unread.md`（会话摘要与未读设计）

## 1. 背景与目标

当前 `Messages` 首页已经支持：

- 会话未读数（每个会话的 `unread_count`，底部 `Messages` tab 也有全局未读角标）
- 收到新消息时刷新首页最后一条消息预览
- 进入聊天页时清零对应未读

但**没有任何"屏内浮层通知"**：用户在 `Messages`、`Contacts`、`Me` 任意一个 tab 时，收到新消息只能看到首页/角标数字变化，没有弹窗告知"谁、说了什么"。

本设计新增一个**顶部悬浮弹窗**：收到新消息时，在屏幕顶部出现一张 320dp 左右的卡片，显示**对方头像 + 昵称 + 消息摘要 + 关闭按钮 + 时间戳**。点击卡片跳转到对应聊天页，4 秒自动消失。

类微信/QQ 桌面端体验，作为现有未读体系的**补充**（不替代角标，角标继续保留）。

## 2. Goals

- 收到新消息时（非当前聊天页），屏幕顶部弹出 Toast 卡片
- 卡片展示：对方头像、昵称、消息摘要（TEXT 显示内容 / IMAGE 显示 `[图片]`）、时间戳、关闭按钮
- 覆盖单聊和群聊两种会话类型
- 点击卡片 → 跳转到对应聊天页 + 清未读
- 4 秒自动消失，× 按钮立即关闭
- 新消息到达时若已有弹窗，旧弹窗立即被新弹窗替换（单条替换策略）

## 3. Non-Goals

- 不实现系统级推送通知（依赖 B13 推送通道，本期不做）
- 不实现群消息的多条堆叠通知
- 不实现滑动手势关闭
- 不实现长按菜单（回复 / 标记已读）
- 不修改现有未读计数逻辑（角标、会话未读数继续由 `MessagesTabUnreadBadgeController` / `ConversationDao.totalUnreadCount` 维护）
- 不修改 WebSocket 协议 / `MessageRepository.handleIncoming` 的写库 / ACK 时序
- 不在用户**正在看的那个聊天页**里弹窗

## 4. 决策汇总

| 决策点 | 选择 |
|---|---|
| 触发条件 | `inserted == true` && `message.conversationId != activeConversationId` |
| 组件 | 自定义 Toast 卡片（Compose），浮在 `MainActivity` 最外层 `Box` |
| 点击卡片 | 跳转到对应聊天页（顺带清未读） |
| × 按钮 | 只关当前弹窗，不影响未读数 |
| 自动消失 | 4 秒 |
| 滑动手势 | 不支持 |
| 堆叠 | 同时只显示一条，新的替换旧的 |
| 单聊头像/昵称 | `ProfileRepository.cachedProfile(senderId)`，没有 fall back 到 `senderId` |
| 群聊昵称 | `Conversation.peerName`（已写入 conversations 表） |
| 群聊头像 | `Conversation.avatarUrl` ?: `null`（用占位符兜底） |
| 群聊预览 | `${senderNickname}: ${content}` 整体截断到 20 字符 |
| TEXT 预览 | `message.content` 截断到 20 字符 |
| IMAGE 预览 | 固定 `"[图片]"` |
| 触发消息类型 | TEXT、IMAGE |
| 不触发的类型 | RECALL 通知、ACK 重复包、系统消息 |

## 5. 架构与文件结构

### 5.1 新增文件

全部放在 `app/src/main/java/com/codex/im/alert/`：

```
com/codex/im/alert/
├── IncomingMessageAlert.kt        # 数据类
├── MessageAlertPolicy.kt          # 纯函数：预览截断、时间格式、群消息发送者拼接
├── MessageAlertController.kt      # 状态机：当前 alert + 4秒计时 + 单条替换 + 跳转回调
└── MessageToastPopup.kt           # Composable：弹窗卡片 UI
```

### 5.2 修改文件

- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
  - 新增 `messageAlerts: SharedFlow<IncomingMessageAlert>` 公开 API
  - 在 `handleIncoming` 现有 `if (inserted)` 分支里构造 `IncomingMessageAlert` 并 `tryEmit`
- `app/src/main/java/com/codex/im/MainActivity.kt`
  - 在 `AuthenticatedImNavHost` 外层包一个 `Box`
  - 把 `MessageAlertHost(controller, onOpenConversation = { conversationId -> navController.navigate("chat/${conversationId}") })` 放在 Box 内，盖在 `NavHost` 上方
  - 启动一个 `LaunchedEffect(Unit)` 收集 `messageRepository.messageAlerts` 并喂给 `alertController.show(...)`

### 5.3 关键依赖（已有，不新增）

- `ProfileRepository.cachedProfile(userId)` / `lookupProfile(userId)`：单聊头像/昵称来源
- `Conversation.peerName` / `Conversation.avatarUrl`：群聊昵称/头像
- `Message.senderId`：用于群消息预览构造 `李四: xxx` 格式

## 6. 数据流

### 6.1 完整路径

```
WebSocket ImPacket(RECEIVE_MESSAGE)
   ↓
MessagePacketProcessor.onPacket(...)
   ↓
MessageRepository.handlePacket(...)  → handleIncoming(json)
   ↓
[1] 解析 → ChatMessage
[2] messageDao.insertOrIgnore(message)        # 现有逻辑：先写库
       ↓ inserted == true
[3] val incrementUnread = message.conversationId != activeConversationId
       ↓
[4] conversationDao.upsertFromMessage(..., incrementUnread)  # 现有逻辑
[5] notifyConversationChanged()              # 现有逻辑：刷新首页/角标
[6] buildIncomingMessageAlert(message)       # 新增：构造 IncomingMessageAlert
[7] mutableMessageAlerts.tryEmit(alert)       # 新增：emit 给弹窗订阅者
[8] DELIVERY_ACK / READ_ACK 处理              # 现有逻辑
[9] enqueueIncomingThumbnailIfNeeded(...)     # 现有逻辑
```

**关键不变量**：弹窗的 `title` / `avatarUrl` / `preview` 全部在 `handleIncoming` 调用瞬间冻结，不做异步更新。已显示的弹窗不会中途变样式。

### 6.2 `IncomingMessageAlert` 数据类

```kotlin
data class IncomingMessageAlert(
    val conversationId: String,   // 跳转用
    val isGroup: Boolean,         // UI 用于切群/单聊样式
    val title: String,            // 单聊=对方昵称，群聊=群名
    val avatarUrl: String?,       // 单聊=对方头像，群聊=群头像
    val preview: String,          // 单聊=消息内容或[图片]，群聊="发送者昵称: 内容"
    val rawTimestamp: Long        // 消息 createdAt，Composable 渲染时格式化
)
```

### 6.3 字段解析路径（同步在 `handleIncoming` 完成）

| 字段 | 单聊来源 | 群聊来源 |
|---|---|---|
| `title` | `ProfileRepository.cachedProfile(senderId)?.nickname` ?: `senderId` | `Conversation.peerName` ?: `groupId` |
| `avatarUrl` | `ProfileRepository.cachedProfile(senderId)?.avatarUrl` | `Conversation.avatarUrl` |
| `preview` | TEXT→`message.content` / IMAGE→`"[图片]"` | TEXT→`"${senderNickname}: ${content}"` / IMAGE→`"${senderNickname}: [图片]"` |
| `rawTimestamp` | `message.createdAt` | `message.createdAt` |

预览全部经 `MessageAlertPolicy.previewText(...)` 截断到 20 字符 + `…`。

### 6.4 `MessageAlertController` 状态机

```
State: Idle (无弹窗)
   ↓ onIncomingAlert(alert)
Showing(alert, remainingMs = 4000)  ── 启动 4s 倒计时
   ↓ tick(1000ms) 减 1s             ── 倒计时归零
   ↓
Idle

   ↓ 用户点击 × 按钮 / 卡片本身    ── 任意时刻可立即清空
Idle

   ↓ 倒计时未结束又来了新 alert
   ↓ 旧 alert 立即被新 alert 替换
Showing(newAlert, remainingMs = 4000)  ── 计时器重置
```

### 6.5 跳转路径

弹窗卡片被点击时：
1. `MessageAlertController` 调 `onOpenConversation(conversationId)`
2. `MainActivity` 收到回调 → `navController.navigate("chat/${conversationId}")`
3. 现有 `ChatScreen` 启动路径会调 `MessageRepository.openConversationById(...)` → 未读清零
4. `currentAlert` 立即清空 → 弹窗 UI fadeOut

## 7. UI 组件设计

### 7.1 视觉规范

```
┌────────────────────────────────────┐
│ [头像]  张三                  ×  │
│         在吗？明天的会…     12:31 │
└────────────────────────────────────┘
```

群聊变体：

```
┌────────────────────────────────────┐
│ [群头像]  项目讨论组          ×  │
│         李四: 好的，我准备…  12:30 │
└────────────────────────────────────┘
```

### 7.2 布局规格

| 元素 | 规格 |
|---|---|
| 容器 | `Surface`，圆角 14dp，白色背景，elevation = 6dp |
| 宽度 | `fillMaxWidth(0.85f)`，min 280dp，max 360dp |
| 位置 | 屏幕顶部，`statusBarsPadding()` + 顶部 56dp 边距 |
| 进出动画 | `AnimatedVisibility`：进入 200ms `slideInVertically { -it } + fadeIn`；退出 200ms `slideOutVertically { -it } + fadeOut` |
| 头像 | 40dp 圆形；`coil.compose.AsyncImage`；URL 为 null 时 `Box + 字母首字 + 主题色背景`（与 `ConversationListScreen` 已有 fallback 一致） |
| 昵称 | 14sp / `FontWeight.SemiBold` / `MaterialTheme.colorScheme.onSurface` |
| 预览 | 13sp / `onSurfaceVariant` / `maxLines = 1` + `TextOverflow.Ellipsis` / 20 字符截断（由 `MessageAlertPolicy.previewText` 输出） |
| 时间 | 11sp / `onSurfaceVariant` 60% 透明度 / 右下角 |
| × 按钮 | 16sp 文字"×"，颜色 `onSurfaceVariant` 50%，点击区域 24×24dp |
| 整卡点击 | `clickable { onCardClick() }` |

### 7.3 `MainActivity` 集成点

```kotlin
@Composable
fun AuthenticatedImNavHost(...) {
    val navController = ...
    val alertController = remember { MessageAlertController() }  // 新增
    LaunchedEffect(Unit) {
        messageRepository.messageAlerts.collect { alertController.show(it) }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = ...) { ... }   // 现有导航
        MessageAlertHost(                          // 新增：浮在最上层
            controller = alertController,
            onOpenConversation = { conversationId ->
                navController.navigate("chat/${conversationId}")
            }
        )
    }
}
```

### 7.4 `MessageAlertHost` 内部

- `AnimatedVisibility(visible = controller.currentAlert != null)` 渲染 `MessageToastPopup`
- 4 秒倒计时由 `LaunchedEffect(alert)` 内 `delay(4000)` 实现
- 切换 alert 时 `LaunchedEffect` key 改变 → 旧 effect 取消 → 新 effect 启动 → 自动重置计时
- 点击 × 调 `controller.dismiss()`
- 点击卡片调 `controller.dismiss() + onOpenConversation(alert.conversationId)`

### 7.5 与现有 UI 的一致性

- 头像 fallback 用主题色背景 + 昵称首字，复用 `ConversationListScreen` 已有逻辑
- 时间格式 `java.text.SimpleDateFormat("HH:mm", Locale.getDefault())` → 12:31
- 不引入新主题色，复用 `MaterialTheme.colorScheme`

## 8. 边界情况与错误处理

| ID | 场景 | 处理策略 |
|---|---|---|
| E1 | 群消息里找不到发送者的昵称 | 预览从 `${senderNickname}: xxx` 降级为 `${senderId}: xxx`；弹窗照常显示 |
| E2 | 群消息但本地 `Conversation` 还没写入 | 标题 fall back 到 `groupId`；头像用占位符；不阻塞弹窗 |
| E3 | IMAGE 消息 | 预览固定 `"[图片]"`，不读 `imageUrl` |
| E4 | 同一会话在 1 秒内连收 3 条 | 按"单条替换"，新 alert 替换旧的，计时器重置为 4 秒 |
| E5 | 弹窗显示中点击 × | `currentAlert = null` 立即清空；不影响 `unread_count` |
| E6 | 弹窗显示中点击卡片跳转 | 立即清空 + `onOpenConversation(conversationId)`；跳转后 `openConversationById` 清未读 |
| E7 | 弹窗显示中退出 App / 切后台 | 弹窗 UI 销毁（Composable 出 composition）；Controller 状态保留；回到前台**不**自动恢复弹窗（避免陈年通知骚扰） |
| E8 | WebSocket 推同 `messageId` 重复包 | `inserted = false` → **不 emit alert** |
| E9 | 已登录但 `activeConversationId = null` | `null != "single:alice:bob"` 为 true → 弹窗正常显示（用户在 Messages/Contacts/Me 都应看到） |
| E10 | 未登录状态 | WebSocket 未建立，`handleIncoming` 不会被调用 → 无弹窗 |
| E11 | Controller 内存泄漏防护 | 状态只放 Composable `remember{}`，不提升到全局单例；不持有 `Context` / `Activity` 引用 |

### 8.1 触发条件重写（`activeConversationId` 解释）

- `message.conversationId: String`（**非空**），单聊格式 `"single:alice:bob"`、群聊格式 `"group:grp_xxx"`
- `activeConversationId: String?`（**可空**），用户**进入**聊天页时设置，离开时清回 `null`

```kotlin
val shouldPopup = inserted && message.conversationId != activeConversationId
```

| `activeConversationId` | `message.conversationId` | 比较 | 行为 |
|---|---|---|---|
| `null`（用户没进聊天页） | `"single:alice:bob"` | `!=` true | 弹窗 |
| `"single:alice:bob"`（用户在 alice 聊天页） | `"single:alice:bob"` | `!=` false | 不弹 |
| `"single:alice:bob"`（用户在 alice 聊天页） | `"single:alice:carol"` | `!=` true | 弹窗（carol 的消息不影响 alice 聊天页） |

## 9. 测试策略

4 个 JVM 单测 + 1 份手测 checklist，**全部不依赖 Android instrumented 测试**，跑在 `./gradlew :app:testDebugUnitTest`。

### 9.1 `MessageAlertPolicyTest`（`com.codex.im.alert`）

纯函数，0 依赖：

- `previewTextForText("")` → `""`
- `previewTextForText("短文本")` → `"短文本"`
- `previewTextForText(21 字符长字符串)` → 前 20 字符 + `"…"`
- `previewTextForText(20 字符字符串)` → 原样（边界条件，不加省略号）
- `previewTextForImage()` → `"[图片]"`
- `groupPreviewText("李四", "在吗？")` → `"李四: 在吗？"`
- `groupPreviewText("李四", 25 字符长字符串)` → `"李四: " + 前 (20-len("李四: ")) 字符 + "…"`
- `groupPreviewText(null, "在吗？")` → 降级用 `senderId` 拼接（用 `senderId="u4"` 时 → `"u4: 在吗？"`）
- `formatTime(epochMillis)` → `HH:mm` 格式（注入 `Clock` 避免 flaky）

### 9.2 `MessageRepositoryIncomingAlertTest`（`com.codex.im.message`）

验证 `messageAlerts` flow 的 emit / 不 emit 分支：

- 单聊新消息 + `activeConversationId = null` → flow 收到 alert，title/preview/avatarUrl 正确
- 单聊新消息 + `activeConversationId = 同会话` → flow 不收到 alert
- 重复包（`messageId` 已存在） → flow 不收到 alert
- 单聊 + `ProfileRepository` 无缓存 → alert.title = `senderId`，avatarUrl = null
- 群聊 + Conversation 在 DB → alert.title = 群名，preview = `李四: 内容`
- 群聊 + Conversation 不在 DB → alert.title = groupId，不抛异常
- IMAGE 消息 → alert.preview = `[图片]`
- 群消息发送者昵称查不到 → preview 用 `senderId` 拼接

收集方式：`flow.first(timeout)` 或 `MutableSharedFlow.replayCache`。

### 9.3 `MessageAlertControllerTest`（`com.codex.im.alert`）

验证状态机和计时器（用 `StandardTestDispatcher` + `advanceTimeBy(...)`）：

- `show(alertA)` → `currentAlert == alertA`
- `advanceTimeBy(4000)` → `currentAlert == null`
- 倒计时内（`advanceTimeBy(2000)`）再 `show(alertB)` → `currentAlert == alertB`，计时器重置
- 倒计时内 `dismiss()` → `currentAlert == null`，不等到 4 秒
- `dismiss()` 后再 `show(alertC)` → 正常显示
- 同一 alert 在 100ms 内连续 show 两次 → 仍然只显示一次
- 卡片点击 → `onOpenConversation` 被调用 + `currentAlert` 立即清空
- × 点击 → `onOpenConversation` 不被调用 + `currentAlert` 立即清空

> `MessageAlertController` 必须设计成**可注入 `CoroutineDispatcher` 和 `now/clock` 函数**，避免真实 sleep。

### 9.4 `MessageToastPopupSnapshotTest`（**可选，先不写**）

Compose 渲染快照测试（roborazzi / Paparazzi）。如果项目没引入，先**不写**；T1/T2/T3 通过即可。UI 视觉在 emulator 上手测。

### 9.5 端到端手测 checklist

写到 `docs/status/2026-06-03-message-toast-popup-status.md` 跟踪：

- [ ] 在 Messages 列表页，对方发来单聊 TEXT → 顶部弹出卡片，点击 → 跳转聊天页 + 未读清零
- [ ] 在 Messages 列表页，对方发来单聊 IMAGE → 预览 `[图片]`，点击 → 跳转聊天页能看图
- [ ] 群聊场景：标题是群名，预览是 `李四: 内容` 格式
- [ ] 在某个聊天页时，对方发来同会话消息 → 不弹
- [ ] 在聊天页时，对方发来另一会话消息 → 弹
- [ ] 点击 × → 弹窗消失，未读数仍 +1
- [ ] 4 秒不动 → 自动消失
- [ ] 100ms 内连发 3 条 → 看到的是最新一条
- [ ] 弹窗未消失时退出 App → 回到前台弹窗不再显示
- [ ] 重复包 → 不弹（已有去重保证）

## 10. 风险与未决项

- **风险 1**：`MessageRepository.handleIncoming` 当前已经写得很长，新增 1 段 alert 构造逻辑后行数会接近 100 行。本设计不要求拆分，但如果未来继续加 B13 推送 / @ mention 弹窗变体，建议把"弹窗信号"和"未读信号"拆成两个独立 SharedFlow。
- **风险 2**：群消息发送者昵称走 `ProfileRepository.cachedProfile` 是同步调用。如果 `ProfileRepository` 后续改成 suspend-only / Flow-based，需要调整为协程内调用并延迟 emit。当前设计假设它是同步缓存读取。
- **未决项**：弹窗在 `MainActivity` 内的 `Box` 位置与 `Messages` tab 的 Top Bar 视觉上是否冲突（都在顶部 56dp 范围）。实现后用 emulator 验证一次，必要时调整上边距。

## 11. 实现步骤概要（详细 plan 走 writing-plans skill）

1. 新增 `IncomingMessageAlert` 数据类
2. 新增 `MessageAlertPolicy` 纯函数 + 单元测试
3. 修改 `MessageRepository` 新增 `messageAlerts: SharedFlow<IncomingMessageAlert>` + 单元测试
4. 新增 `MessageAlertController` 状态机 + 单元测试
5. 新增 `MessageToastPopup` Composable
6. 修改 `MainActivity` 集成 `MessageAlertHost`
7. 跑全套 JVM 测试 + emulator 手测 checklist
