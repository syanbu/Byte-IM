# 开发约束

最后更新时间：2026-06-04

## 产品和架构范围

本项目是自研 IM 客户端和本地验证服务。

- 不接入第三方 IM SDK，包括腾讯云 IM、融云、环信、火山 RTC IM 等同类 SDK。
- Android 客户端必须自己维护核心 IM 链路：协议处理、连接生命周期、ACK/重试/去重、有序性、本地持久化、未读状态和聊天 UI 状态。
- Java/Netty mock server 是本地验证后端，不是生产服务。它可以实现足够的 HTTP/WebSocket 能力来验证 Android 客户端，但 Android 的正确性不应依赖未记录的手工 server 状态。
- 状态和路线图以 `docs/bg/DEVELOPMENT_STATUS.md` 和 `docs/status/` 为准。

## Android 存储约束

- 使用 `SQLiteOpenHelper` 手写 SQLite。
- 不引入 Room。
- 存储模型变更应尽量补 DAO contract test。
- 消息、会话、pending outbox、profile、group、本地图片/缓存元数据应通过明确 DAO 边界持久化，不应只保存在 UI state 中。
- UI 页面应渲染 repository/ViewModel 暴露的状态；该状态应来自存储或明确的一次性事件。UI 页面不能成为 IM 持久事实的来源。

## Android 鉴权请求语义

每一次已鉴权客户端请求，都必须在发送前解析 fresh valid `accessToken`。

- 不要复用页面级或 ViewModel 级 `AuthSession.accessToken` 快照来发送后续 HTTP 请求。
- 每次已鉴权 HTTP 请求前，调用共享 session provider，例如 `AuthRepository.ensureValidSession()`。
- 如果无法解析 fresh valid session，就不要携带 stale token 发送请求。
- 该规则适用于 OSS upload target、profile 拉取/更新、avatar upload target、group HTTP 请求，以及未来 history/push 等已鉴权 endpoint。
- WebSocket reconnect/auth 也必须通过 token provider 使用同样的 fresh-session 规则，不能使用过期缓存 token。

## Android 消息接收语义

Android 客户端必须在用户已登录时持续接收并持久化当前账号的 IM 消息，不依赖当前可见页面。

- `Messages`、`Contacts`、`Me` 都必须保持登录会话具备接收 WebSocket `RECEIVE_MESSAGE` packet 的能力。
- 消息接收不能依赖当前可见页面 ViewModel 是否正在 collect `incomingPackets`。
- 必须由登录会话级 receiver，例如 `MessagePacketProcessor`，统一 collect message packet 并通过 `MessageRepository` 持久化。
- 页面 ViewModel 可以从本地存储或 repository update event 刷新 UI，但不能成为防止 packet 丢失的唯一 consumer。
- 在 `Contacts` 或 `Me` 上收到消息后，再打开 `Chat` 必须能从本地 SQLite 看到已持久化消息。
- 这个接收保证不等同于 B9 retry、ACK timeout retry、pending outbox replay 或 reconnect backfill。

## 消息弹窗和一次性事件语义

短生命周期 UI 事件不应建模为持久数据库行，除非该功能明确要求持久化。

- message toast popup 是应用内一次性事件。
- `IncomingMessageAlert` 数据在处理 incoming message 时冻结：title、avatar、preview、timestamp、conversation id 在接收时复制到 alert 快照。
- popup UI 直接渲染 alert 快照，不应在弹窗可见期间重新拉取或刷新 profile/conversation 数据。
- Activity 或 Compose state 重建后，不应 replay 旧的 message toast popup。
- 关闭 popup 不能清未读数。只有现有 chat-open 路径负责清除 unread state。

## Android 返回导航语义

所有页面的左上角返回按钮必须和 Android 系统 Back 动作使用同一套语义。

- 不要为左上角返回按钮和系统 Back 实现两套分叉逻辑。
- 每个页面只能有一个统一的 `onBack` / `onNavigateBack` 回调。
- 左上角返回按钮和 `BackHandler` 都应调用同一个回调。
- 顶层页面，例如 `Messages`、`Contacts`、`Me`，系统 Back 应将任务移到后台，而不是调用 `Activity.finish()`。
- 二级/三级页面必须先返回上一级。

当前 IM 页面层级示例：

- `Messages` 顶层页：系统 Back 将任务移到后台，类似微信返回桌面但不杀 App。
- `Contacts` 顶层页：系统 Back 将任务移到后台。
- `Me` 顶层页：系统 Back 将任务移到后台。
- `Me -> Profile`：左上角返回和系统 Back 都返回 `Me`。
- `Me -> Profile -> Name`：左上角返回和系统 Back 都返回 `Profile`。
- `Chat`：左上角返回和系统 Back 都返回 `Messages`。

实现建议：

- 页面不要直接调用 Activity `finish()` 处理 Back。
- 顶层 Back 使用 `Activity.moveTaskToBack(true)`，不要 `finish()`。
- 内部页面层级由页面自己的统一 back handler 处理。
- 触发顶层后台逻辑前，必须先确认当前没有子页面需要返回。

## 协议和可靠性语义

- `MESSAGE_ACK` 表示服务端接收消息并分配 `serverSeq`。它不表示接收方已投递或已读。
- `DELIVERY_ACK` 表示接收方设备已经本地持久化该消息。它不表示用户已打开或已读会话。
- `READ_ACK` 才是已读回执信号。不要从 `DELIVERY_ACK` 推导 read state。
- 重复 incoming packet 必须继续按 `messageId` 本地幂等处理，同时保持安全 ACK 行为。
- `serverSeq` 是同一会话内 confirmed/received 消息的权威排序键。
- sender `clientSeq` 只作为本地关联和 ACK correlation 元数据，不能替代服务端排序。
