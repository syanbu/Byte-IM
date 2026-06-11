# Bug Plan: 消息弹窗进入聊天时图片灰度占位闪烁

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-step. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除从消息弹窗（通知/Toast）进入聊天界面时，图片消息先显示灰色占位再渲染图片的问题。从会话列表进入则无此问题。

---

## Status

- Status: Investigated, Plan Ready
- Created on: 2026-06-11
- Branch: rename-redesign-ui
- Scope: `ChatImageBubble` 图片预热时机、`openPreloadedChat` 预加载逻辑

## Observed Symptoms

1. 用户收到消息弹窗（系统通知 or 应用内 Toast），点击进入聊天界面。
2. 聊天界面中的图片消息先显示灰色背景 `Color(0xFFE5E2E1)` 占位，持续几帧（约 1-3 帧）。
3. 然后图片才渲染出来，产生"灰闪"视觉跳动。
4. **但从会话列表点击进入同一个聊天，不会出现此问题。**

## Root Cause Analysis

### 两条路径的完整时序对比

**路径 A：从会话列表进入（无灰闪）**

```
1. 用户点击 ConversationRow
2. ConversationListViewModel.openConversation() 在 IO dispatcher 上执行：
   → repository.openConversationById() → 设置 activeConversationId, 清除未读, 发送已读回执
3. LaunchedEffect(state.navigationTargetConversationId) 触发
4. openPreloadedChat(conversationId) 被调用：
   → messageRepository.preloadInitialPageSync() 同步加载 SQLite 消息到内存缓存
   → navController.navigateToChat()
5. ChatViewModel 被 remember 创建 → init 调用 hydrateInitialStateFromCache()
   → 从 repository 内存缓存读取消息（含 localThumbnailPath 的图片消息）
   → 第一次 composition 时 messages 已有数据
6. ChatScreen 组合 → ChatImageBubble 渲染每条图片消息
7. LaunchedEffect(message.localThumbnailPath) 自预热触发
   → Coil.imageLoader(context).execute(request) 解码本地文件
8. SubcomposeAsyncImage 加载同一 model
```

**路径 B：从消息弹窗进入（有灰闪）**

```
1. 用户点击 MessageToastPopup → controller.openCurrent(onOpenConversation)
2. openPreloadedChat(conversationId) 被调用：
   → messageRepository.preloadInitialPageSync() 同步加载 SQLite 消息到内存缓存
   → navController.navigateToChat()
   ⚠️ 注意：此路径没有调用 repository.openConversationById()！
3. ChatViewModel 被 remember 创建 → init 调用 hydrateInitialStateFromCache()
   → 从 repository 内存缓存读取消息（含 localThumbnailPath 的图片消息）
   → 第一次 composition 时 messages 已有数据
4. ChatScreen 组合 → ChatImageBubble 渲染每条图片消息
5. LaunchedEffect(message.localThumbnailPath) 自预热触发
   → Coil.imageLoader(context).execute(request) 解码本地文件
6. SubcomposeAsyncImage 加载同一 model
   ⚠️ 自预热和 SubcomposeAsyncImage 存在竞态
```

### 核心原因：Coil 内存缓存未预热

两条路径的数据加载（`preloadInitialPageSync` + `hydrateInitialStateFromCache`）完全一致——**SQLite 中的消息数据（含 `localThumbnailPath`）在第一次 composition 前就已就绪**。

关键差异在于 **Coil 的内存缓存状态**：

| 缓存层 | 会话列表路径 | 弹窗路径 |
|--------|------------|---------|
| SQLite 消息数据 | ✅ preloadInitialPageSync 预热 | ✅ 同样预热 |
| Coil 内存缓存（Bitmap） | ✅ 通常已热* | ❌ 冷缓存 |
| ChatImageBubble 自预热 | ✅ 竞态胜出 | ❌ 竞态失败 |

*会话列表路径中，用户浏览列表时已有自然延迟，且可能之前已访问过该聊天的图片，Coil 内存缓存可能仍持有 Bitmap。

**根因机制：**

`ChatImageBubble` 中的 `LaunchedEffect` 自预热与 `SubcomposeAsyncImage` 的内部加载存在**竞态条件**：

```kotlin
// ChatImageBubble.kt, lines 52-56
LaunchedEffect(message.localThumbnailPath) {          // ← 自预热：异步启动
    val path = message.localThumbnailPath ?: return@LaunchedEffect
    val request = ChatLocalThumbnailRequest.build(context, path) ?: return@LaunchedEffect
    Coil.imageLoader(context).execute(request)        // ← 解码本地文件到 Coil 内存缓存
}
// ...
SubcomposeAsyncImage(                                  // ← 几乎同时启动
    model = model,                                     // ← 如果内存缓存未命中，进入 loading 状态
    loading = { /* showInlineProgress() = false, 渲染为空 */ }
)
```

- `LaunchedEffect` 在 composition 后的 **下一帧** 才执行
- `SubcomposeAsyncImage` 在 **当前 composition 帧** 就启动了内部加载
- 当 Coil 内存缓存为冷（从通知弹窗进入时常见），`SubcomposeAsyncImage` 先进入 `loading` 状态
- `loading` 状态下 `showInlineProgress()` 返回 `false`，不渲染任何内容
- 灰色背景 `Color(0xFFE5E2E1)` 透过空内容可见——这就是"灰闪"

### 为什么会话列表路径没有灰闪

1. **Coil 内存缓存可能已热**：用户之前可能浏览过该聊天的图片，Bitmap 仍在 Coil 内存缓存中
2. **自然延迟**：会话列表点击到聊天界面渲染之间有更多帧的时间（列表滚动、UI 反馈等），给了 `LaunchedEffect` 自预热完成的时间窗口
3. **无关键差异**：导航过渡动画配置为 `EnterTransition.None`（见 `MainActivity.kt:539`），两条路径都没有过渡动画缓冲

### 次要因素：`openConversationById` 未被调用

从弹窗路径进入时，`openPreloadedChat` 只调用了 `preloadInitialPageSync()`，**没有调用** `repository.openConversationById()`。这导致：

- `activeConversationId` 在 `ChatViewModel.start()` → `openCurrentConversation()` 之前未被设置
- 在此窗口期，如果有新的图片消息到达，`enqueueIncomingThumbnailIfNeeded` 会以 `NORMAL` 优先级处理，而非 `HIGH`
- 这不是灰闪的直接原因，但影响了缩略图下载的优先级

## Pre-Fix Code Evidence

- [ChatImageBubble.kt:52-56](app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt#L52-L56) — `LaunchedEffect` 自预热与 `SubcomposeAsyncImage` 竞态
- [ChatImageBubble.kt:61](app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt#L61) — 灰色占位背景 `Color(0xFFE5E2E1)`
- [ChatImageBubble.kt:78-81](app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt#L78-L81) — `loading` 状态不渲染任何内容
- [ChatImageBubbleLoadingPolicy.kt:4](app/src/main/java/com/buyansong/im/chat/ChatImageBubbleLoadingPolicy.kt#L4) — `showInlineProgress()` 硬编码 `false`
- [MainActivity.kt:459-464](app/src/main/java/com/buyansong/im/MainActivity.kt#L459-L464) — `openPreloadedChat` lambda，只调用 `preloadInitialPageSync` 未预热 Coil
- [MainActivity.kt:508-513](app/src/main/java/com/buyansong/im/MainActivity.kt#L508-L513) — 弹窗路径消费 pushDeepLink 后调用 `openPreloadedChat`
- [MainActivity.kt:784-786](app/src/main/java/com/buyansong/im/MainActivity.kt#L784-L786) — MessageAlertHost 的 `onOpenConversation` 连接到 `openPreloadedChat`
- [ConversationListViewModel.kt:119-124](app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt#L119-L124) — 会话列表路径先调用 `openConversationById`
- [MessageRepository.kt:657-684](app/src/main/java/com/buyansong/im/message/MessageRepository.kt#L657-L684) — `openConversationById` 设置 `activeConversationId`
- [MessageRepository.kt:443-454](app/src/main/java/com/buyansong/im/message/MessageRepository.kt#L443-L454) — `preloadInitialPageSync` 返回消息列表

## Fix Plan

### 方案：在 `openPreloadedChat` 中批量预热 Coil 内存缓存

在 `preloadInitialPageSync` 之后、导航之前，遍历预加载的消息列表中的图片消息，同步预热它们的 `localThumbnailPath` 到 Coil 内存缓存。

- [ ] **Step 1:** 修改 `openPreloadedChat` lambda，增加 Coil 预热步骤

  文件: `app/src/main/java/com/buyansong/im/MainActivity.kt` (~line 459)

  ```kotlin
  val openPreloadedChat: (String) -> Unit = remember(messageRepository, navController) {
      { conversationId ->
          val cachedMessages = messageRepository.preloadInitialPageSync(conversationId)
          // Pre-warm Coil memory cache for image thumbnails before navigation
          prewarmImageThumbnails(context, cachedMessages)
          SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
      }
  }
  ```

- [ ] **Step 2:** 添加 `prewarmImageThumbnails` 辅助函数

  文件: `app/src/main/java/com/buyansong/im/MainActivity.kt`

  ```kotlin
  private fun prewarmImageThumbnails(context: Context, messages: List<ChatMessage>) {
      val imageLoader = Coil.imageLoader(context)
      messages.filter { it.type == MessageType.IMAGE }
          .mapNotNull { it.localThumbnailPath }
          .forEach { path ->
              val request = ChatLocalThumbnailRequest.build(context, path) ?: return@forEach
              imageLoader.execute(request)  // 同步执行，解码到内存缓存
          }
  }
  ```

  需要新增 import：`coil.Coil`, `com.buyansong.im.chat.ChatLocalThumbnailRequest`, `com.buyansong.im.storage.MessageType`

- [ ] **Step 3（可选）:** 补调 `openConversationById` 使两条路径行为一致

  文件: `app/src/main/java/com/buyansong/im/MainActivity.kt` (~line 459)

  ```kotlin
  val openPreloadedChat: (String) -> Unit = remember(messageRepository, navController) {
      { conversationId ->
          val cachedMessages = messageRepository.preloadInitialPageSync(conversationId)
          messageRepository.openConversationById(session.userId, conversationId)
          prewarmImageThumbnails(context, cachedMessages)
          SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
      }
  }
  ```

  注意：`ChatViewModel.start()` 也会调用 `openCurrentConversation()`，需确认幂等性。可在 `openConversationById` 中加 `if (activeConversationId == conversationId) return conversationId` 守卫去重。

### 为什么这个方案有效

- `preloadInitialPageSync` 已经过滤了没有 `localThumbnailPath` 的图片消息（`isReadyForChatDisplay()`），所以遍历到的每条图片消息都有本地缩略图文件
- `prewarmImageThumbnails` 在主线程同步执行（与 `preloadInitialPageSync` 同步执行模式一致），Coil `execute()` 同步解码本地文件到内存缓存
- 当 `ChatImageBubble` 首次组合时，`SubcomposeAsyncImage` 能直接命中 Coil 内存缓存，无需进入 `loading` 状态
- 无论从哪条路径进入，图片都能在第一帧渲染出来

### 不采用的方案

| 方案 | 不采用原因 |
|------|-----------|
| 给 `SubcomposeAsyncImage` 设置 Coil `placeholder()` | 只是遮盖灰闪，不解决根本问题（首次仍需从磁盘解码），且需要生成占位 Bitmap |
| 给 `loading` 状态加 loading indicator | 视觉上可能比灰闪更差，而且 `showInlineProgress()` 目前刻意关闭 |
| 添加导航过渡动画延迟 | 当前设计明确使用 `EnterTransition.None`，加动画会引入延迟影响体验 |
| 移除 `LaunchedEffect` 自预热完全依赖预加载 | 自预热仍需保留，用于处理 `ChatViewModel.start()` 后新到达的图片消息 |

## Verification

1. 从消息弹窗进入有图片的聊天 → 图片应在第一帧就渲染出来，无灰色占位闪烁
2. 从系统通知进入有图片的聊天 → 同上
3. 从会话列表进入有图片的聊天 → 行为不变，无回退
4. 进入没有图片的纯文本聊天 → 无影响
5. 冷启动后从通知进入 → Coil 内存缓存为冷，但预热步骤确保图片在导航前已解码
6. 快速连续点击不同聊天的弹窗 → 预热不会阻塞导航过久（本地文件解码通常 < 10ms/张）
