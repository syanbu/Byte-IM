# BUGFIX

本文汇总项目中的真实 bug 修复案例，重点记录现象、排查过程、根因和修复结果。详细原始记录见 [`docs/bug/`](docs/bug/)。

## 案例 1：WebSocket 鉴权失败后仍处理业务包

**来源：** [`docs/bug/Fix-WebSocketAuthFailureAndUnauthenticatedMessageHandling.md`](docs/bug/Fix-WebSocketAuthFailureAndUnauthenticatedMessageHandling.md)

### 现象

- access token 过期后，mock-server 日志出现 `AUTH rejected invalid or expired token`。
- Android 不再进入 `Authenticated`，心跳停止。
- 更危险的是，未认证 WebSocket 连接仍能触发 `SEND_MESSAGE` 处理。
- 发送方收不到 `MESSAGE_ACK`，接收方被当作离线，消息进入离线队列。

### 排查过程

1. 从 mock-server 日志确认认证失败发生在业务消息之前。
2. 检查服务端 WebSocket packet handler，发现 auth 失败后连接没有明确关闭。
3. 检查业务包处理路径，发现未认证连接仍可能从 packet body 中读取 `senderId` / `receiverId`。
4. 检查 Android 重连逻辑，发现重连可能复用旧的内存 access token，而不是重新解析 fresh session。
5. 对照心跳状态机，确认“心跳停止”不是根因，而是 WebSocket 一直无法重新认证的结果。

### 根因

- 服务端 auth 边界不够硬：`AUTH` 失败后没有 `AUTH_NACK` 和主动关闭。
- 服务端业务处理没有强制要求 socket 已认证。
- Android WebSocket reconnect 使用了可能过期的 session snapshot。

### 修复

- 增加 `AUTH_NACK(reason)`。
- mock-server 在 auth 失败后发送 `AUTH_NACK` 并关闭 WebSocket。
- mock-server 拒绝未认证的 `SEND_MESSAGE`、`DELIVERY_ACK` 和 `HEARTBEAT`。
- Android 增加 `AuthRepository.ensureValidSession()`，所有 connect/reconnect 都通过统一入口获取 fresh access token。
- Android 处理 `AUTH_NACK`，不可恢复时清理本地 session 并回到登录态。

### 结果

- 过期 token 不再导致无限 stale reconnect。
- 未认证 socket 不能发送业务消息。
- 心跳在重新认证成功后自然恢复。

## 案例 2：撤回通知没有可靠补发

**来源：** [`docs/bug/Fix-RecallNotifyNotDurablyRedelivered.md`](docs/bug/Fix-RecallNotifyNotDurablyRedelivered.md)

### 现象

- A 发送消息给 B，B 已经收到并 `DELIVERY_ACK`。
- A 在 2 分钟内撤回消息，服务端返回 `RECALL_ACK(success=true)`。
- 如果 B 当时离线、重连中或错过 `RECALL_NOTIFY`，B 本地仍永久显示原消息。
- 服务端重启后也不会补发撤回通知。

### 排查过程

1. 检查 Android 侧，确认 `RECALL_NOTIFY` 到达后会调用 `markMessageRecalled(...)` 并持久化 `is_recalled`。
2. 检查 mock-server `handleRecallMessage(...)`，发现成功撤回后只尝试向在线 receiver 发送一次 `RECALL_NOTIFY`。
3. 检查 B5.5 持久化恢复逻辑，发现 `undeliveredMessagesByReceiver` 只追踪原始 `RECEIVE_MESSAGE`。
4. 复盘状态：一旦原消息已经 `DELIVERY_ACK`，它就不再属于未投递消息；后续 recall 是另一个状态更新，却没有自己的投递游标。

### 根因

撤回被建模为 accepted message 的状态更新，但没有 per-receiver 的撤回通知投递状态。原始消息 delivery 和撤回状态 delivery 混在一起，导致 `RECALL_NOTIFY` 只有 at-most-once 语义。

### 修复

- 增加 `RECALL_NOTIFY_ACK = 18`。
- Android 在成功持久化撤回状态后发送 `RECALL_NOTIFY_ACK`。
- mock-server 增加 `pendingRecallNotifiesByReceiver`。
- `accepted_messages` 增加 per-receiver `recall_notified` 字段。
- receiver auth/reconnect 时独立 replay 未确认的 `RECALL_NOTIFY`。

### 结果

- 原消息已经 delivery-acked 后，撤回状态仍可单独可靠补发。
- 重复 `RECALL_NOTIFY` 对 Android 是幂等的。
- 单聊撤回不再因为接收方短暂离线而永久不同步。

## 案例 3：会话列表分页边界漏触发和卡顿

**来源：** [`docs/bug/Fix-ConversationListPaginationScrollStutter.md`](docs/bug/Fix-ConversationListPaginationScrollStutter.md)

### 现象

- 会话列表快速滑过 50 条分页边界时，下一页有时不会及时加载。
- 即使触发分页，每跨一次边界也会出现明显卡顿。
- 499 条种子会话下，50、100、150 等边界都能感受到 stutter。

### 排查过程

1. 复现快速滚动穿过分页边界时列表停在 50 条的问题。
2. 检查 `ConversationListScreen`，发现分页触发依赖 `snapshotFlow` + `LaunchedEffect`。
3. 发现 `LaunchedEffect` key 包含 `state.items.size`、`hasMore`、`isLoadingMore`，分页完成后会取消并重建 collector。
4. 检查 ViewModel 数据层，发现没有下一页预取，跨边界时才同步查询下一页并 merge。
5. 将 UI 触发丢失和数据层卡顿拆成两个问题处理。

### 根因

- UI 层 collector 在分页完成时被状态变化反复取消，快速滚动事件可能漏掉。
- 数据层没有 prefetch，分页边界才做 DB 查询、merge 和 item build，造成主观卡顿。

### 修复

- 用 `derivedStateOf` 计算 `shouldLoadMore`。
- `LaunchedEffect(shouldLoadMore)` 只在布尔值变化时触发。
- 抽出 `ConversationListLoadMorePolicy`，把阈值判断变成可测试纯逻辑。
- ViewModel 增加下一页 `prefetchedPage` 和 `prefetchJob`。
- `refresh()` 时清空旧预取，避免复用脏数据。

### 结果

- 快速滑过分页边界时不再漏触发。
- 下一页查询提前完成，跨页体验更平滑。
- 分页判断逻辑从 UI 闭包中移出，后续更容易测试。

## 案例 4：图片聊天入口首帧灰色占位

**来源：** [`docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md`](docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md)

### 现象

- 从会话列表、消息弹窗、联系人资料等入口进入已有图片消息的聊天页时，首帧可能先显示灰色占位，再渲染缩略图。
- 发送方新发图片通常正常，接收方首次进入含多图会话更容易复现。

### 排查过程

1. 区分消息元数据缓存和 Coil bitmap 内存缓存。
2. 确认 `MessageRepository.initialPageCache` 只保证消息路径、尺寸、状态等元数据已在内存。
3. 检查旧的 `ChatInitialImagePrewarmer.prewarmAsync()`，发现它是 fire-and-forget，导航不会等待解码完成。
4. 复盘接收方路径：缩略图下载到本地后，SQLite 有 `localThumbnailPath`，但 Coil 可能仍是冷缓存。
5. 评估主线程阻塞等待解码的旧方案，确认有 ANR / 卡顿风险。

### 根因

聊天入口只预读了消息元数据，没有保证本地缩略图在导航前完成 Coil 解码。fire-and-forget 预热只能降低概率，不能保证首帧命中 bitmap cache。

### 修复

- 增加 `ChatInitialImagePrewarmer.prewarmBeforeNavigation(...)`。
- 导航前最多等待短时间预算，只处理有限数量的本地缩略图，并限制并发。
- `openPreloadedChat` 顺序执行：预读消息元数据、预热缩略图、导航。
- 超时后继续导航，保留原有 UI 兜底加载。

### 结果

- 冷缓存下进入图片会话时，首帧灰色占位明显减少。
- 不恢复主线程阻塞解码，避免用卡顿换取首帧稳定。

## 案例 5：refresh token rotation 没有完整替换双 token

**来源：** [`docs/bug/Fix-RefreshTokenRotation.md`](docs/bug/Fix-RefreshTokenRotation.md)

### 现象

- access token 过期后需要 refresh。
- 如果 refresh 只返回新 access token 或未正确废弃旧 refresh token，会导致客户端和服务端 token 状态不一致。
- 旧 refresh token 可能被复用，破坏 refresh-token rotation 语义。

### 排查过程

1. 对照登录态恢复和 WebSocket reconnect 需求，确认 refresh 结果必须能更新完整 session。
2. 检查 mock-server `/refresh` 处理，确认需要同时签发新的 access token 和 refresh token。
3. 检查 Android 持久化路径，确认 refresh 成功后本地必须保存新的 refresh token。
4. 对照 WebSocket auth bug，确认 stale token 会进一步影响 reconnect。

### 根因

refresh token rotation 是双 token 协议：服务端需要废弃旧 refresh token 并签发新 refresh token；客户端需要持久化整个新 session。只更新 access token 会留下过期或已吊销的 refresh token。

### 修复

- mock-server `/refresh` 同时返回新的 access token 和 refresh token。
- 旧 refresh token 在同一个 SQLite transaction 中废弃。
- Android refresh 成功后保存完整新 session。

### 结果

- refresh token 不能重复使用。
- Android 登录态恢复和 WebSocket reconnect 能基于 fresh session 工作。

## 修复经验总结

1. IM 协议状态要拆清楚：服务端接收、设备投递、用户已读、撤回通知都是不同事实。
2. UI 页面不能成为消息接收和持久化的唯一入口。
3. 本地缓存要区分元数据缓存和 bitmap / payload 缓存。
4. 分页、预取和滚动触发要把纯判断逻辑从 UI 闭包中抽出来。
5. 认证失败要 fail fast，不能保留半认证连接。
6. 所有 bug 文档统一保留在 [`docs/bug/`](docs/bug/)，文件名格式为 `Fix-<EnglishPascalCase>.md`。
