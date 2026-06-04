# 项目背景与当前状态

最后更新时间：2026-06-04

## 项目定位

这是一个本地自研 IM 项目，目标是在 Android 上实现一个完整的即时通讯客户端，并配套一个 Java/Netty mock server 作为本地联调、协议验证和自动化测试环境。

项目重点不是接入成熟 IM 云服务，而是自己实现 IM 客户端的核心链路：

- 登录态和鉴权
- WebSocket 长连接
- 自定义二进制协议
- 心跳、断线重连和前后台连接策略
- 消息 ACK、重试、去重和排序
- 本地 SQLite 持久化
- 会话列表、未读数、历史分页和聊天 UI
- 资料、头像、群聊、图片消息、撤回、已读等常见 IM 体验能力

Android 端使用 Kotlin、Coroutines、Flow、Jetpack Compose 和手写 SQLiteOpenHelper。项目明确不使用 Room，也不接入任何现成 IM SDK。

mock server 使用 Java/Netty/Maven，承担本地账号注册登录、HTTP 鉴权、WebSocket 鉴权、消息转发、ACK、离线暂存、持久化恢复、profile/group/OSS upload-target 等本地验证职责。服务端是辅助验证环境，不是生产后端。

## 当前完成度概览

当前项目已经从最初的单聊文本 demo 演进为一个较完整的本地 IM 客户端验证工程。

已完成或已实现首版的能力包括：

- B1 登录/注册、JWT access token、refresh token 轮换、登录态恢复和登出清理。
- B2 单聊文本消息实时发送与接收。
- B3 会话列表、最近会话、未读数、最后一条消息预览、进入会话清未读。
- B4 本地 SQLite 历史消息分页；服务端历史查询仍 deferred。
- B5 手写 SQLite 本地持久化，包括消息、会话、pending outbox、profile、group 等数据。
- B5.5 mock-server accepted-message 持久化和重启恢复。
- B6 自定义二进制协议，包含 header/body/CRC。
- B7 心跳、断线重连、指数退避、前后台心跳间隔切换。
- B8 基于 serverSeq 的消息有序性处理。
- B9 sender-side ACK、超时重试、失败标记和 messageId 幂等。
- B9.5 receiver-side DELIVERY_ACK 和重启后的未投递消息恢复。
- B10 群聊基础能力，包括 server-backed group creation、群消息收发、群成员存储、@ mention 元数据和 @ 我未读计数；完整群成员 UI 等仍未完全收敛。
- B11 图片消息首版，包括图库选择、多选拆成多条消息、OSS upload-target、上传/发送失败分层、缩略图缓存和渐进式显示。
- B12 消息撤回和已读回执首版，包括单聊已读回执、撤回状态持久化、聊天 UI 撤回展示和会话预览更新。
- Profile/Chat self-design UI，包括 Messages/Contacts/Me 顶层 tab、资料展示与编辑、头像上传链路、昵称/头像在会话和聊天中展示、向量 tab 图标、头像缓存、聊天输入栏优化、返回语义修正。
- Message toast popup：非当前会话收到 TEXT/IMAGE 消息时，在应用内顶部展示 4 秒弹窗，支持点击进入会话和手动关闭。

详细状态以 [`DEVELOPMENT_STATUS.md`](DEVELOPMENT_STATUS.md) 和 `../status/` 下各模块 status 文档为准。

## 当前重点边界

当前仍未完成或仍需人工验证的重点：

- B4 服务端历史消息查询尚未接入，协议上保留 `HISTORY_QUERY` / `HISTORY_RESULT`。
- B10 群成员完整 UI、成员同步的长期体验、群管理能力仍未作为完整产品闭环完成。
- B13 推送仍 deferred。
- OSS 头像/图片上传需要真实环境变量、bucket 策略和设备端手工验证。
- 性能和抓包类交付物仍需要专项验证：消息延迟、100 条并发顺序、50 次断网重连、1 万条会话列表性能、Wireshark/Charles 协议截图。

## 文档入口

- 需求目标：[`ProjectTarget.md`](ProjectTarget.md)
- 开发索引：[`DEVELOPMENT_STATUS.md`](DEVELOPMENT_STATUS.md)
- 开发约束：[`DEVELOPMENT-CONSTRAINTS.md`](DEVELOPMENT-CONSTRAINTS.md)
- 协议文档：[`../feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](../feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)
- 模块状态：`../status/`
