# 自研 IM 客户端开发路线文档

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于训练营 mock server，完成一个支持登录、单聊、会话列表、历史分页、本地持久化、自研协议、心跳重连、消息有序与可靠投递的 Android IM 客户端。

**Architecture:** 采用分层 + 状态机设计：UI 只消费 ViewModel 暴露的 Flow，业务层负责消息发送、ACK、重试、去重和会话聚合，网络层负责 WebSocket 长连接、协议编解码、心跳和重连，存储层用 `SQLiteOpenHelper` 手写表结构与索引。优先做“单聊文本 IM”主链路，群聊、图片、撤回、已读和推送作为后续加分项，避免一开始把范围拉爆。

**Tech Stack:** Kotlin、Jetpack Compose、Coroutines、Flow、Channel、OkHttp WebSocket、SQLiteOpenHelper、JUnit、MockK、Charles/Wireshark、Android Studio Profiler。

---

## 1. 项目目标与范围

### 必做基础功能

- 登录/注册：HTTP 接口，使用 JWT Token。
- 单聊文本消息：通过 WebSocket 长连接实时收发。
- 会话列表：展示最近会话、未读数、最后一条消息预览和最后时间。
- 历史消息分页：聊天页上拉加载更多。
- 消息持久化：直接使用 SQLite，禁止 Room。

### 高优先进阶功能

- 自定义二进制协议：`Header(magic + version + length + cmd) + Body(JSON 或 Protobuf) + CRC`。
- 心跳与断线重连：心跳包、超时检测、指数退避、前后台切换感知。
- 消息有序性：客户端生成 `seq`，服务端 ACK，接收端按 seq 重排。
- 消息可靠性：ACK 机制、失败重发、`messageId` 去重。

### 延后加分功能

- 群聊 + @ 提醒。
- 图片消息：上传、缩略图、渐进式加载。
- 消息撤回 / 已读回执。
- 后台推送 mock。

---

## 2. 推荐模块结构

如果从零创建 Android 项目，建议先用单 app module，内部按包拆分。等功能稳定后再拆独立 module，避免早期工程复杂度过高。

```text
app/src/main/java/<package>/
  app/
    ImApplication.kt
    AppForegroundObserver.kt
  auth/
    AuthApi.kt
    AuthRepository.kt
    TokenStore.kt
    LoginViewModel.kt
    LoginScreen.kt
  protocol/
    ImCommand.kt
    ImPacket.kt
    ImPacketCodec.kt
    Crc32.kt
    ProtocolModels.kt
  connection/
    ImConnection.kt
    OkHttpImConnection.kt
    ConnectionState.kt
    HeartbeatManager.kt
    ReconnectPolicy.kt
  message/
    MessageRepository.kt
    MessageSyncEngine.kt
    OutboxManager.kt
    MessageIdGenerator.kt
    SeqGenerator.kt
  conversation/
    ConversationRepository.kt
    ConversationListViewModel.kt
    ConversationListScreen.kt
  chat/
    ChatViewModel.kt
    ChatScreen.kt
    MessageInputBar.kt
    MessageBubble.kt
  storage/
    ImDatabaseHelper.kt
    MessageDao.kt
    ConversationDao.kt
    PendingMessageDao.kt
  network/
    HttpClientFactory.kt
    ApiResult.kt
  diagnostics/
    NetworkLog.kt
    PerfMarkers.kt
```

核心原则：

- `protocol` 不依赖 Android UI，方便单元测试。
- `connection` 只管连接、发包、收包、状态，不直接操作 UI。
- `message` 处理 ACK、重发、去重、有序性，是项目技术亮点。
- `storage` 只暴露 DAO，不把 SQL 散落在 ViewModel。
- `ui/viewmodel` 只处理页面状态，不写协议细节。

---

## 3. 数据库设计

### `messages`

保存单聊消息主体。

```sql
CREATE TABLE messages (
  local_id INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id TEXT NOT NULL UNIQUE,
  conversation_id TEXT NOT NULL,
  sender_id TEXT NOT NULL,
  receiver_id TEXT NOT NULL,
  client_seq INTEGER NOT NULL,
  server_seq INTEGER,
  content TEXT NOT NULL,
  status TEXT NOT NULL,
  direction TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX idx_messages_conversation_time
ON messages(conversation_id, created_at DESC);

CREATE INDEX idx_messages_conversation_seq
ON messages(conversation_id, server_seq ASC);
```

### `conversations`

保存会话聚合信息，避免每次列表页都扫消息表。

```sql
CREATE TABLE conversations (
  conversation_id TEXT PRIMARY KEY,
  peer_id TEXT NOT NULL,
  peer_name TEXT NOT NULL,
  last_message_id TEXT,
  last_message_preview TEXT,
  last_message_time INTEGER NOT NULL,
  unread_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);

CREATE INDEX idx_conversations_last_time
ON conversations(last_message_time DESC);
```

### `pending_messages`

保存未 ACK 或待重发消息。

```sql
CREATE TABLE pending_messages (
  message_id TEXT PRIMARY KEY,
  packet_cmd INTEGER NOT NULL,
  packet_body TEXT NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX idx_pending_next_retry
ON pending_messages(next_retry_at ASC);
```

---

## 4. 协议路线

### 协议帧格式

建议先用 JSON Body，答辩时更容易展示字段；如果后期时间充裕，再替换 Protobuf。

```text
+----------+---------+--------+------+----------+------+
| magic(2) | ver(1)  | len(4) | cmd  | body(N)  | crc  |
+----------+---------+--------+------+----------+------+
| 0xCAFE   | 1       | Int    | Int  | JSON     | Int  |
+----------+---------+--------+------+----------+------+
```

### 命令字建议

```text
1  AUTH
2  AUTH_ACK
3  HEARTBEAT
4  HEARTBEAT_ACK
10 SEND_MESSAGE
11 MESSAGE_ACK
12 RECEIVE_MESSAGE
13 READ_ACK
20 HISTORY_QUERY
21 HISTORY_RESULT
```

### 文本消息 Body 示例

```json
{
  "messageId": "u1001-1710000000000-000001",
  "conversationId": "single:u1001:u1002",
  "senderId": "u1001",
  "receiverId": "u1002",
  "clientSeq": 42,
  "content": "hello",
  "timestamp": 1710000000000
}
```

### ACK Body 示例

```json
{
  "messageId": "u1001-1710000000000-000001",
  "conversationId": "single:u1001:u1002",
  "clientSeq": 42,
  "serverSeq": 386,
  "serverTime": 1710000000123
}
```

---

## 5. 分阶段开发路线

### Phase 0：准备与约束确认

- [ ] 创建 Android Kotlin 项目，确定包名、最低 SDK、Compose 版本。
- [ ] 获取训练营 mock server 地址、HTTP 登录文档、WebSocket 协议文档。
- [ ] 在 `README.md` 写清楚运行方式、server 地址配置方式。
- [ ] 创建 `AI_USAGE.md`、`DECISIONS.md`、`BUGFIX.md` 空文档。
- [ ] 建立提交节奏：每完成一个可运行小功能提交一次，最终保证 30 个以上有意义 commit。

验收：

- App 能启动到空登录页。
- README 包含环境要求和运行命令。

### Phase 1：登录注册与 Token 管理

- [ ] 实现 `AuthApi`，用 OkHttp 调用登录/注册 HTTP 接口。
- [ ] 实现 `TokenStore`，用 SharedPreferences 保存 JWT。
- [ ] 实现 `AuthRepository`，统一处理登录、注册、登出。
- [ ] 实现登录页 Compose UI：账号、密码、登录按钮、注册入口、错误提示。
- [ ] 登录成功后进入会话列表页。

验收：

- 正确账号能登录并保存 Token。
- 错误账号能显示失败原因。
- 重启 App 后能根据 Token 进入主界面或登录页。

### Phase 2：SQLite 基础存储

- [ ] 实现 `ImDatabaseHelper`，创建 `messages`、`conversations`、`pending_messages`。
- [ ] 实现 `MessageDao.insertOrIgnore(message)`，用 `message_id` 去重。
- [ ] 实现 `MessageDao.queryPage(conversationId, beforeTime, limit)`。
- [ ] 实现 `ConversationDao.upsertFromMessage(message)`。
- [ ] 写 DAO 单元测试，覆盖插入、分页、去重、会话更新时间排序。

验收：

- 插入重复 `messageId` 不产生重复消息。
- 1 万条消息下，会话列表查询目标小于 200ms。

### Phase 3：协议编解码

- [ ] 实现 `ImPacket` 数据结构。
- [ ] 实现 `ImPacketCodec.encode(packet): ByteString`。
- [ ] 实现 `ImPacketCodec.decode(bytes): ImPacket`。
- [ ] 实现 magic、version、length、cmd、crc 校验。
- [ ] 写协议单元测试：正常包、magic 错误、length 错误、crc 错误、未知 cmd。

验收：

- 协议单测全部通过。
- 能在日志中打印协议字段，方便后续抓包说明。

### Phase 4：WebSocket 长连接

- [ ] 实现 `ImConnection` 接口：`connect()`、`disconnect()`、`send(packet)`、`states`、`incomingPackets`。
- [ ] 用 OkHttp WebSocket 实现 `OkHttpImConnection`。
- [ ] 连接建立后发送 AUTH 包，Token 放入协议 Body。
- [ ] 将收到的二进制消息交给 `ImPacketCodec.decode`。
- [ ] 将连接状态暴露为 `StateFlow<ConnectionState>`。

验收：

- 登录后能建立 WebSocket。
- 控制台能看到连接成功、鉴权成功、收包、断开日志。

### Phase 5：单聊发送与接收

- [ ] 实现 `MessageIdGenerator`：`userId + timestamp + increasingCounter`。
- [ ] 实现 `SeqGenerator`：每个会话本地递增 `clientSeq`。
- [ ] 点击发送时先落库为 `SENDING`，再通过 WebSocket 发 `SEND_MESSAGE`。
- [ ] 收到 `MESSAGE_ACK` 后将状态更新为 `SENT`，写入 `serverSeq`。
- [ ] 收到 `RECEIVE_MESSAGE` 后落库，并更新会话列表。
- [ ] 实现聊天页：消息列表、输入框、发送按钮、发送中/失败状态。

验收：

- 两个客户端能互发文本消息。
- 发送消息先出现在本地，再变为已发送。
- 收到消息后会话列表未读数和最后消息更新。

### Phase 6：历史消息分页

- [ ] 聊天页首次进入加载最近 20 条本地消息。
- [ ] 上拉到顶部时从本地数据库加载更早 20 条。
- [ ] 如果本地不足，再向服务端请求历史消息。
- [ ] 服务端历史返回后批量落库，并按时间或 `serverSeq` 合并展示。
- [ ] 避免分页重复：用 `messageId` 去重。

验收：

- 进入聊天页能看到历史消息。
- 上拉加载更多时不闪烁、不重复。
- 断网时仍能查看本地历史。

### Phase 7：心跳与指数退避重连

- [ ] 实现 `HeartbeatManager`：连接成功后每 15 秒发送心跳。
- [ ] 如果 2 个周期未收到 `HEARTBEAT_ACK`，主动断开并触发重连。
- [ ] 实现 `ReconnectPolicy`：1s、2s、4s、8s、16s、30s 封顶。
- [ ] 前台切换时立即检查连接；后台时降低心跳频率或断开连接，恢复前台后重连。
- [ ] UI 顶部展示连接状态：连接中、已连接、重连中、离线。

验收：

- 手动断网后 App 不崩溃，状态变为离线或重连中。
- 恢复网络后能自动重连。
- 模拟 50 次断网，目标重连成功率 100%。

### Phase 8：可靠性、重发、去重、有序性

- [ ] 发送消息时写入 `pending_messages`。
- [ ] ACK 成功后删除 pending 记录。
- [ ] 超时未 ACK 的消息按退避策略重发。
- [ ] 收到重复 `messageId` 时只更新状态，不插入重复消息。
- [ ] 收到乱序消息时先落库，再按 `serverSeq` 排序展示。
- [ ] 实现 100 条并发发送测试工具或调试入口。

验收：

- 100 条并发发送，接收端展示顺序 100% 正确。
- 模拟 ACK 丢失时消息会重发。
- 重发导致服务端重复下发时，本地不重复展示。

### Phase 9：会话列表与未读

- [ ] 会话列表按 `last_message_time DESC` 排序。
- [ ] 进入聊天页后清零对应会话未读数。
- [ ] 当前打开会话收到消息时不增加未读，只刷新列表预览。
- [ ] 非当前会话收到消息时未读数 +1。
- [ ] 增加会话搜索或固定 mock 联系人入口，方便演示。

验收：

- 最近会话排序正确。
- 未读数逻辑符合常见 IM 体验。
- 1 万条消息下会话列表加载小于 200ms。

### Phase 10：性能、抓包与稳定性证据

- [ ] 使用 Charles 或 Wireshark 抓 WebSocket 协议包。
- [ ] 整理协议字段说明截图：magic、version、length、cmd、body、crc。
- [ ] 用 Android Studio Profiler 检查会话列表、聊天页、100 条并发发送。
- [ ] 准备 50 次断网重连测试记录。
- [ ] 准备 1 万条消息数据库性能测试记录。
- [ ] 录制 5 到 10 分钟 Demo 视频。

验收：

- 消息延迟同网环境目标小于 200ms。
- 50 次断网重连成功率 100%。
- 1 万条消息会话列表加载小于 200ms。
- 有协议抓包截图和字段说明。

---

## 6. 测试策略

### 单元测试优先级

- `ImPacketCodecTest`
  - 编码后解码字段完全一致。
  - magic 错误时拒绝解析。
  - crc 错误时拒绝解析。
  - length 和 body 不匹配时拒绝解析。

- `ReconnectPolicyTest`
  - 第 1 到第 6 次重连延迟分别为 1s、2s、4s、8s、16s、30s。
  - 重连成功后 reset 回 1s。

- `MessageRepositoryTest`
  - 相同 `messageId` 插入两次只保留一条。
  - ACK 后消息状态从 `SENDING` 变为 `SENT`。
  - 失败重发超过阈值后状态变为 `FAILED`。

- `ConversationDaoTest`
  - 新消息能创建或更新会话。
  - 会话列表按最后消息时间倒序。
  - 进入会话后未读清零。

### 手工验收场景

- 新用户注册并登录。
- A 给 B 连续发送 10 条消息。
- B 杀进程后重进，历史消息仍存在。
- 断网发送 3 条消息，恢复网络后自动重发。
- 快速发送 100 条消息，B 端顺序正确。
- 清空网络后重连 50 次，App 不崩溃。

---

## 7. 文档交付路线

### `README.md`

必须包含：

- 项目简介。
- 功能完成勾选表。
- 架构图。
- 协议帧格式说明。
- 数据库表结构说明。
- 运行方式。
- 测试账号或 mock server 配置。
- 性能测试结果摘要。

### `AI_USAGE.md`

至少记录 5 个 AI 辅助案例，每个案例包含：

- 问题背景。
- AI 给出的方案。
- 最终是否采纳。
- 采纳或拒绝原因。
- 人工验证方式。

建议案例：

- 自定义协议字段设计。
- SQLite 索引设计。
- 重连退避策略。
- ACK 重发机制。
- Compose 聊天列表性能优化。

### `DECISIONS.md`

用 ADR 格式记录关键决策：

```markdown
## ADR-001: 使用 OkHttp WebSocket 而不是裸 Socket

### Context
训练营允许 OkHttp WebSocket 或裸 Socket + 自写协议帧。项目重点是 IM 协议、可靠性和状态机。

### Decision
使用 OkHttp WebSocket 承载二进制协议帧，自研 Header、Body、CRC 和命令字。

### Consequences
能减少 TCP 连接细节成本，把时间集中在协议和消息可靠性；但底层 TCP 粘包拆包问题不会作为主要展示点。
```

### `BUGFIX.md`

至少记录 3 个真实踩坑：

- WebSocket 重连后重复收到旧消息。
- SQLite 分页出现重复或漏消息。
- ACK 丢失导致消息状态卡在发送中。

每个问题写清楚：现象、复现步骤、排查过程、根因、修复方案、验证方式。

---

## 8. Commit 节奏建议

目标是 30 个以上有意义 commit。可以按下面节奏提交：

```text
feat: create android project skeleton
docs: add im client roadmap
feat: add login screen
feat: implement auth api
feat: persist jwt token
feat: add sqlite database helper
feat: add message dao
test: cover message dao deduplication
feat: add conversation dao
test: cover conversation ordering
feat: define im packet model
feat: implement packet encoder
test: cover packet encoding
feat: implement packet decoder
test: cover crc validation
feat: add websocket connection
feat: authenticate websocket session
feat: add heartbeat manager
test: cover reconnect policy
feat: send local text messages
feat: handle message ack
feat: receive remote messages
feat: add chat screen
feat: add conversation list screen
feat: implement history pagination
feat: persist pending messages
feat: retry unacked messages
feat: deduplicate received messages
feat: order messages by server sequence
perf: add large message seed tool
docs: add protocol capture notes
docs: add performance report
```

---

## 9. 风险与取舍

- 不建议一开始做群聊、图片、撤回、已读、推送。它们会拉高 UI、存储和协议复杂度，容易影响基础链路稳定性。
- 不建议一开始拆多 module。训练营项目更看重可运行、可答辩、可测量，多 module 可以作为后期架构优化。
- 自定义协议建议先用 JSON Body。二进制 Header + CRC 已经能体现协议能力，JSON Body 便于调试和抓包说明。
- UI 保持清爽即可，不要把时间花在过度还原商业 IM。这个项目的分数核心在连接、可靠性、数据库性能和答辩。

---

## 10. 最小高分版本定义

如果时间有限，最终交付至少做到：

- 登录/注册可用。
- 单聊文本消息实时收发。
- 会话列表、未读数、最后消息正常。
- 历史消息分页正常。
- SQLite 手写存储，1 万条消息会话列表小于 200ms。
- 自定义二进制协议可编码、可解码、可抓包说明。
- 心跳、断线重连、前后台恢复稳定。
- ACK、重发、去重、有序展示可演示。
- README、AI_USAGE、DECISIONS、BUGFIX、性能报告、Demo 视频齐全。

这个版本已经足够支撑训练营 IM 项目的主要评分项，并且答辩时有清晰的技术叙事。
