# Fix: 聊天入口等待图片预热后再导航

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从会话列表、消息弹窗、已加入群、联系人资料等入口进入已有图片消息的单聊/群聊时，尽量让首帧直接命中 Coil 内存缓存，避免图片先显示灰色占位再渲染。

**Architecture:** 保留现有 `preloadInitialPageSync()` 消息元数据预读和 `ChatViewModel.init` 缓存 hydrate；新增一个可等待但有硬上限的图片预热 API。`openPreloadedChat` 改为在 `uiScope.launch` 中执行：先同步预读消息元数据，再在 `Dispatchers.IO` 上限时并发解码首屏本地缩略图，完成或超时后再导航。

**Tech Stack:** Android Kotlin, Jetpack Compose Navigation, Kotlin Coroutines, Coil, JUnit.

---

## Status

- Status: Implemented
- Created on: 2026-06-11
- Branch: `rename-redesign-ui`
- Scope: `MainActivity.openPreloadedChat`, `ChatInitialImagePrewarmer`, 单聊/群聊已有图片消息入口首帧渲染

## Implementation Summary

- `ChatInitialImagePrewarmer` 新增 `prewarmBeforeNavigation(...)`，导航前最多等待 `700ms`，只处理前 `12` 个去重后的本地缩略图路径，并按每批 `3` 个限制并发解码。
- `openPreloadedChat` 改为在 `uiScope.launch` 中执行：`preloadInitialPageSync()` → `prewarmBeforeNavigation()` → `navigateToChat()`。
- 保留 `prewarmAsync()`，后续如需后台 fire-and-forget 预热仍可使用。
- 新增单测覆盖去重后数量限制，以及是否需要导航前预热的纯逻辑判断。

## Verification

- `.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatInitialImagePrewarmerTest" --tests "com.buyansong.im.chat.ChatViewModelInitialCacheTest" --tests "com.buyansong.im.message.MessageRepositoryCacheTest"` → `BUILD SUCCESSFUL`
- `.\gradlew assembleDebug` → `BUILD SUCCESSFUL`

## 重新审查结论

### 1. “已经缓存了，是不是就不需要解码？”

要区分两层缓存：

| 缓存 | 当前是否已实现 | 解决什么 | 是否等于图片已解码 |
|------|----------------|----------|--------------------|
| `MessageRepository.initialPageCache` | 已实现 | 消息元数据首帧可用：路径、尺寸、URL、状态 | 否 |
| Coil memory cache | 部分预热，但当前不等待 | Bitmap 首帧可直接显示 | 是 |

`preloadInitialPageSync()` 只保证 `ChatMessage.localThumbnailPath` 等元数据已经在内存里。它不能保证本地缩略图文件已经被 Coil 解码成 bitmap。

如果 Coil memory cache 已经有对应 `memoryCacheKey(localThumbnailPath)`，新增的等待预热会很快返回，几乎没有额外延迟。如果只有消息元数据缓存，没有 Coil bitmap 缓存，仍然需要解码。

### 2. “新发的消息是不是总要解码？”

发送方已有单独机制：`ChatScreen.prewarmOutgoingLocalThumbnails()` 在 `viewModel.sendImages(preparedImages)` 之前同步等待 Coil `execute()` 完成。因此发送方自己发出的 9 张图，通常不会看到灰色占位。

接收方不同。比如 B 在群聊发 9 张图，A 收到后：

1. 缩略图先由 `ThumbnailDownloadScheduler` 下载到本地。
2. SQLite 消息行写入 `localThumbnailPath`。
3. A 从会话列表进入群聊时，`preloadInitialPageSync()` 只能拿到这些路径。
4. 如果 A 之前没看过这些图，Coil memory cache 是冷的。
5. 当前 `ChatInitialImagePrewarmer.prewarmAsync()` 只在后台抢跑，不等待。
6. `SubcomposeAsyncImage` 首帧可能先 cache miss，于是灰色背景透出。

所以“B 发 9 张图，A 第一次进去总是会先灰后图”并不是消息缓存失效，而是接收方 bitmap 解码没有在导航前完成。

### 3. 当前实现为什么又暴露灰色占位？

历史提交 `5612033 fix: Chat Image Gray Placeholder On ChatEntry` 确实加了 `ChatInitialImagePrewarmer`，但文档 [Fix-ChatImageGrayPlaceholderOnChatEntry.md](Fix-ChatImageGrayPlaceholderOnChatEntry.md) 已记录：旧版主线程 `runBlocking` 等 Coil 解码会 ANR，所以当前实现改为非阻塞预热。

当前代码：

```kotlin
val openPreloadedChat: (String) -> Unit = remember(context, uiScope, messageRepository, navController) {
    { conversationId ->
        val messages = messageRepository.preloadInitialPageSync(conversationId)
        ChatInitialImagePrewarmer.prewarmAsync(uiScope, context, messages)
        SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
    }
}
```

这里 `prewarmAsync()` 没有被等待，导航立即发生。它降低了灰闪概率，但不能保证首帧命中 Coil。

## 目标行为

1. 从会话列表进入已有图片的单聊：最多等待一个很短预算，图片预热成功则首帧直接显示缩略图。
2. 从会话列表进入已有图片的群聊：同样预热 `group:<groupId>` 初始页中的本地缩略图。
3. 从消息弹窗 / push deep link 进入聊天：复用同一 `openPreloadedChat` 逻辑。
4. 从联系人资料发起单聊：复用同一 `openPreloadedChat` 逻辑。
5. 已经命中 Coil memory cache：预热 API 快速返回，几乎不增加入口延迟。
6. 极冷缓存、大图、低端机、图片很多：最多等待硬上限，到点导航，保留现有 `ChatImageBubble.LaunchedEffect` 和 `SubcomposeAsyncImage` 兜底。
7. 纯文本聊天：无缩略图路径，直接导航。

## Non-Goals

- 不恢复主线程 `runBlocking` 等图片解码。
- 不等待所有历史图片，只处理初始页中有限数量的本地缩略图。
- 不做网络下载。预热只处理 `localThumbnailPath`，网络缩略图下载仍由现有 `ThumbnailDownloadScheduler` 负责。
- 不改 `ChatImageBubble` 的灰色背景样式；本计划解决的是 bitmap 缓存时序。
- 不改变发送方 `prewarmOutgoingLocalThumbnails()` 的现有行为。

## File Structure

| 文件 | 职责 |
|------|------|
| `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt` | 新增可等待的导航前预热 API，限制数量、限时、限制并发 |
| `app/src/main/java/com/buyansong/im/MainActivity.kt` | 将 `openPreloadedChat` 改为 `uiScope.launch` 中顺序执行预读、预热、导航 |
| `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt` | 覆盖路径筛选、数量限制、超时/无路径快速返回等纯逻辑 |
| `docs/bug/Fix-ChatImageGrayPlaceholderOnChatEntry.md` | 可选更新，记录本计划替代当前 fire-and-forget 入口预热 |

## Design Details

### 预热窗口

推荐默认值：

```kotlin
private const val PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 700L
private const val MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 12
private const val MAX_PREWARM_CONCURRENCY = 3
```

原因：

- `500ms` 对 9 张冷缓存缩略图可能偏紧。
- `800ms` 以上点击延迟开始明显。
- `700ms` 是首版折中；如果真机验证仍偶发灰闪，可调到 `800ms`。
- 并发 `3` 比串行更容易覆盖 9 张图，但不会像一次性 `async` 12 个那样把 IO/解码打满。

### 预热 API 形态

新增 suspend API：

```kotlin
object ChatInitialImagePrewarmer {
    private const val PREWARM_TIMEOUT_MS = 300L
    private const val PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 700L
    private const val MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 12
    private const val MAX_PREWARM_CONCURRENCY = 3

    suspend fun prewarmBeforeNavigation(
        context: Context,
        messages: List<ChatMessage>
    ) {
        prewarmBeforeNavigation(
            context = context,
            messages = messages,
            timeoutMs = PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS,
            maxImages = MAX_PREWARM_BEFORE_NAVIGATION_IMAGES,
            maxConcurrency = MAX_PREWARM_CONCURRENCY
        )
    }

    internal suspend fun prewarmBeforeNavigation(
        context: Context,
        messages: List<ChatMessage>,
        timeoutMs: Long,
        maxImages: Int,
        maxConcurrency: Int
    ) {
        val thumbnailPaths = thumbnailPathsToPrewarm(messages).take(maxImages)
        if (thumbnailPaths.isEmpty()) {
            return
        }
        val appContext = context.applicationContext
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                val imageLoader = Coil.imageLoader(appContext)
                thumbnailPaths.chunked(maxConcurrency.coerceAtLeast(1)).forEach { batch ->
                    coroutineScope {
                        batch.map { path ->
                            async {
                                val request = ChatLocalThumbnailRequest.build(appContext, path) ?: return@async
                                imageLoader.execute(request)
                            }
                        }.awaitAll()
                    }
                }
            }
        }
    }
}
```

保留现有 `prewarmAsync()`，用于不想等待的兜底场景，也兼容未来可能的后台预热调用。

### 导航入口形态

`openPreloadedChat` 改为非阻塞主线程的协程顺序：

```kotlin
val openPreloadedChat: (String) -> Unit = remember(context, uiScope, messageRepository, navController) {
    { conversationId ->
        uiScope.launch {
            val messages = messageRepository.preloadInitialPageSync(conversationId)
            ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)
            SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
        }
    }
}
```

说明：

- `uiScope.launch` 让点击处理函数立即返回，不阻塞 Compose 点击回调。
- `preloadInitialPageSync()` 当前内部仍有 `100ms` 硬上限，保持原有消息首帧优化。
- `prewarmBeforeNavigation()` 是 suspend，主线程挂起等待，不阻塞 Looper；真实解码在 `Dispatchers.IO`。
- 如果解码提前完成，立即导航。
- 如果超时，`withTimeoutOrNull` 返回，继续导航。

## Implementation Plan

### Task 1: 扩展路径筛选逻辑，支持导航前数量限制

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt`

- [ ] **Step 1: 添加失败测试，确认只取前 N 个去重后的图片缩略图路径**

在 `ChatInitialImagePrewarmerTest` 中新增：

```kotlin
@Test
fun thumbnailPathsToPrewarmCanBeLimitedAfterDistinctFiltering() {
    val messages = listOf(
        message("text", type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg"),
        message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
        message("image-2", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg"),
        message("image-3", type = MessageType.IMAGE, localThumbnailPath = "/cache/b.jpg"),
        message("image-4", type = MessageType.IMAGE, localThumbnailPath = "/cache/c.jpg")
    )

    assertEquals(
        listOf("/cache/a.jpg", "/cache/b.jpg"),
        ChatInitialImagePrewarmer.thumbnailPathsToPrewarm(messages, maxImages = 2)
    )
}
```

- [ ] **Step 2: 运行单测确认失败**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatInitialImagePrewarmerTest"
```

Expected: 编译失败，提示 `thumbnailPathsToPrewarm(messages, maxImages = 2)` 没有匹配的重载。

- [ ] **Step 3: 实现带数量限制的重载**

在 `ChatInitialImagePrewarmer.kt` 中保留现有函数，并新增：

```kotlin
fun thumbnailPathsToPrewarm(messages: List<ChatMessage>, maxImages: Int): List<String> {
    if (maxImages <= 0) {
        return emptyList()
    }
    return thumbnailPathsToPrewarm(messages).take(maxImages)
}
```

- [ ] **Step 4: 运行单测确认通过**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatInitialImagePrewarmerTest"
```

Expected: `ChatInitialImagePrewarmerTest` 全部通过。

### Task 2: 新增可等待的导航前图片预热 API

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/chat/ChatInitialImagePrewarmer.kt`
- Modify: `app/src/test/java/com/buyansong/im/chat/ChatInitialImagePrewarmerTest.kt`

- [ ] **Step 1: 添加纯逻辑测试，确认空消息和非图片消息不需要等待预热**

新增一个不依赖 Android `Context` 的判断函数测试：

```kotlin
@Test
fun shouldPrewarmBeforeNavigationReturnsFalseWhenThereAreNoLocalImageThumbnails() {
    val messages = listOf(
        message("text", type = MessageType.TEXT, localThumbnailPath = "/cache/text.jpg"),
        message("image-empty", type = MessageType.IMAGE, localThumbnailPath = null)
    )

    assertEquals(false, ChatInitialImagePrewarmer.shouldPrewarmBeforeNavigation(messages))
}

@Test
fun shouldPrewarmBeforeNavigationReturnsTrueWhenLocalImageThumbnailExists() {
    val messages = listOf(
        message("image-1", type = MessageType.IMAGE, localThumbnailPath = "/cache/a.jpg")
    )

    assertEquals(true, ChatInitialImagePrewarmer.shouldPrewarmBeforeNavigation(messages))
}
```

- [ ] **Step 2: 运行单测确认失败**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatInitialImagePrewarmerTest"
```

Expected: 编译失败，提示 `shouldPrewarmBeforeNavigation` 未定义。

- [ ] **Step 3: 实现判断函数和 suspend API**

在 `ChatInitialImagePrewarmer.kt` 中增加 imports：

```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
```

扩展对象：

```kotlin
object ChatInitialImagePrewarmer {
    private const val PREWARM_TIMEOUT_MS = 300L
    private const val PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS = 700L
    private const val MAX_PREWARM_BEFORE_NAVIGATION_IMAGES = 12
    private const val MAX_PREWARM_CONCURRENCY = 3

    fun shouldPrewarmBeforeNavigation(messages: List<ChatMessage>): Boolean {
        return thumbnailPathsToPrewarm(messages, maxImages = 1).isNotEmpty()
    }

    suspend fun prewarmBeforeNavigation(
        context: Context,
        messages: List<ChatMessage>
    ) {
        prewarmBeforeNavigation(
            context = context,
            messages = messages,
            timeoutMs = PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS,
            maxImages = MAX_PREWARM_BEFORE_NAVIGATION_IMAGES,
            maxConcurrency = MAX_PREWARM_CONCURRENCY
        )
    }

    internal suspend fun prewarmBeforeNavigation(
        context: Context,
        messages: List<ChatMessage>,
        timeoutMs: Long,
        maxImages: Int,
        maxConcurrency: Int
    ) {
        val thumbnailPaths = thumbnailPathsToPrewarm(messages, maxImages)
        if (thumbnailPaths.isEmpty()) {
            return
        }

        val appContext = context.applicationContext
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                val imageLoader = Coil.imageLoader(appContext)
                thumbnailPaths
                    .chunked(maxConcurrency.coerceAtLeast(1))
                    .forEach { batch ->
                        coroutineScope {
                            batch.map { path ->
                                async {
                                    val request = ChatLocalThumbnailRequest.build(appContext, path) ?: return@async
                                    imageLoader.execute(request)
                                }
                            }.awaitAll()
                        }
                    }
            }
        }
    }

    fun prewarmAsync(scope: CoroutineScope, context: Context, messages: List<ChatMessage>) {
        val thumbnailPaths = thumbnailPathsToPrewarm(messages)
        if (thumbnailPaths.isEmpty()) {
            return
        }

        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            withTimeoutOrNull(PREWARM_TIMEOUT_MS) {
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

- [ ] **Step 4: 运行单测确认通过**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatInitialImagePrewarmerTest"
```

Expected: `ChatInitialImagePrewarmerTest` 全部通过。

### Task 3: 将 openPreloadedChat 改成协程内等待预热后导航

**Files:**

- Modify: `app/src/main/java/com/buyansong/im/MainActivity.kt`

- [ ] **Step 1: 修改 `openPreloadedChat`**

将当前实现：

```kotlin
val openPreloadedChat: (String) -> Unit = remember(context, uiScope, messageRepository, navController) {
    { conversationId ->
        val messages = messageRepository.preloadInitialPageSync(conversationId)
        ChatInitialImagePrewarmer.prewarmAsync(uiScope, context, messages)
        SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
    }
}
```

改为：

```kotlin
val openPreloadedChat: (String) -> Unit = remember(context, uiScope, messageRepository, navController) {
    { conversationId ->
        uiScope.launch {
            val messages = messageRepository.preloadInitialPageSync(conversationId)
            ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)
            SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
        }
    }
}
```

`uiScope` 已在当前文件中使用；如果文件顶部没有 `kotlinx.coroutines.launch` import，使用全限定调用：

```kotlin
uiScope.launch {
    val messages = messageRepository.preloadInitialPageSync(conversationId)
    ChatInitialImagePrewarmer.prewarmBeforeNavigation(context, messages)
    SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)
}
```

- [ ] **Step 2: 检查所有入口仍复用 `openPreloadedChat`**

Run:

```powershell
rg -n "openPreloadedChat|navigateToChat" app/src/main/java/com/buyansong/im/MainActivity.kt
```

Expected:

- 会话列表 `onOpenConversation` 调用 `openPreloadedChat(conversationId)`。
- 已加入群 `onOpenGroup` 调用 `openPreloadedChat("group:$groupId")`。
- 联系人资料 `onSendMessage` 调用 `openPreloadedChat(...)`。
- `MessageAlertHost` 调用 `openPreloadedChat(conversationId)`。
- `pushDeepLink` 调用 `openPreloadedChat(conversationId)`。
- 新建群成功仍可直接 `navigateToChat`，因为新群没有历史图片需要预热。

### Task 4: 编译与回归测试

**Files:**

- Test only.

- [ ] **Step 1: 运行图片预热单测**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatInitialImagePrewarmerTest"
```

Expected: 所有测试通过。

- [ ] **Step 2: 运行聊天初始缓存单测**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.chat.ChatViewModelInitialCacheTest"
```

Expected: 所有测试通过，证明 `ChatViewModel.init` 从消息元数据缓存首帧 hydrate 的行为未被破坏。

- [ ] **Step 3: 运行消息缓存单测**

Run:

```powershell
.\gradlew testDebugUnitTest --tests "com.buyansong.im.message.MessageRepositoryCacheTest"
```

Expected: 所有测试通过，证明 `preloadInitialPageSync()` 和 cache invalidation 未被破坏。

- [ ] **Step 4: 编译 Debug**

Run:

```powershell
.\gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`。

### Task 5: 真机/模拟器验证

**Files:**

- No code changes.

- [ ] **Step 1: 冷缓存接收方群聊验证**

步骤：

1. B 在群聊连续发送 9 张图片。
2. A 等缩略图下载完成，并停留在会话列表。
3. A 杀进程重启，确保 Coil memory cache 冷。
4. A 从会话列表点击该群聊。

Expected:

- 点击后允许有短暂停顿。
- 聊天页打开首帧尽量直接显示缩略图。
- 若低端机或图片过大导致超过 700ms，允许少量图片仍短暂灰闪，但不允许入口卡死或 ANR。

- [ ] **Step 2: 已缓存重入验证**

步骤：

1. A 进入同一群聊一次，等待图片全部显示。
2. 返回会话列表。
3. 再次进入同一群聊。

Expected:

- Coil memory cache 命中，入口等待时间几乎不可感知。
- 图片首帧直接显示。

- [ ] **Step 3: 单聊接收方验证**

步骤：

1. B 在单聊给 A 发送 3-9 张图片。
2. A 从会话列表进入该单聊。

Expected:

- 行为与群聊一致。
- 文字消息和图片消息顺序不变。

- [ ] **Step 4: 纯文本聊天验证**

步骤：

1. A 从会话列表进入一个只有文本的聊天。

Expected:

- 无明显额外等待。
- 消息首帧仍由 `preloadInitialPageSync()` 和 `ChatViewModel.init` 缓存 hydrate 保证。

## Risk Analysis

| 风险 | 影响 | 缓解 |
|------|------|------|
| 入口等待变长 | 用户感觉点击后慢半拍 | `700ms` 硬上限；已缓存时快速返回；纯文本直接跳过 |
| 多图并发解码占用资源 | 低端机可能掉帧或 IO 压力大 | 限制前 `12` 张，限制并发 `3` |
| 超时取消 Coil execute | 部分图片仍灰闪 | 保留 `ChatImageBubble.LaunchedEffect` 和 `SubcomposeAsyncImage` 正常加载兜底 |
| 消息预读返回空列表 | 无法预热图片 | 直接导航，保持现有异步刷新行为 |
| 新建群成功入口未预热 | 理论上绕过统一入口 | 新群无历史图片；如果未来创建后自动带图片，再改 `GroupCreate` 成 `openPreloadedChat` |

## Rollback Strategy

如果真机验证发现点击等待不可接受：

1. 将 `PREWARM_BEFORE_NAVIGATION_TIMEOUT_MS` 从 `700L` 降到 `500L`。
2. 将 `MAX_PREWARM_BEFORE_NAVIGATION_IMAGES` 从 `12` 降到 `9`。
3. 将 `MAX_PREWARM_CONCURRENCY` 从 `3` 降到 `2`。
4. 如果仍不可接受，`openPreloadedChat` 恢复为 `prewarmAsync` fire-and-forget，保留新增 API 但不在入口等待。

## Related Docs

- [Fix-ChatImageGrayPlaceholderOnChatEntry.md](Fix-ChatImageGrayPlaceholderOnChatEntry.md)
- [Fix-ChatImageGrayscalePlaceholderOnNotificationEntry.md](Fix-ChatImageGrayscalePlaceholderOnNotificationEntry.md)
- [Fix-ChatInitialBlankScreenOptimization.md](Fix-ChatInitialBlankScreenOptimization.md)
- [../feature-notes/coil-image-preload-mechanism.md](../feature-notes/coil-image-preload-mechanism.md)

## Self-Review

- Spec coverage: 覆盖了“已缓存是否还要解码”“新接收图片为什么仍灰闪”“单聊/群聊入口如何统一”“等待上限与并发限制”。
- Placeholder scan: 本文没有未填内容或空泛“后续处理”步骤；每个实现任务都有具体文件、代码片段、命令和期望结果。
- Type consistency: 使用现有类型 `ChatInitialImagePrewarmer`、`ChatMessage`、`ChatLocalThumbnailRequest`、`SelfHostedImRoute.Chat.createRoute`、`NavHostController.navigateToChat`；新增 API 名称在各任务中保持一致。
