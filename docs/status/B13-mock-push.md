# B13 推送 Mock 状态

最后更新：2026-06-10

## Requirement

B13 来自 `docs/bg/ProjectTarget.md`：

> | B13 | 推送 | 后台被杀时通过厂商推送通道唤起，可用 mock | App 后台或进程被杀时，可以通过厂商推送通道唤起；如真实厂商推送接入成本较高，可使用 mock 方案模拟。 |

要求覆盖：App 后台或被进程杀掉时，新消息能在系统通知栏弹出；tap 通知能 deep-link 到对应 ChatScreen，消息已落库可读。

## Status

**实现中（mock 完整闭环首版已接入，待设备 E2E）**。服务端和 Android 首版代码已接入；真实厂商 SDK 不在本期范围内。

- 设计 Spec：[`../superpowers/specs/2026-06-10-b13-mock-push-design.md`](../superpowers/specs/2026-06-10-b13-mock-push-design.md)
- 实施 Plan：[`../superpowers/plans/2026-06-10-b13-mock-push.md`](../superpowers/plans/2026-06-10-b13-mock-push.md)
- 功能说明：[`../feature-notes/B13-mock-push.md`](../feature-notes/B13-mock-push.md)

当前已完成代码首版：

- mock-server：`push_tokens` / `push_notifications` SQLite store、`/push/register-token`、`/push/unregister-token`、`/push/pending`、`/push/ack`，以及 receiver 离线时的自动 push 入队。
- Android：`ImApp` 通知 channel、mock token 本地存储与注册/注销、WorkManager 15 min pending 轮询、系统通知、通知 tap deep-link 到会话。
- push payload 首版只做通知预览和 deep-link；真实消息仍由 B5.5 WebSocket undelivered replay 入库，不用 push 伪造完整消息。

## Project Context

`docs/2026-engine.md` / `docs/bg/ProjectTarget.md` 将 B13 列为第三阶段"体验增强"的可选功能。当前开发状态（B1-B12）已基本完成，B13 是项目目标文档中最后一个未实现的功能点。

为什么做 mock 而不是真实厂商推送：

1. 真实厂商 SDK（FCM / HMS / MiPush / OPPO / VIVO）需要 google-services.json、厂商账号、签名等，与"自研 IM 客户端"目标不符。
2. 集成真实 SDK 成本高、跨厂商差异大，对"自研链路"主题没有增量价值。
3. `docs/bg/ProjectTarget.md` 明确允许 mock 方案。

## 设计锁定的关键决策

1. **拉取机制**：WorkManager 周期任务（15 min 最小周期，约束 `NetworkType.CONNECTED`）。
2. **触发时机**：自动 — `MessageRouter` 在 receiver 离线时把消息入 push 队列（不替代 B5.5 undelivered 持久化）。
3. **通知内容**：发送方昵称 + 消息预览（图片消息显示 `[图片]`），channel `im_messages_push` importance HIGH。
4. **Mock 边界**：完整闭环（register-token / pending / ack / 自动触发 / 通知弹窗 / deep-link / 进程被杀后唤起）。

## 设计要点摘要

- **客户端 token 注册**：进入 `Authenticated` 时生成 UUID 风格 mock token，POST `/push/register-token`；登出时 unregister。
- **服务端离线入队**：`MessageRouter.deliverOrKeepPending` 的离线分支里入 `push_notifications` 表；群聊对每个 member 单独判断。
- **客户端拉取**：`PushPollWorker` (CoroutineWorker) 周期 15 min 拉取 `/push/pending?since=`，弹 NotificationCompat 通知，POST `/push/ack`。
- **通知 + deep-link**：`singleTop` launchMode + `pendingDeepLink: StateFlow<String?>`，NavHost 监听并 navigate 到 `chat/{conversationId}`。
- **消息入库**：tap 通知进入会话后，现有 WebSocket 鉴权/重连路径通过 B5.5 undelivered replay 调 `MessageRepository.handlePacket`，幂等性由 `messageDao.insertOrIgnore` 兜底。
- **不破坏现有语义**：push 仅是 WebSocket 的兜底；服务端不因为入 push 队列就跳过 B5.5 undelivered 持久化；客户端不能因为收到 push 就跳过 `DELIVERY_ACK`。

## Current Foundation To Reuse

mock-server：

- `session/MessageRouter.java:560` — `deliverOrKeepPending` 离线分支，加 push 入队即可。
- `session/ClientSessionRegistry.java` — `find(userId)` 判断在线。
- `group/SQLiteGroupStore.java` — raw JDBC DAO 模板（参照写 `SQLitePushTokenStore` / `SQLitePushNotificationStore`）。
- `netty/HttpAuthHandler.java:199-229` — `POST /groups` 鉴权 + 响应模板。

Android：

- `message/MessageRepository.kt:389` — `handlePacket` 是入站唯一入口；push 走 `handleIncoming` 路径。
- `storage/MessageDao.kt` — `insertOrIgnore` 自带幂等。
- `storage/ConversationDao.kt` — `upsertFromMessage` 维护会话预览和未读。
- `auth/AuthRepository.kt` — `ensureValidSession()` 满足"每次已鉴权请求前解析 fresh valid session"。
- `group/GroupApi.kt` — HTTP API 模板（`OkHttpGroupApi` + sealed Result）。

## 已知限制（首版）

- WorkManager 周期最小 15 min，演示时需用 `adb shell cmd jobscheduler run -f <pkg> <jobId>` 手动触发加速。
- 当前代码额外提供 mock 调试广播，可用 `adb shell am broadcast -p com.buyansong.im -a com.buyansong.im.DEBUG_RUN_PUSH_POLL` 立即 enqueue 一次 one-time push poll，比手动找 JobScheduler jobId 更稳定。
- `force-stop` / 设置页"强行停止"会阻止 WorkManager/JobScheduler；mock push 只覆盖后台进程被系统回收或进程不存在但应用未被强停的场景。
- 多端 push 路由未做（同 userId 后注册 token 覆盖前者）。
- 不做 InboxStyle 聚合、不做点击统计、不做富文本通知布局。
- 群 `@所有人` 不做特殊 push 行为。
- push token 不做失效轮换（mock 简化）。

## 后续工作

下一步做真机/模拟器 E2E：后台后 `am kill` user B 进程、user A 发消息、触发 WorkManager job、验证通知和 tap deep-link。
