# B13 推送（Mock 完整闭环）— 设计 Spec

最后更新：2026-06-10

## 1. 背景

`docs/bg/ProjectTarget.md` 中 B13 要求："App 后台或进程被杀时，可以通过厂商推送通道唤起；如真实厂商推送接入成本较高，可使用 mock 方案模拟。" 当前 `docs/bg/DEVELOPMENT_STATUS.md` 标注 B13 为"暂缓"。

**为什么做 mock**：

1. 现有项目没有引入任何 FCM / HMS / MiPush SDK，集成真实厂商推送需要 google-services.json、签名、厂商账号等，与"自研 IM 客户端"目标不符。Mock 是项目目标文档明确允许的方案。
2. 当前 `ConnectionLifecycleManager` 依赖 `MainActivity` 持有，进程被杀后 WebSocket、心跳、重连、ACK 全部停止；没有"被杀后被唤起"的兜底通道。

**Mock 边界补充**：

- WorkManager 可以覆盖"应用退到后台、进程被系统回收/不存在，但应用未被用户强停"的 mock 唤起场景。
- `adb shell am force-stop <package>` / 设置页"强行停止"会让 Android 阻止该应用的 JobScheduler / WorkManager 任务继续运行；这是系统语义，mock 轮询无法像真实厂商推送一样突破该状态。手工验证应使用后台后 `adb shell am kill <package>` 或等待系统回收进程，不使用 `force-stop`。

**锁定的设计决策**（已与用户确认）：

1. **拉取机制**：WorkManager 周期任务（15 min 最小周期）。
2. **触发时机**：自动 — MessageRouter 在 receiver 离线时把消息入 push 队列。
3. **通知内容**：发送方昵称 + 消息预览（图片消息显示 `[图片]`）。
4. **Mock 边界**：完整闭环（register-token / pending / ack / 自动触发 / 通知弹窗 / deep link / 进程被杀后唤起）。

## 2. Goals

- App 后台或被进程杀掉时，新消息能在系统通知栏弹出。
- tap 系统通知能 cold-start App 并 deep-link 到对应 ChatScreen，消息已落库可读。
- 不引入第三方 push SDK；不依赖真实厂商通道。
- 复用现有 `MessageRepository.handlePacket`、`messageDao.insertOrIgnore`、`conversationDao.upsertFromMessage` 等路径，不破坏 B5/B9/B9.5/B10/B12 的现有语义。
- mock-server 离线入队与 B5.5 undelivered 持久化互不替代：push 队列是"兜底通道"，不是"另一种投递"。

## 3. Non-Goals

- 真实厂商推送 SDK 接入（FCM / HMS / MiPush / OPPO / VIVO）。
- 通知点击率统计、撤回 push、富文本通知布局。
- 群 `@所有人` 的特殊 push 行为（首版只按 receiver 是否在线判定）。
- 多端 push 路由（同 userId 多个 token 共存）。mock 简化：新 token 覆盖旧 token。

## 4. Functional Behavior

### F1. Push Token 注册

- 客户端进入 `Authenticated` 状态时：
  1. 检查本地是否已有 mock push token（SharedPreferences）。没有就 `UUID.randomUUID().toString()` 生成一个。
  2. POST `/push/register-token`，Header `Authorization: Bearer <freshAccessToken>`（走 `AuthRepository.ensureValidSession()`，符合 `DEVELOPMENT-CONSTRAINTS` 鉴权语义）。
  3. Body：`{ "pushToken": "...", "platform": "android", "deviceId": "<androidId>" }`。
  4. 服务端按 `userId` PRIMARY KEY UPSERT 到 `push_tokens` 表。
- 客户端登出时：
  - 调 `POST /push/unregister-token` 通知服务端清理；并清本地 SharedPreferences 里的 mock token（下次登录重新生成）。
- 重新登录 / 进程重启 / session 刷新：
  - 重新注册；服务端 UPSERT 幂等。
- 多端（mock 简化）：同 userId 多次注册，新 push token 覆盖旧 push token；不维护多 token list。

### F2. 离线消息自动入 push 队列

- `MessageRouter` 处理 `SEND_MESSAGE` 时，对 receiver 列表做如下判断：
  - 如果 `ClientSessionRegistry.find(receiverId).isPresent()` —— receiver 在线，不入 push 队列，由 WebSocket 推送 `RECEIVE_MESSAGE`。
  - 如果 receiver 不在线 —— 在写 `accepted_messages` undelivered 队列之后（B5.5 已实现），额外往 `push_notifications` 表 INSERT 一条。
- 群消息 fanout：每个 member 单独判断；离线 member 各入一条。
- `push_notifications` 表 schema（mock-server 新 SQLite 文件 `data/mock-im-push.sqlite`）：

  ```sql
  CREATE TABLE push_notifications (
    push_id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    message_type TEXT NOT NULL,           -- TEXT / IMAGE
    preview TEXT,                         -- text content / '[图片]'
    server_seq BIGINT NOT NULL,
    server_time BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    delivered_at BIGINT,                  -- NULL = 待拉取
    UNIQUE(user_id, message_id)           -- MessageRouter 幂等
  );
  CREATE INDEX idx_push_user_pending
    ON push_notifications(user_id, delivered_at, push_id);
  ```

- 重复 messageId：`UNIQUE(user_id, message_id)` 约束兜底；enqueue 用 `INSERT OR IGNORE`。

### F3. 客户端 WorkManager 周期拉取

- 新建 `PushPollWorker` (`CoroutineWorker`)：
  1. 解析 fresh valid session（`authRepository.ensureValidSession()`），无 session → `Result.success()`（不重试）。
  2. 读本地 `push_last_seen_push_id`（SharedPreferences，Long，默认 0）。
  3. GET `/push/pending?since=<lastSeenPushId>&limit=50`。
  4. 返回 0 条 → `Result.success()`。
  5. 返回 ≥1 条：逐条构造 `NotificationCompat.Builder` post 到 channel `im_messages_push`（importance HIGH，heads-up）。
  6. POST `/push/ack`，body `{ "pushIds": [...] }`。
  7. 持久化 `lastSeenPushId = max(本地, response.latestPushId)`。
  8. 网络异常 → `Result.retry()`（WorkManager 自带指数退避）。
- 调度：
  - `ImApp.onCreate` 调度一次 `PeriodicWorkRequest`，周期 15 min（WorkManager 最小），约束 `NetworkType.CONNECTED`。
  - `AuthenticatedImNavHost` 在 `DisposableEffect` 启动时 `enqueueUniquePeriodicWork` 替换已有任务（用 userId 标记 worker name，避免多账号冲突）。
  - 登出时 `cancelUniqueWork`。

### F4. 系统通知展示

- 通知 channel：`im_messages_push`（`IMPORTANCE_HIGH`，description "新消息提醒"），`ImApp.onCreate` 一次性 `createNotificationChannel`。
- 每条 push 一条通知：
  - title：发送方昵称。`messageId` 查本地 `MessageDao`/`ContactsDao` 缓存；未命中显示 `senderId`。
  - content：文本预览 / `[图片]`（来自 push payload `preview` 字段）。
  - smallIcon：复用 `R.mipmap.ic_launcher`（首版不画专用 icon）。
  - `setAutoCancel(true)`。
  - `setContentIntent`：PendingIntent → `MainActivity`，`FLAG_UPDATE_CURRENT` + `FLAG_IMMUTABLE`。
    - extras: `conversation_id` / `sender_id` / `message_id` / `push_id`。
  - notification id: `push_id.toInt()`。
- 权限：AndroidManifest 加 `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`（API 33+）。运行时权限请求：首次 `PushPollWorker` 拉取到非空结果时，先 `NotificationManagerCompat.areNotificationsEnabled()` 检查；未授权则跳过弹通知（避免 SecurityException），并降级为只持久化到本地（参考 F5）。
- 聚合：首版不做 InboxStyle 聚合（一条 push 一条通知）。

### F5. tap 通知 deep-link

- `MainActivity` launchMode 改为 `singleTop`（如尚未）。
- `onCreate` 与 `onNewIntent` 都处理 intent extras：
  - 读出 `conversation_id` / `message_id` / `push_id`。
  - 把 `pendingDeepLink: String?` 写入 `MutableStateFlow`，NavHost 监听该 StateFlow。
- 路径：
  - 已登录 → `navigate("chat/{conversationId}")`。
  - 未登录 / token 失效 → 等 `LoginViewModel` 完成登录后，再 navigate。
- 消息入库：
  - push payload 首版只承载通知预览和 deep-link 必需字段，不伪造完整 `RECEIVE_MESSAGE`。
  - tap 通知 cold-start 后，现有 WebSocket 鉴权/重连路径会通过 B5.5 undelivered replay 把真实消息送入 `MessageRepository.handlePacket`。
  - 重复 insert 仍由 `messageDao.insertOrIgnore` 兜底；push 不替代 `DELIVERY_ACK`。

### F6. push payload 不替代 WebSocket RECEIVE_MESSAGE

- 在线主路径仍是 WebSocket `RECEIVE_MESSAGE` → `MessagePacketProcessor` → `MessageRepository.handlePacket`。
- push 仅在 WebSocket 不通 / 未连接时是兜底。
- 客户端不能因为收到 push 就跳过 `DELIVERY_ACK`（用户后续上线，WebSocket 重放依旧会触发完整流程）。
- 服务端不因为入 push 队列就跳过 B5.5 undelivered 持久化（两条独立路径，互不替代）。

## 5. Server API Contract

### POST /push/register-token

- Auth：Bearer access token。
- Request：

  ```json
  { "pushToken": "uuid-xxx", "platform": "android", "deviceId": "..." }
  ```

- Response 200：

  ```json
  { "code": 0, "message": "ok", "data": { "registeredAt": 1717000000000 } }
  ```

- 错误：401 未鉴权 / 400 缺 `pushToken`。

### POST /push/unregister-token

- Auth：Bearer。
- Request：`{}`。
- Response 200：`{ "code": 0, "message": "ok", "data": {} }`。
- 服务端从 `push_tokens` 删除该 userId 行。

### GET /push/pending?since=&limit=

- Auth：Bearer。
- `since`：上次拉取到的最大 `pushId`，首次为 0。
- `limit`：默认 50，上限 100。
- Response 200：

  ```json
  {
    "code": 0,
    "message": "ok",
    "data": {
      "pending": [
        {
          "pushId": 123,
          "senderId": "u_1001",
          "conversationId": "single:u_1001:u_1002",
          "messageId": "m_abc",
          "messageType": "TEXT",
          "preview": "hello",
          "serverSeq": 1010,
          "serverTime": 1717000000000
        }
      ],
      "latestPushId": 123
    }
  }
  ```

- 仅返回 `delivered_at IS NULL` 的记录。

### POST /push/ack

- Auth：Bearer。
- Request：`{ "pushIds": [1, 2, 3] }`。
- Response 200：`{ "code": 0, "message": "ok", "data": { "ackedCount": 3 } }`。
- 服务端把对应 push 的 `delivered_at = now`。

## 6. Data Model Changes

### mock-server

- 新 SQLite 文件 `data/mock-im-push.sqlite`：
  - `push_tokens(user_id PK, push_token, platform, device_id, updated_at)`。
  - `push_notifications(push_id PK AUTOINCREMENT, user_id, sender_id, conversation_id, message_id, message_type, preview, server_seq, server_time, created_at, delivered_at NULL, UNIQUE(user_id, message_id))`。
  - `idx_push_user_pending(user_id, delivered_at, push_id)`。
- 不动现有 `mock-im-messages.sqlite` / `mock-im-groups.sqlite`。

### Android

- 不新增 SQLite 表。
- SharedPreferences 加 `push_last_seen_push_id` (Long) 和 `mock_push_token` (String)。

## 7. Dependencies

- Android `app/build.gradle`：
  - 新增 `androidx.work:work-runtime-ktx:2.9.1`。
- mock-server `pom.xml`：
  - 无新增（Gson + sqlite-jdbc + Netty 已够用）。

## 8. Out of Scope（未来迭代）

- 多端 push 路由（同 userId 多个 token 共存）。
- 通知点击率统计。
- 富媒体通知（图片大图、InboxStyle 聚合）。
- 群 @ 全员的特殊 push 行为。
- push token 失效轮换策略、token 续签。
