# B13 Mock Push 实现与手测说明

## 背景

B13 的目标是模拟"App 后台或进程不存在时，通过推送提醒用户并唤起到聊天页"。

本项目没有接入 FCM / HMS / MiPush / OPPO / VIVO 等真实厂商 SDK。当前实现是一个 **mock push 闭环**：

```text
mock-server 离线入队
  -> Android WorkManager 轮询 pending push
  -> 系统通知
  -> tap 通知 deep-link 到 ChatScreen
  -> WebSocket 重连后通过 B5.5 undelivered replay 落真实消息
```

## Mock 边界

这个功能不是厂商推送，也不能突破 Android 的 `force-stop` 语义。

- 可以覆盖：App 退到后台、进程被系统回收、进程不存在但应用未被用户强停。
- 不能覆盖：`adb shell am force-stop com.buyansong.im` 或设置页"强行停止"后的唤醒。

原因是 `force-stop` 后 Android 会阻止该应用的 JobScheduler / WorkManager / manifest receiver 运行。真实厂商推送可以通过系统级通道唤醒，mock 轮询做不到。

手测时应使用：

```bash
adb shell am kill com.buyansong.im
```

不要使用：

```bash
adb shell am force-stop com.buyansong.im
```

## 服务端实现

### 数据库

mock-server 新增独立 SQLite 文件：

```text
mock-server/data/mock-im-push.sqlite
```

包含两张表：

| 表 | 作用 |
|---|---|
| `push_tokens` | 记录每个 userId 的 mock push token。当前 mock 简化为同 userId 后注册覆盖前注册 |
| `push_notifications` | 记录离线用户待拉取的 push payload |

`push_notifications` 使用 `UNIQUE(user_id, message_id)` 保证同一用户同一消息只入队一次。

### HTTP API

服务端新增 4 个鉴权接口：

| API | 作用 |
|---|---|
| `POST /push/register-token` | Android 登录后注册 mock push token |
| `POST /push/unregister-token` | Android 登出时清理 token |
| `GET /push/pending?since=&limit=` | Android WorkManager 拉取待展示 push |
| `POST /push/ack` | Android 成功处理 push 后 ack |

这些接口都使用 `Authorization: Bearer <accessToken>`。

### 离线入队

`MessageRouter` 在处理 `SEND_MESSAGE` 时判断 receiver 是否在线：

```text
receiver 在线
  -> WebSocket RECEIVE_MESSAGE
  -> 不入 push 队列

receiver 离线
  -> 写 B5.5 undelivered 队列
  -> 额外写 push_notifications
```

群聊 fanout 时，对每个成员单独判断在线状态。在线成员不入 push 队列，离线成员各入一条。

## Android 实现

### Application 与通知 channel

`ImApp` 在启动时创建通知 channel：

```text
channel id: im_messages_push
importance: HIGH
```

Manifest 声明：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<application android:name=".app.ImApp" ...>
```

`MainActivity` 使用 `singleTop`，用于通知点击时复用已有 Activity 并处理新 intent。

### Mock push token

登录进入 authenticated UI 后：

1. `MockPushTokenStore` 从 SharedPreferences 读取本地 mock token。
2. 如果没有 token，则生成 UUID。
3. `PushTokenRepository` 调用 `/push/register-token`。
4. 本地记录 `mock_push_last_known_user_id`，供进程冷启动时恢复 WorkManager 调度。

登出时：

1. 调用 `/push/unregister-token`。
2. 清理本地 mock token。
3. 取消当前 userId 对应的 push poll work。

### WorkManager 轮询

`PushPollWorker` 的流程：

```text
ensureValidSession()
  -> 无有效 session：success，不重试
  -> 有 session：读取 lastSeenPushId
  -> GET /push/pending?since=<lastSeen>&limit=50
  -> pending 为空：更新 lastSeen 并 success
  -> pending 非空：逐条弹 NotificationCompat 通知
  -> POST /push/ack
  -> ack 成功：保存 latestPushId
```

网络或 ack 失败时返回 `Result.retry()`，交给 WorkManager 退避重试。

### 通知点击 deep-link

通知 PendingIntent 写入这些 extras：

| extra | 作用 |
|---|---|
| `conversation_id` | 目标会话 |
| `sender_id` | 发送方 |
| `message_id` | 消息 id |
| `push_id` | push id |

`MainActivity.onCreate()` 和 `onNewIntent()` 都会读取 `conversation_id`，写入 `pendingPushDeepLink`。

登录态可用后，`AuthenticatedImNavHost` 消费该 deep-link：

```text
pendingPushDeepLink
  -> navigate("chat/{conversationId}")
  -> 清空 pending deep-link
```

### 消息如何真正落库

push payload 首版只承载通知预览和 deep-link 必需字段，不伪造完整 `RECEIVE_MESSAGE`。

点击通知进入聊天页后，App 的 WebSocket 会重新鉴权。mock-server 通过 B5.5 undelivered replay 把真实消息重新下发：

```text
WebSocket AUTH
  -> mock-server replay undelivered RECEIVE_MESSAGE
  -> MessagePacketProcessor
  -> MessageRepository.handlePacket
  -> MessageDao.insertOrIgnore
  -> DELIVERY_ACK
```

这样 push 不绕过既有 ACK、去重和可靠投递语义。

## 手测流程

下面以两台模拟器/真机为例：

- A 端登录 `15000000000`
- B 端登录 `15000000001`

实际账号可替换。

### 1. 启动 mock-server

在项目根目录：

```bash
./start-mock-server.sh
```

确认 Android 端能访问 mock-server。

模拟器使用：

```properties
host=10.0.2.2
port=8080
```

真机 USB 调试使用：

```bash
adb reverse tcp:8080 tcp:8080
```

并将 `host` 配为 `127.0.0.1`。

### 2. 安装并登录两端

安装 debug APK：

```bash
adb -s <B设备> install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 13+ 给 B 端通知权限：

```bash
adb -s <B设备> shell pm grant com.buyansong.im android.permission.POST_NOTIFICATIONS
```

A、B 分别登录后，B 端会注册 mock push token，并调度 WorkManager。

可检查服务端 token：

```bash
sqlite3 mock-server/data/mock-im-push.sqlite \
  "select user_id,push_token,platform,device_id,updated_at from push_tokens;"
```

### 3. 验证在线不弹 push

B 端保持 App 在线。

A 给 B 发一条消息。

预期：

- B 端通过 WebSocket 立即收到消息。
- 不出现系统 push 通知。
- `push_notifications` 不新增该消息。

### 4. 让 B 后台进程不存在

B 端按 Home：

```bash
adb -s <B设备> shell input keyevent HOME
```

杀后台进程：

```bash
adb -s <B设备> shell am kill com.buyansong.im
```

不要使用 `force-stop`。

### 5. A 给 B 发送新消息

A 给 B 再发一条新文本消息，例如 `1111`。

检查服务端 push 队列：

```bash
sqlite3 -header -column mock-server/data/mock-im-push.sqlite \
  "select push_id,user_id,sender_id,conversation_id,message_id,preview,delivered_at from push_notifications order by push_id desc limit 10;"
```

预期看到 B 的 userId 有一条 `delivered_at` 为空的记录。

### 6. 手动触发 mock poll

WorkManager 周期最短 15 分钟。为了手测稳定，当前实现提供了一个 mock 调试广播，会立即 enqueue 一次 one-time `PushPollWorker`：

```bash
adb -s <B设备> shell am broadcast -p com.buyansong.im -a com.buyansong.im.DEBUG_RUN_PUSH_POLL
```

推荐使用这个命令，不再推荐手动找 JobScheduler jobId。

原因是 periodic WorkManager 的 system job id 会变化，`cmd jobscheduler run -f` 有时只启动 `SystemJobService`，但不会稳定执行到目标 worker。

### 7. 查看 worker 日志

```bash
adb -s <B设备> logcat -d -v time | grep -E 'PushPoll|WM-WorkerWrapper|Notification'
```

成功时应看到类似：

```text
PushPollDebug: enqueue debug push poll
PushPollWorker: start
PushPollWorker: pending count=1 latest=1 since=0
PushPollWorker: ack success pushIds=[1]
WM-WorkerWrapper: Worker result SUCCESS ...
```

### 8. 验证通知和 ack

B 端应收到系统通知。若没有 heads-up，可下拉通知栏查看。

服务端 `delivered_at` 应被写入：

```bash
sqlite3 -header -column mock-server/data/mock-im-push.sqlite \
  "select push_id,user_id,preview,delivered_at from push_notifications order by push_id desc limit 10;"
```

B 端本地 `lastSeenPushId` 应更新：

```bash
adb -s <B设备> shell run-as com.buyansong.im cat shared_prefs/mock_push.xml
```

预期：

```xml
<long name="push_last_seen_push_id_<B用户ID>" value="<最新pushId>" />
```

### 9. 点击通知

点击通知。

预期：

1. App 打开。
2. 自动进入对应 ChatScreen。
3. WebSocket 重新鉴权后，服务端重放未投递消息。
4. 消息最终出现在聊天页。

## 常见问题排查

### 服务端没有 pending push

查询：

```bash
sqlite3 -header -column mock-server/data/mock-im-push.sqlite \
  "select * from push_notifications order by push_id desc limit 10;"
```

如果没有记录，通常说明发送时 receiver 仍在线，或 B 端没有被杀到离线状态。

### 有 pending，但 worker 没消费

先用 debug broadcast：

```bash
adb -s <B设备> shell am broadcast -p com.buyansong.im -a com.buyansong.im.DEBUG_RUN_PUSH_POLL
```

再看日志：

```bash
adb -s <B设备> logcat -d -v time | grep -E 'PushPoll|WM-WorkerWrapper'
```

如果看到：

```text
PushPollWorker: skip: no valid session
```

说明 B 端本地登录态无效，需要重新登录。

如果看到：

```text
PushPollWorker: pending failed
```

优先检查 mock-server 地址和网络。

### worker ack 成功，但没看到通知

检查通知权限：

```bash
adb -s <B设备> shell dumpsys package com.buyansong.im | grep POST_NOTIFICATIONS -A 3
```

检查通知 channel：

```bash
adb -s <B设备> shell dumpsys notification --noredact | grep -A 8 -B 4 im_messages_push
```

确认 channel importance 是 `4`，即 HIGH。

### 不要用 force-stop

如果执行过：

```bash
adb shell am force-stop com.buyansong.im
```

需要手动启动一次 App，重新清掉 stopped 状态：

```bash
adb shell am start -n com.buyansong.im/.MainActivity
```

然后再按正常流程测试。

## 相关文件

| 文件 | 说明 |
|---|---|
| `mock-server/src/main/java/com/buyansong/imserver/push/` | 服务端 push token / notification store 和 `PushService` |
| `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java` | `/push/*` HTTP 端点 |
| `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java` | receiver 离线时自动入 push 队列 |
| `app/src/main/java/com/buyansong/im/app/ImApp.kt` | 创建通知 channel，恢复 last-known user 的 poll 调度 |
| `app/src/main/java/com/buyansong/im/push/PushPollWorker.kt` | 拉取 pending、弹通知、ack |
| `app/src/main/java/com/buyansong/im/push/PushPollDebugReceiver.kt` | 手测用 debug broadcast 入口 |
| `app/src/main/java/com/buyansong/im/push/PushDeepLink.kt` | 通知 PendingIntent 和 extras |
| `app/src/main/java/com/buyansong/im/MainActivity.kt` | 注册 token、调度/cancel worker、消费通知 deep-link |

## 验证命令

代码层验证：

```bash
mvn test
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

手工验证通过标准：

```text
在线消息不弹 push
离线消息进入 push_notifications
DEBUG_RUN_PUSH_POLL 能触发 PushPollWorker
系统通知出现
push 被 ack，delivered_at 写入
tap 通知进入对应 ChatScreen
真实消息通过 WebSocket replay 落库展示
```
