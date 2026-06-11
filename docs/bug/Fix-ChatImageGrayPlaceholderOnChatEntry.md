# Fix: 接收方进入聊天时图片灰色占位符闪烁

- Created on: 2026-06-11
- Branch: rename-redesign-ui
- Scope: `ChatImageBubble` 自预热竞态、`openPreloadedChat` 缺少 Coil 预热
- Status: Implemented

## Observed Symptoms

用户 B 给用户 A 发了 9 张图片，A 从会话列表点进聊天：
- 文字消息立即渲染 ✅
- 图片消息先显示灰色占位符 `Color(0xFFE5E2E1)`，等几帧后才显示缩略图 ❌

## Root Cause Analysis

### 竞态时序

```
T0   openPreloadedChat → preloadInitialPageSync() 阻塞主线程读 SQLite
     → 只加载了消息元数据（路径字符串、尺寸、URL），Coil 内存缓存为空

T0+  ChatViewModel.init → hydrateInitialStateFromCache()
     → 从 initialPageCache（内存 HashMap）填充 MutableStateFlow.messages
     → messages 包含 localThumbnailPath 字符串，但 Coil 未解码任何 bitmap

T1   首帧 composition → ChatScreen → LazyColumn → ChatImageBubble
     → SubcomposeAsyncImage(model = localThumbnailRequest) 发起异步加载
     → Coil 内存缓存 miss → 进入 loading 状态 → 灰色背景 Color(0xFFE5E2E1) 穿透
     
T1   首帧 composition 后 → LaunchedEffect(message.localThumbnailPath) 执行
     → Coil.imageLoader(context).execute(request) 同步解码
     → 缓存开始变暖，但 SubcomposeAsyncImage 已在飞

T2+  Coil 异步解码完成 → 重组 → 图片才显示
```

### 核心矛盾

**`LaunchedEffect` 是 Compose side-effect，在 composition 之后才执行；`SubcomposeAsyncImage` 在 composition 期间就发起加载。**

自预热的 `LaunchedEffect` 永远赢不了第一帧——它在 `SubcomposeAsyncImage` 已经 cache miss 之后才把 bitmap 放进内存缓存。

### 缓存层级对比

| 缓存层 | 进入聊天时状态 | 说明 |
|--------|---------------|------|
| SQLite 消息数据 | ✅ preloadInitialPageSync 预热 | 路径、尺寸、URL 等元数据 |
| Coil 内存缓存（Bitmap） | ❌ 冷缓存 | 没有任何 bitmap 被解码 |
| ChatImageBubble 自预热 | ❌ 竞态必输 | LaunchedEffect 在 SubcomposeAsyncImage 之后执行 |

### 为什么发送方不受影响

发送方的 `prewarmOutgoingLocalThumbnails()` 在 `viewModel.sendImages()` **之前**同步执行——数据还没到 UI，Coil 缓存已经热了。首帧 composition 时 `SubcomposeAsyncImage` 直接命中内存缓存。

### 与已有 bug 文档的关系

`Fix-ChatImageGrayscalePlaceholderOnNotificationEntry.md` 描述的是**通知入口**的同一问题，但该修复未实现，且该文档只关注通知路径。实际上**会话列表入口也有同样问题**——只是平时 Coil 缓存可能幸存（用户之前访问过），所以不常触发。两条路径都经过 `openPreloadedChat`，根因相同。

## Fix Plan

### 方案：在 `openPreloadedChat` 中启动 Coil 内存缓存预热

与发送方 `prewarmOutgoingLocalThumbnails()` 模式一致：**在 UI 消费数据之前，提前把 bitmap 填入 Coil 缓存**。

在 `preloadInitialPageSync()` 返回消息列表后、导航之前，收集其中图片消息的本地缩略图路径，启动后台 `Dispatchers.IO` 预热任务，然后立即导航。

> 2026-06-11 修正：旧草案使用 `runBlocking` 在主线程等待 Coil `execute()` 完成。实测从会话列表进入图片聊天会触发 ANR，系统随后杀进程。当前实现改为非阻塞预热，避免入口路径被 bitmap 解码卡住。

- [x] **Step 1**: 修改 `openPreloadedChat` lambda，增加 Coil 预热步骤

  文件: `app/src/main/java/com/buyansong/im/MainActivity.kt` (~line 459)

  当前：
  ```kotlin
  val openPreloadedChat: (String) -> Unit = remember(messageRepository, navController) {
      { conversationId ->
          messageRepository.preloadInitialPageSync(conversationId)
          SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
      }
  }
  ```

  改为：
  ```kotlin
  val openPreloadedChat: (String) -> Unit = remember(context, uiScope, messageRepository, navController) {
      { conversationId ->
          val messages = messageRepository.preloadInitialPageSync(conversationId)
          ChatInitialImagePrewarmer.prewarmAsync(uiScope, context, messages)
          SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
      }
  }
  ```

- [x] **Step 2**: 添加 `ChatInitialImagePrewarmer`

  文件: `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt`

  注意：预热函数不能作为 `ChatScreen.kt` 的 `private` 顶层函数实现，因为调用点在 `MainActivity.kt`。当前实现提取为 `ChatInitialImagePrewarmer`，由 `MainActivity.openPreloadedChat` 调用。

  ```kotlin
  object ChatInitialImagePrewarmer {
      fun prewarmAsync(scope: CoroutineScope, context: Context, messages: List<ChatMessage>) {
          val thumbnailPaths = thumbnailPathsToPrewarm(messages)
          if (thumbnailPaths.isEmpty()) return

          val appContext = context.applicationContext
          scope.launch(Dispatchers.IO) {
              withTimeoutOrNull(300) {
                  val imageLoader = Coil.imageLoader(appContext)
                  thumbnailPaths.forEach { path ->
                      val request = ChatLocalThumbnailRequest.build(appContext, path) ?: return@forEach
                      imageLoader.execute(request)
                  }
              }
          }
      }
  }
  ```

- [x] **Step 3**: 添加路径筛选单测

  文件: `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt`

  覆盖行为：只预热图片消息的 `localThumbnailPath`，忽略文本/空路径，并对重复路径去重。

  初始 RED:
  ```text
  Unresolved reference 'ChatInitialImagePrewarmer'
  ```

  GREEN:
  ```text
  ChatInitialImagePrewarmerTest > thumbnailPathsToPrewarmReturnsDistinctLocalPathsForImageMessagesOnly PASSED
  ```

  旧草案曾建议的同步实现如下，已废弃，因为它会阻塞 UI 线程等待 Coil 解码，实际触发 ANR：
  ```kotlin
  private fun prewarmImageThumbnails(context: Context, messages: List<ChatMessage>) {
      runBlocking {
          withContext(Dispatchers.IO) {
              for (message in messages) {
                  val path = message.localThumbnailPath ?: continue
                  val request = ChatLocalThumbnailRequest.build(context, path) ?: continue
                  imageLoader.execute(request)
              }
          }
      }
  }
  ```

  关键点：
  - 使用 `scope.launch(Dispatchers.IO)`，主线程只负责启动预热任务，不等待解码完成
  - bitmap 解码在 `Dispatchers.IO` 线程池执行，不在主线程
  - `execute()` 仍用于让 Coil 完整执行请求并写入内存缓存，但只在后台协程内调用
  - 只处理 `localThumbnailPath != null`——符合接收方严格缓存策略
  - 已加 `withTimeoutOrNull(300)` 保护后台任务，避免长时间占用预热协程
  - 由于不再阻塞导航，极冷缓存/超大图片场景仍可能有短暂灰色占位；这是为避免 ANR 做出的取舍，`ChatImageBubble.LaunchedEffect` 仍保留兜底

  **为什么不在主线程等待？** `Coil.imageLoader.execute()` 是 suspend 函数，包含磁盘读取 + bitmap 解码，属于 IO/CPU 密集操作。旧版 `runBlocking` 即使把实际解码放进 IO，也会让主线程等待结果；当 Coil 解码或取消不能及时返回时，会阻塞输入分发并触发 ANR。

## Rejected Alternatives

| 方案 | 拒绝原因 |
|------|---------|
| 移除 `LaunchedEffect` 完全依赖预加载 | 自预热仍需保留作为兜底——处理 `ChatViewModel.start()` 后新到达的图片、以及 `preloadInitialPageSync` 超时场景 |
| 改用 `remember` + `produceState` 在 composition 期间同步解码 | Compose 不允许在 composition 期间执行阻塞 I/O |
| 给 `loading` 状态加 loading indicator | 视觉上比灰色闪烁更差，且 `showInlineProgress()` 目前刻意关闭 |
| 添加导航过渡动画延迟 | 当前设计使用 `EnterTransition.None`，加动画引入延迟影响体验 |
| 主线程 `runBlocking` 等待 Coil 预热完成 | 实测会导致从会话列表进入图片聊天时 ANR |

## 不需要改动的部分

| 项目 | 原因 |
|------|------|
| `ChatImageBubble.LaunchedEffect` 自预热 | 保留作为兜底 |
| `ChatImageBubbleLoadingPolicy.showInlineProgress()` | 预热后不再需要 loading indicator |
| `ChatLocalThumbnailRequest` | 缓存键已正确共享 |
| Coil ImageLoader 配置 | 默认配置足够 |
| 灰色背景 `Color(0xFFE5E2E1)` | 预热成功后不再可见 |

## Verification

1. B 发 9 张图 → A 从会话列表点进聊天 → 首帧即显示缩略图，无灰色闪烁
2. 从通知弹窗点进有图片的聊天 → 同样无灰色闪烁
3. 进入纯文本聊天 → `ChatInitialImagePrewarmer.prewarmAsync` 提前 return，无额外开销
4. 再次进入同一聊天 → `execute()` 命中内存缓存立即返回，无延迟
5. 用 `seed_local_messages.py --per-peer 5000` 灌数据 → 首屏可见图片无闪烁
6. 杀进程后重新打开 → 通知/列表入口均无灰闪

## Pre-Fix Code Evidence

- [ChatImageBubble.kt:52-56](app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt#L52-L56) — `LaunchedEffect` 自预热与 `SubcomposeAsyncImage` 竞态
- [ChatImageBubble.kt:61](app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt#L61) — 灰色占位背景 `Color(0xFFE5E2E1)`
- [ChatImageBubble.kt:78-81](app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt#L78-L81) — `loading` 状态不渲染任何内容
- [ChatImageBubbleLoadingPolicy.kt:4](app/src/main/java/com/buyansong/im/chat/ChatImageBubbleLoadingPolicy.kt#L4) — `showInlineProgress()` 硬编码 `false`
- [MainActivity.kt:459-464](app/src/main/java/com/buyansong/im/MainActivity.kt#L459-L464) — `openPreloadedChat` lambda，只调用 `preloadInitialPageSync` 未预热 Coil
- [ChatScreen.kt:510-520](app/src/main/java/com/buyansong/im/chat/ChatScreen.kt#L510-L520) — 发送方 `prewarmOutgoingLocalThumbnails`，可复用的预热模式
- [ChatLocalThumbnailRequest.kt:7-20](app/src/main/java/com/buyansong/im/chat/ChatLocalThumbnailRequest.kt#L7-L20) — 统一请求构建器，缓存键共享
