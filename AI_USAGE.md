# AI_USAGE

本文记录本项目中使用 AI 辅助方案设计、排查和文档整理的典型案例。

## 使用方式概览

AI 主要用于：

- 拆解自研 IM 的功能路线和实现步骤。
- 对协议、存储、可靠性、UI 状态流进行方案对比。
- 根据日志、现象和源码路径辅助定位 bug 根因。
- 把实现过程沉淀为 `docs/status/`、`docs/feature-notes/` 和 `docs/bug/`。
- 在每次实现后补充状态文档、验证清单和后续风险。

当前最终状态以 [`docs/bg/DEVELOPMENT_STATUS.md`](docs/bg/DEVELOPMENT_STATUS.md)、[`docs/status/`](docs/status/) 和 [`docs/feature-notes/`](docs/feature-notes/) 为准。

## AI 方案采纳记录

### 1. 采纳：先做本地 SQLite 历史分页，暂缓服务端历史查询

**AI 建议：**  
聊天页先基于本地 SQLite 做 `beforeTime + limit` 的游标分页，服务端 `HISTORY_QUERY` / `HISTORY_RESULT` 先保留协议枚举，不急于实现远端历史闭环。

**是否采纳：**  
采纳。

**理由：**

- 项目验收优先要求聊天页能上拉加载历史消息，本地消息已经落库，先打通本地分页风险更低。
- 远端历史需要补 server accepted message 查询、协议响应、客户端合并和冲突处理，复杂度更高。
- 先保留协议枚举，后续接入服务端 history 时不会破坏协议扩展方向。

**落地结果：**

- `ChatViewModel.start()` 首次加载最近 20 条本地消息。
- `loadMoreHistory()` 使用最早消息的 `createdAt` 作为 cursor。
- 服务端历史仍未接入，但协议枚举保留。

**参考：**

- [`docs/status/B4-history-pagination.md`](docs/status/B4-history-pagination.md)
- [`docs/feature-notes/B4-history-pagination-design-notes.md`](docs/feature-notes/B4-history-pagination-design-notes.md)

### 2. 采纳：把 `MESSAGE_ACK`、`DELIVERY_ACK`、`READ_ACK` 分成三层语义

**AI 建议：**  
不要把服务端 ACK、设备投递确认、用户已读混成一个状态。分别定义：

- `MESSAGE_ACK`：服务端接收并分配 `serverSeq`。
- `DELIVERY_ACK`：接收设备已经把消息持久化到本地。
- `READ_ACK`：用户已读到某个会话游标。

**是否采纳：**  
采纳。

**理由：**

- IM 可靠性里“服务端收到”“设备收到”“用户已读”是三个不同事实。
- 混用会导致 UI 错误，例如把 delivery 当 read。
- 拆开后可以支持离线重放、已读回执和群已读游标。

**落地结果：**

- B9 处理 sender-side ACK 和重试。
- B9.5 增加 receiver-side `DELIVERY_ACK` 和服务端未投递消息恢复。
- B12 复用 `READ_ACK` 做单聊已读和群聊 read cursor。

**参考：**

- [`docs/status/B9-message-reliability.md`](docs/status/B9-message-reliability.md)
- [`docs/status/B9.5-delivery-ack.md`](docs/status/B9.5-delivery-ack.md)
- [`docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)

### 3. 采纳：用服务端 `serverSeq` 做会话内权威排序

**AI 建议：**  
客户端可以继续生成 `clientSeq` 用于本地发送和 ACK correlation，但最终展示排序应以服务端按会话分配的 `serverSeq` 为准。

**是否采纳：**  
采纳。

**理由：**

- 多端、多发送方和群聊场景下，客户端本地 seq 无法表示全局顺序。
- 服务端统一分配 `serverSeq` 后，接收端可稳定排序。
- 发送中消息仍可用本地 `createdAt/clientSeq` 临时展示，ACK 后再进入确认顺序。

**落地结果：**

- mock-server 按会话分配 `serverSeq`。
- Android 查询和合并路径按 `serverSeq` 排序。
- `clientSeq` 降级为本地关联元数据。

**参考：**

- [`docs/status/B8-message-ordering.md`](docs/status/B8-message-ordering.md)
- [`docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)

### 4. 采纳：用会话级 `MessagePacketProcessor` 统一接收入站消息

**AI 建议：**  
不要让 `ChatViewModel`、`ConversationListViewModel` 等页面分别 collect WebSocket incoming packets。应由登录会话级 processor 统一收包、持久化，再通过 repository update event 刷新 UI。

**是否采纳：**  
采纳。

**理由：**

- 页面 ViewModel 生命周期短，依赖页面收包会导致切到 Contacts/Me 时丢消息。
- 多个 collector 同时处理同一包会导致重复插入、重复 ACK 或状态竞争。
- 登录会话级处理器更符合 IM 客户端“只要登录就持续接收消息”的语义。

**落地结果：**

- `MessagePacketProcessor` 负责 WebSocket message packet 处理。
- 页面 ViewModel 只从本地存储或 repository update signal 刷新。
- B9.5 文档记录了该改动修复重复 `DELIVERY_ACK` 的问题。

**参考：**

- [`docs/status/B9.5-delivery-ack.md`](docs/status/B9.5-delivery-ack.md)
- [`docs/bg/PROJECT_BACKGROUND.md`](docs/bg/PROJECT_BACKGROUND.md)

### 5. 采纳：图片入口预热采用“可等待但有硬上限”的方案

**AI 建议：**  
不要恢复主线程 `runBlocking` 等图片解码；改为导航前最多等待一个短预算，在 IO dispatcher 并发预热首屏本地缩略图，超时则继续导航。

**是否采纳：**  
采纳。

**理由：**

- 直接阻塞主线程会造成 ANR 或明显卡顿。
- 完全 fire-and-forget 又无法保证首帧命中 Coil 内存缓存，容易先灰后图。
- “短等待 + 硬上限 + 并发限制”能在体验和响应速度之间折中。

**落地结果：**

- `ChatInitialImagePrewarmer.prewarmBeforeNavigation(...)` 导航前预热。
- `openPreloadedChat` 先预读消息元数据，再短预算预热缩略图，最后导航。
- 冷缓存下减少灰色占位首帧，极端情况仍按超时兜底。

**参考：**

- [`docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md`](docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md)
- [`docs/feature-notes/coil-image-preload-mechanism.md`](docs/feature-notes/coil-image-preload-mechanism.md)

## 总结

AI 在本项目中的作用主要是帮助拆解复杂链路、暴露隐藏边界、比较方案取舍和形成文档化记录。最终是否采纳以项目约束、实现复杂度、可验证性和当前交付目标为准。
