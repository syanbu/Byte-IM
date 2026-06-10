# IM 客户端开发状态索引

最后更新时间：2026-06-04

本文是 IM 客户端开发进度的顶层索引，项目目标见
[`ProjectTarget.md`](ProjectTarget.md)。各功能模块的详细状态记录在
`../status/` 目录下。

设计路线图：`../superpowers/plans/2026-05-21-im-client-roadmap.md`。

开发约束：[`docs/bg/DEVELOPMENT-CONSTRAINTS.md`](DEVELOPMENT-CONSTRAINTS.md)。

功能说明：[`docs/feature-notes/messages-conversation-summary-and-unread.md`](../feature-notes/messages-conversation-summary-and-unread.md)。

## 当前进度

| 功能 | 需求描述 | 状态 | 详情 |
|---|---|---|---|
| Foundation | Android Kotlin 工程骨架和本地构建基础 | 已完成 | [00-project-foundation.md](../status/00-project-foundation.md) |
| B1 | 登录/注册、HTTP API、JWT token | 已完成 | [B1-auth.md](../status/B1-auth.md) |
| B2 | 基于 WebSocket 的单聊文本消息 | 已完成 | [B2-single-chat.md](../status/B2-single-chat.md) |
| B3 | 会话列表：最近会话、未读数、最后消息预览 | 已完成 | [B3-conversation-list.md](../status/B3-conversation-list.md) |
| B4 | 本地历史消息分页；服务端历史查询暂缓 | 部分完成 | [B4-history-pagination.md](../status/B4-history-pagination.md) |
| B5 | 使用 SQLite 持久化消息，不使用 Room | 已完成 | [B5-local-persistence.md](../status/B5-local-persistence.md) |
| B5.5 | mock-server 消息持久化和重启恢复 | 已完成 | [B5.5-mock-server-message-persistence.md](../status/B5.5-mock-server-message-persistence.md) |
| B6 | 自定义二进制协议，包含 header、body、CRC | 已完成 | [B6-binary-protocol.md](../status/B6-binary-protocol.md) |
| B7 | 心跳和断线重连 | 已完成 | [B7-heartbeat-reconnect.md](../status/B7-heartbeat-reconnect.md) |
| B8 | 基于 client seq / server ACK 的消息有序性 | 已完成 | [B8-message-ordering.md](../status/B8-message-ordering.md) |
| B9 | ACK、重试、去重等可靠性能力 | 发送侧首版已完成 | [B9-message-reliability.md](../status/B9-message-reliability.md) |
| B9.5 | 接收侧 DELIVERY_ACK | 已完成 | [B9.5-delivery-ack.md](../status/B9.5-delivery-ack.md) |
| Mock server | 本地 Netty 服务，支持鉴权、WebSocket、持久化重放和当前 demo 联调能力 | 当前 B1-B9.5 路径已完成，并被后续功能继续扩展 | [mock-server.md](../status/mock-server.md) |
| B10 | 群聊、群创建和 @ 提醒基础能力 | 部分完成 | [B10-group-chat-and-mention.md](../status/B10-group-chat-and-mention.md) |
| B11 | 图片消息：图库选择、多选拆分、上传/重试分层、缩略图渐进展示 | 图片消息首版已实现 | [B11-image-message-design-status.md](../status/B11-image-message-design-status.md) |
| B12 | 消息撤回和已读回执 | 单聊已读回执、群聊已读人数/读者列表已实现；撤回使用单聊/群聊共享聊天 UI 路径 | [B12-message-recall-and-read-receipts.md](../status/B12-message-recall-and-read-receipts.md) |
| B13 | 推送 | 实现中（mock 完整闭环首版已接入，待设备 E2E） | [B13-mock-push.md](../status/B13-mock-push.md) |

## 近期自设计工作

这些是原始 B 系列需求之外的产品/UI 体验优化。

| 功能 | 范围 | 状态 | 详情 |
|---|---|---|---|
| Profile/Chat UI 自设计 | 增加 Messages/Contacts/Me tab、profile 缓存和编辑、头像上传链路、会话/聊天昵称头像、向量 tab 图标、头像缓存、聊天输入栏优化、Navigation Compose 返回、统一 Back 语义和聊天 UI 清理 | 已实现；仍建议做模拟器/真机 UI 和真实 OSS 验证 | [self-design-profile-chat-ui-status.md](../status/self-design-profile-chat-ui-status.md) |
| 消息顶部弹窗 | 非当前会话收到文本/图片消息时展示应用内顶部弹窗，支持点击进入会话、关闭和 4 秒自动消失 | 已实现；建议补充多设备手工 UI 验证 | [message-toast-popup.md](../status/message-toast-popup.md) |

## 下一步

如果后续需要服务端历史消息，再继续 B4 的 server-backed history；当前本地 SQLite 历史分页路径已经实现。

近期 self-design 工作的下一步是按
[self-design-profile-chat-ui-status.md](../status/self-design-profile-chat-ui-status.md)
中的 checklist 做模拟器/真机验证，覆盖 profile/avatar、聊天视觉、向量 tab 图标、Back 语义和 OSS 头像上传。

B3 当前本地单聊范围已经完成：

- `ConversationListViewModel` 和 `ConversationListScreen` 已存在。
- 登录/会话恢复后先进入会话列表，再进入聊天页。
- 会话行展示对方 id/name、预览、最后时间和未读数。
- 点击会话会打开 `ChatScreen`。
- 进入会话会清除未读。
- 当前打开会话收到新消息时刷新预览但不增加未读。
- 会话行会从 Repository conversation update 事件刷新，包括聊天页处理的消息。
- 底部 `Messages` tab 展示所有会话总未读，`0` 时隐藏，超过 99 显示 `99+`。
- 空会话列表不再展示固定 mock 联系人；本地 demo 账号通过 `Contacts` tab 暴露。
- 连接/鉴权状态和登出只显示在会话列表；聊天页保留返回导航。

B4 本地历史分页已经在当前 SQLite 聊天路径实现：

- `ChatViewModel.start()` 首次进入只加载最近 20 条本地消息。
- `ChatViewModel.loadMoreHistory()` 使用当前最早可见消息的 `createdAt` 作为 `beforeTime` 游标，继续加载更早 20 条。
- 已加载分页按 `messageId` 合并，保持当前 `reverseLayout` 聊天 UI 使用的 newest-first 列表。
- 实时新消息会刷新可见列表，不丢弃已加载历史。
- `ChatUiState` 暴露 `isLoadingMore`、`hasMoreLocal` 和 `errorMessage`。
- `ChatScreen` 在用户滚动到旧消息端附近 6 项内时自动加载更早本地历史。
- 当前聊天 ViewModel 为本次聊天会话维护 grow-only 内存历史缓存，上限 2,000 条。

## 已完成

- B1 鉴权和 token 管理已完成，基于本地 mock server。
- B1 refresh-token rotation 已匹配双 token 约定：`/refresh` 返回新的 access/refresh token，并废弃旧 refresh token。
- B2 单聊实时文本收发已完成。
- B4 本地 SQLite 历史分页已完成。
- B5 SQLite 持久化基础已完成。
- B6 二进制协议编解码已完成，并记录在 `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`。
- B7 Android 心跳和重连已完成：前台 15 秒心跳、后台 75 秒心跳、心跳 ACK 存活判断、超时断开、指数退避重连和 `Reconnecting` UI 状态。
- B8 消息有序性已完成：sender-side `clientSeq` 仅作为本地/ACK 关联元数据，mock server 按会话分配 `serverSeq`，Android 查询/合并路径按 `serverSeq` 排序。
- B9 发送侧可靠性首版已完成：Android 持久化 pending outbox，在 `Authenticated` 后重试到期消息，超过重试次数标记 `FAILED`，刷新聊天发送状态；mock server 基于 `messageId` 保持幂等。
- 本地 Java/Netty mock server 支持当前鉴权和单聊 WebSocket 路径。
- Self-design Profile/Chat UI 已实现，包括 Messages/Contacts/Me tab、资料展示/编辑、头像上传链路、昵称头像展示、向量 tab 图标、头像缓存、聊天输入栏优化和 Back 语义修正。
- 项目级开发约束记录在 `docs/bg/DEVELOPMENT-CONSTRAINTS.md`。

## 进行中

- B10 第一阶段基础能力已实现：
  - Android model 和 SQLite schema 已携带 conversation type、group id 和 mention metadata。
  - Android SQLite 已包含 `groups` 和 `group_members`，并有本地 group metadata DAO 覆盖。
  - Android repository 可以发送 group text packet，也可以在 `group:<groupId>` 下接收 group packet。
  - `@ me` 未读通过 `mention_unread_count` 单独计数。
  - 会话列表可以用 `chat/{conversationId}` 打开 single/group 会话。
  - mock-server 可以向在线群成员 fanout 群消息、为离线成员排队、拒绝非成员发送，并保持重复 group `messageId` 幂等。
  - mock-server 按具体 receiver 持久化 group message delivery state，所以群消息 `DELIVERY_ACK` 和离线重放是 per member 的。
  - mock-server 在 `data/mock-im-groups.sqlite` 持久化群元数据和群成员。
  - mock-server 暴露已鉴权 group HTTP endpoint；Android 的发起群聊流程通过 `GroupApi`/`GroupRepository` 调用 `POST /groups`，不再创建纯本地群。
  - Android 可以通过 `PATCH /groups/{groupId}` 重命名群，会话列表通过 `GET /groups` 刷新群名。
  - 多端成员同步、完整群成员 UI、@ composer/highlight 体验仍未完全收敛。
- B4 服务端历史消息尚未接入。后续如果需要远端历史，应使用 `HISTORY_QUERY` 和 `HISTORY_RESULT`。
- 最新 self-design Profile/Chat UI 仍需要手工模拟器验证。

## 未开始

- Phase 10 性能、抓包和稳定性证据。

## 最近完成

- B1 refresh-token rotation 修复：
  - mock-server `/refresh` 现在同时签发新的 access token 和 refresh token。
  - 旧 refresh token 在同一个 SQLite rotation transaction 中废弃，不能再复用。
  - Android 鉴权解析/持久化测试覆盖了保存 refresh 返回的新 refresh token。
- B5.5 mock-server 持久化：
  - mock server 将 accepted messages 持久化到 `mock-server/data/mock-im-messages.sqlite`。
  - accepted-message recovery 可以跨 server 重启保持 sender-side `messageId` 幂等。
  - receiver undelivered-message recovery 可以跨 server 重启恢复；receiver auth/reconnect 后只重放仍等待 `DELIVERY_ACK` 的消息。
  - `DELIVERY_ACK` 会清除内存 undelivered index 和持久化 delivery flag，已 ACK 消息重启后不会重放。
  - 该持久化层保留了后续 B4 server-backed history 需要的 accepted message body 和关键字段。
- B9.5 receiver delivery ACK：
  - Android 持久化 `RECEIVE_MESSAGE` 后发送 `DELIVERY_ACK`。
  - 重复 receive packet 仍按 `messageId` 本地去重，并安全 ACK。
  - mock server 启动时恢复 receiver delivery state，并在 receiver auth/reconnect 后重发未投递消息。
  - sender `MESSAGE_ACK` 语义不变，只表示服务端接收和 `serverSeq` 分配。
  - Android 现在强制单一入站 packet consumer：`MessagePacketProcessor` 负责 WebSocket receive，`ChatViewModel` 和 `ConversationListViewModel` 只通过 repository update signal 刷新。这修复了多个 UI collector 处理同一个 `RECEIVE_MESSAGE` 导致重复 `DELIVERY_ACK` 的问题。
