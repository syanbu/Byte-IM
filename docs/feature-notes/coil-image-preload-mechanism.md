# Coil 图片预加载机制设计

## 背景

在图片消息发送和显示的过程中，存在两个性能问题：

1. **发送方热发送闪烁问题**：用户选择图片并点击发送后，新创建的图片气泡会短暂显示灰色占位符，然后才显示本地缩略图
2. **聊天列表滚动性能问题**：新进入视野的图片气泡在首次显示时需要解码本地文件，可能造成轻微的滚动卡顿

Coil 预加载机制通过多层次的缓存预热策略解决了这些问题。

---

## 核心机制概述

本项目采用**双层预加载策略**确保图片显示的流畅性：

| 层级 | 触发时机 | 作用范围 | 目标 |
|------|----------|----------|------|
| **发送前预暖** | 用户点击发送后、本地消息插入数据库前 | 本次即将发送的所有图片 | 完全消除气泡首次显示时的灰色占位符 |
| **气泡自预加载** | `ChatImageBubble` 首次组合时 | 每个进入视野的图片气泡 | 确保滚动过程中新出现的图片解码无延迟 |

---

## 一、发送方热发送预暖 (Sender-side Hot-send Prewarm)

### 问题描述

在修复之前，图片发送流程是：
1. 用户选择图片
2. `ChatImageCompressor` 生成本地原图和缩略图文件
3. 调用 `ChatViewModel.sendImages()`
4. 仓库创建本地 `UPLOADING` 状态的消息行
5. Compose 重组绘制图片气泡
6. **此时 Coil 才开始解码本地缩略图文件**

由于步骤 5 和 6 之间存在微小的时间窗口（几毫秒到几十毫秒），气泡在 Coil 解码完成之前会先绘制稳定的灰色背景，造成可见的闪烁。

### 解决方案

在步骤 3 **之前**插入预暖步骤，将流程改为：

1. 用户选择图片
2. `ChatImageCompressor` 生成本地原图和缩略图文件
3. **对所有 `preparedImages` 的 `localThumbnailPath` 执行 Coil 预加载**
4. 调用 `ChatViewModel.sendImages()`
5. 仓库创建本地 `UPLOADING` 状态的消息行
6. Compose 重组绘制图片气泡
7. Coil 直接从内存缓存中获取已解码的位图，立即显示

### 关键时序要点

> **预暖必须在 `sendImages()` 之前执行**

因为 `sendImages()` 内部会：
- 调用 `repository.createLocalImageMessage()` 插入数据库
- 触发 `refreshKeepingHistory()` 将新消息发射到 UI 状态

如果预暖发生在这之后，Compose 可能已经开始了第一帧绘制，预暖就失去了意义。

---

## 二、每个气泡自预加载 (Per-bubble Self-preload)

### 历史沿革

**旧方案（已废弃）**：
- 从对话列表进入聊天时，预加载最近 5/10 张本地缩略图
- 位置：`ChatThumbnailPreloader`
- 问题：
  - 启动太晚（导航后才开始）
  - 不可靠的 fire-and-forget 路径（`enqueue` 无结果回调）
  - 硬编码只支持单聊，不支持群聊

**新方案（当前实现）**：
- 每个 `ChatImageBubble` 在首次组合时自行触发预加载
- 位置：`ChatImageBubble` 内部的 `LaunchedEffect`

### 实现细节

```kotlin
LaunchedEffect(message.localThumbnailPath) {
    if (message.localThumbnailPath != null && message.localThumbnailPath.startsWith("/")) {
        Coil.imageLoader(context).execute(
            ChatLocalThumbnailRequest.build(context, message.localThumbnailPath)
        )
    }
}
```

**设计要点**：
1. **仅处理本地文件**：路径以 `/` 开头才执行预加载
2. **使用 `execute` 而非 `enqueue`**：协程可以观察解码结果，Coil 通过共享缓存键与后续 `SubcomposeAsyncImage` 请求自动去重
3. **统一的 Request**：使用与渲染时完全相同的 `ChatLocalThumbnailRequest`
4. **无跨 ViewModel 依赖**：`ConversationListViewModel` 不再携带预加载逻辑

### 适用场景

这个机制主要服务于：
- 接收方：聊天历史中滚动出现的新图片气泡
- 发送方：用户重新进入之前的聊天，历史消息中的图片气泡

---

## 三、ChatLocalThumbnailRequest 统一请求

### 设计目标

确保预暖和渲染使用完全相同的缓存键，命中同一个缓存条目。

### 核心参数

```kotlin
object ChatLocalThumbnailRequest {
    fun build(context: Context, localPath: String): ImageRequest {
        return ImageRequest.Builder(context)
            .data(localPath)
            .memoryCacheKey(localPath)       // 显式指定内存缓存键
            .diskCacheKey(localPath)         // 显式指定磁盘缓存键
            .size(Size.ORIGINAL)             // 使用原始尺寸，避免缩放
            .build()
    }
}
```

### 为什么需要显式指定 CacheKey

Coil 默认会根据 `data`、`size`、`transformations` 等参数自动计算缓存键。但显式指定有以下好处：

1. **确定性**：预暖和渲染使用完全相同的键，不会因为参数差异导致缓存未命中
2. **可读性**：代码意图明确，后续维护者可以直接看到缓存策略
3. **一致性**：即使 Coil 未来版本改变默认计算逻辑，本项目行为不变

---

## 四、与接收方严格缓存策略的配合

### 接收方策略回顾

接收方采用**严格的缩略图缓存策略**：
- 收到图片消息时，先持久化 URL 和元数据，`localThumbnailPath = null`
- 通过 `ThumbnailDownloadScheduler` 异步下载缩略图到本地文件
- 只有在 `localThumbnailPath` 写入后，消息才会出现在聊天历史中
- 在此之前，图片消息对用户完全不可见

### 预加载机制的边界

> **接收方的预加载永远是本地文件解码，不涉及网络**

因为在严格的策略下，能够进入聊天历史的图片消息，必然已经有了 `localThumbnailPath`，所以：
- 气泡的自预加载只需要解码本地文件
- 网络下载的责任完全在 `ThumbnailDownloadScheduler`
- 预加载机制不承担任何网络获取的职责

---

## 五、效果验证

### 预期效果

1. **发送方热发送**：点击发送后，新气泡直接显示缩略图，完全看不到灰色占位符
2. **聊天滚动**：新进入视野的图片气泡显示流畅，无明显的解码延迟
3. **接收方**：缩略图下载完成后显示时无闪烁

### 验证方法

1. 在模拟器或真机上选择 3-9 张图片一次性发送
2. 观察新出现的气泡是否直接显示图片（无灰色过渡）
3. 快速滚动聊天列表，观察图片显示是否平滑

---

## 六、相关文件

| 文件 | 说明 |
|------|------|
| `ChatScreen.kt` | 发送前预暖的调用位置 |
| `ChatImageBubble.kt` | 气泡自预加载的实现位置 |
| `ChatImageCompressor.kt` | 本地缩略图文件生成逻辑 |
| `ChatViewModel.kt` | `sendImages()` 方法，预暖必须在其调用之前 |

---

## 变更历史

- **2026-06-07**：废弃全局预加载器，改为每个气泡自预加载
- **2026-06-10**：新增发送方热发送预暖，消除发送瞬间的灰色占位符闪烁
