# 群消息已读回执设计 (B12-G)

最后更新：2026-06-09

## 背景

B12 已经实现单聊消息撤回与单聊已读回执，但群聊已读明确 deferred
(`docs/status/B12-message-recall-and-read-receipts.md` 中 "Group read-member state remains out of scope")。
本设计补齐群聊已读能力，目标体验对齐抖音/微信群：

- 发送者在自己最新的一条已发送群消息下方看到 `X 人已读`。
- 点击 `X 人已读` 弹出 bottom sheet，居中标题 `已读 X 人`，下方为已读成员列表（头像 + 昵称）。
- 其它历史消息不展示已读指示器，避免视觉拥挤。
- 接收者及非发送者不可见。

设计延续 B12 已有原则：自建 WebSocket 二进制协议、本地手写 SQLite (`SQLiteOpenHelper`)、
Compose UI；不使用 Room，不引入第三方 IM SDK，不绕过 `MessagePacketProcessor`。

## 范围

### 在范围内

- 群聊文本消息和图片消息的已读人数指示器。
- 仅发送者可见。
- 仅 0 人已读时隐藏；X ≥ 1 时显示 `X 人已读`。
- 仅挂在发送者"最新一条已发送成功"（status=SENT、有 serverSeq、未撤回）的消息上。
- 撤回后自动退到上一条已发送成功的消息。
- Bottom sheet 仅展示已读成员，按 `readAt` 降序排列。
- 服务端持久化群读游标，新会话/重连/重启时补推。
- 协议复用 `READ_ACK = 13`，仅 body 加 `conversationType` 字段。
- 本地 SQLite 新增 `group_read_cursors` 表。

### 不在范围内

- 显示未读成员名单或 tab 切换。
- 接收者也可见的已读人数。
- 单聊已读 UI 任何改动（保留 B12 现有绿色对勾）。
- 已撤回消息显示"撤回前已读"。
- 已读时间点显示（"3 分钟前读"）。
- 推送通知（"X 读了你的消息"）。
- 单聊 `peer_read_up_to_server_seq` 列迁移到 cursor 表。
- 群成员退群时清理其历史 cursor 行。

## 用户故事

### US-1 发送者看到已读人数
A 在群里发出文本 / 图片消息 m。B、C 打开群聊页 → A 在 m 下方看到 `2 人已读 >`。

### US-2 发送者看不到自己读过的人数
A 自己读自己消息不计入。X = 群成员中读到 m.serverSeq 的人数 − 1（排除 A）。

### US-3 只显示最新一条
A 先后发 m1、m2、m3。仅 m3 下方可能出现 `X 人已读`，m1、m2 永不显示。

### US-4 SENDING / FAILED 时退回
若 A 的最新一条 m_latest 还在 SENDING / FAILED 状态，指示器挂在 m_latest 之前最近一条 SENT 的消息上。

### US-5 撤回后退回
A 撤回 m_latest 后，m_latest 变为居中系统提示且不显示指示器；指示器自动挂回上一条 SENT 未撤回消息。
若 A 没有任何其它 SENT 消息，整页不显示指示器。

### US-6 仅发送者本人可见
"X 人已读" 指示器只挂在 **当前登录用户本人发出** 的消息上。

- A 端看群聊：只在 A 自己发出的最新一条 SENT 消息下可能出现 indicator。A 看 B 发的消息一律无 indicator。
- B 端看同一个群聊：只在 B 自己发出的最新一条 SENT 消息下可能出现 indicator。B 看 A 发的消息一律无 indicator。
- 同一时刻，每个登录设备最多有一个 indicator 可见（指向"当前用户最近一条 SENT 消息"）。

这意味着群成员 A 和 B 各自的 indicator 是独立计算的，互不可见对方的状态。

### US-7 离线补推
A 发 m 后立即杀 App。B 此时打开群聊页读 m。A 重启 App → 进入群聊页 → 立即看到 m 下方 `1 人已读 >`。

### US-8 详情 sheet
A 点击 `X 人已读 >` → 从底部弹出 ModalBottomSheet：

- 顶部居中 `已读 X 人`
- 一条横向分割线
- 列表行为头像 + 昵称
- 按 `readAt` 降序（最近读的在最上）
- 下滑或点击外部 dismiss

## 协议设计

复用 `READ_ACK = 13`，body 增加 `conversationType` 字段。

### Client → Server

```json
{
  "conversationId": "group:g_1001",
  "conversationType": "GROUP",
  "readerId": "13900113900",
  "readUpToServerSeq": 1010,
  "readAt": 1717000000000
}
```

### Server → 所有在线群成员

```json
{
  "conversationId": "group:g_1001",
  "conversationType": "GROUP",
  "readerId": "13900113900",
  "readUpToServerSeq": 1010,
  "readAt": 1717000000000
}
```

字段说明：

- `conversationId`：`group:<groupId>`（已是 B10 约定）。
- `conversationType`：新字段；`SINGLE` 走 B12 既有路径，`GROUP` 走本设计新路径。缺省视作 SINGLE 以兼容老客户端。
- `readerId`：发起者用户 id，必须等于 WebSocket 通道认证身份；否则丢弃。
- `readUpToServerSeq`：reader 本地该会话的最大 incoming serverSeq。
- `readAt`：客户端时间戳，用于 sheet 排序。

`docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md` 必须在同一实现 pass 中更新。

## 数据模型

### Mock-server SQLite

与 `accepted_messages` 同库（`mock-server/data/mock-im-messages.sqlite`）：

```sql
CREATE TABLE IF NOT EXISTS group_read_cursors (
  group_id              TEXT    NOT NULL,
  reader_id             TEXT    NOT NULL,
  read_up_to_server_seq INTEGER NOT NULL,
  read_at               INTEGER NOT NULL,
  PRIMARY KEY(group_id, reader_id)
);
CREATE INDEX IF NOT EXISTS idx_group_read_cursors_group
  ON group_read_cursors(group_id);
```

更新规则：仅当 `new.read_up_to_server_seq > existing.read_up_to_server_seq` 时 UPSERT
（单调，防止旧包覆盖新状态）。返回值标识"是否实际更新"，用于决定是否广播。

老 SQLite 文件兼容：启动时 `CREATE TABLE IF NOT EXISTS`。

### Android SQLite

与 `messages` / `conversations` 同库（`ImDatabaseHelper`）：

```sql
CREATE TABLE IF NOT EXISTS group_read_cursors (
  group_id              TEXT    NOT NULL,
  reader_id             TEXT    NOT NULL,
  read_up_to_server_seq INTEGER NOT NULL,
  read_at               INTEGER NOT NULL,
  PRIMARY KEY(group_id, reader_id)
);
CREATE INDEX IF NOT EXISTS idx_group_read_cursors_group
  ON group_read_cursors(group_id);
```

迁移：`onUpgrade` 单独 `CREATE TABLE IF NOT EXISTS`，不破坏 `messages` / `conversations` 现有数据。

### Android Model

```kotlin
data class GroupReadCursor(
    val groupId: String,
    val readerId: String,
    val readUpToServerSeq: Long,
    val readAt: Long
)
```

### 不引入新字段的表

- `messages` 表 **不加任何字段**。已读状态完全通过 cursor 关联计算。
- `conversations.peer_read_up_to_server_seq` 保留供单聊使用；群会话该列为 NULL。
- `groups` / `group_members` 不动。

## 计算规则（端侧纯函数）

### 最新一条可挂指示器的消息

```kotlin
fun latestEligibleOwnSentMessageId(
    messages: List<ChatMessage>,  // newest-first as exposed by ChatViewModel
    currentUserId: String
): String? = messages.firstOrNull { m ->
    m.senderId == currentUserId &&
    m.direction == MessageDirection.OUTGOING &&
    m.status == MessageStatus.SENT &&
    m.serverSeq != null &&
    !m.isRecalled
}?.messageId
```

### 该消息的已读成员

```kotlin
fun readersOf(
    messageSenderId: String,
    messageServerSeq: Long?,
    cursors: List<GroupReadCursor>,
    members: List<GroupMember>
): List<GroupMember> {
    if (messageServerSeq == null) return emptyList()
    val cursorByUser = cursors.associateBy { it.readerId }
    return members
        .filter { it.userId != messageSenderId }
        .mapNotNull { m -> cursorByUser[m.userId]?.let { c -> m to c } }
        .filter { (_, c) -> c.readUpToServerSeq >= messageServerSeq }
        .sortedByDescending { (_, c) -> c.readAt }
        .map { (m, _) -> m }
}
```

两个纯函数集中在 `chat/GroupReadReceiptPolicy.kt`，单元测试覆盖所有边界。

## Mock-server 行为

### `MessageRouter.handleReadAck()` 分支

```
handleReadAck(channel, packet):
  reader = channel.authenticatedUser
  if packet.readerId != reader.id: drop (security)

  if packet.conversationType == SINGLE:
    # 既有单聊路径
    return

  if packet.conversationType == GROUP:
    groupId = parseGroupId(packet.conversationId)
    if not groupService.isMember(groupId, reader.id): drop

    persisted = groupReadCursorStore.upsertIfGreater(
        groupId, reader.id, packet.readUpToServerSeq, packet.readAt
    )
    if not persisted: return   # 旧 cursor，不广播

    members = groupService.members(groupId)
    payload = forwardPacket(packet)
    for member in members:
      session = sessionRegistry.find(member.userId)
      if session != null:
        session.send(payload)
```

### 离线补推

`SessionRegistry.onAuthenticated(user)` 在以下既有 step 之后追加新 step：

1. （既有）重发 undelivered `RECEIVE_MESSAGE`。
2. （既有）重发 pending `RECALL_NOTIFY`。
3. （新增）`replayGroupReadCursorsFor(user)`：
   ```
   joinedGroups = groupService.findByMember(user.id)
   cursors = cursorStore.findByMemberOf(joinedGroups)
   for c in cursors:
     send READ_ACK(conversationType=GROUP, ...)
   ```

补推全量 cursor 的理由：客户端要回答"每个成员读到哪里"，每个 `(group, member)` 只有一行；
100 群 × 50 成员 = 5000 行级别完全可接受，不需要按"是否影响某发送者"裁剪。

### 加群/退群行为

- 新成员加入群：不写 cursor 行。其第一次进入聊天页时由客户端发 READ_ACK 触发写入。
- 成员退群：**不**删除其历史 cursor 行，保留历史一致性。
- 删除整个群（如果将来支持）：级联清理 cursor。

## Android 实现

### 新建文件

```
app/src/main/java/com/buyansong/im/chat/
├── GroupReadReceiptPolicy.kt         # 纯函数集，单元测试焦点
├── GroupReadIndicator.kt             # Composable: 气泡下方 "X 人已读 >"
└── GroupReadDetailSheet.kt           # Composable: ModalBottomSheet

app/src/main/java/com/buyansong/im/storage/
├── GroupReadCursorDao.kt             # interface
├── AndroidGroupReadCursorDao.kt      # SQLite 实现
└── InMemoryGroupReadCursorDao.kt     # 单元测试用

app/src/main/java/com/buyansong/im/group/
└── GroupReadCursorRepository.kt      # cursor 查询入口 + 变更通知
```

### 修改文件

- `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt` — 加 `group_read_cursors` 表 CREATE。
- `app/src/main/java/com/buyansong/im/protocol/ImCommand.kt` — `READ_ACK` 已存在；若 packet parser 强类型枚举 `conversationType`，需新增。
- `app/src/main/java/com/buyansong/im/message/MessagePacketProcessor.kt` — `READ_ACK` 分支按 `conversationType` 路由。
- `app/src/main/java/com/buyansong/im/message/MessageRepository.kt` — 新增 `sendGroupReadAck(...)` / `applyIncomingGroupReadAck(...)` / `observeGroupReadCursors(groupId): Flow<List<GroupReadCursor>>`。
- `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt` — combine cursors + members + messages → 计算 latest-own-sent id 与 readers。
- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` — outgoing row 渲染 indicator slot + ModalBottomSheet 持有 state。

### `ChatViewModel` 数据流

```kotlin
combine(
    messagesFlow,
    repository.observeGroupReadCursors(groupId),
    membersFlow
) { messages, cursors, members ->
    val latestOwnId = GroupReadReceiptPolicy
        .latestEligibleOwnSentMessageId(messages, currentUserId)
    val latestOwn = messages.firstOrNull { it.messageId == latestOwnId }
    val readers = latestOwn?.let {
        GroupReadReceiptPolicy.readersOf(
            it.senderId, it.serverSeq, cursors, members
        )
    } ?: emptyList()
    state.copy(
        latestOwnSentMessageId = latestOwnId,
        groupReadCountForLatest = readers.size,
        groupReadersForLatest = readers
    )
}.stateIn(...)
```

### `MessageRepository.sendGroupReadAck`

```kotlin
suspend fun sendGroupReadAck(groupId: String, readerId: String) {
    val conversationId = "group:$groupId"
    val readUpTo = messageDao.maxIncomingServerSeq(conversationId) ?: return
    val last = lastSentGroupReadCursor[groupId] ?: -1L
    if (readUpTo <= last) return                       // 单调去重
    lastSentGroupReadCursor[groupId] = readUpTo
    websocket.send(buildReadAckPacket(
        conversationId = conversationId,
        conversationType = "GROUP",
        readerId = readerId,
        readUpToServerSeq = readUpTo,
        readAt = System.currentTimeMillis()
    ))
}
```

调用时机（与 B12 单聊一致）：
- `ChatViewModel.start()` 进入群聊页时。
- 停留群聊页期间收到新 incoming 消息后。

### `MessagePacketProcessor` 分发

```kotlin
private suspend fun handleReadAck(packet: ImPacket) {
    val body = parseReadAckBody(packet)
    when (body.conversationType) {
        ConversationType.SINGLE -> repository.applyIncomingSingleReadAck(body)
        ConversationType.GROUP  -> repository.applyIncomingGroupReadAck(body)
    }
}
```

`applyIncomingGroupReadAck` 内部：

```
1. groupReadCursorDao.upsertIfGreater(groupId, readerId, readUpTo, readAt)
2. notifyGroupReadCursorChanged(groupId)
   → emits to observeGroupReadCursors(groupId) flow
```

## UI 视觉规范

### `GroupReadIndicator`（气泡下方）

```
┌──────────────────────────────────────────────┐
│                                              │
│           ┌───────────────┐  [A 头像]         │   ← outgoing 气泡（右对齐）
│           │ 大家明天九点  │                   │
│           │ 在A会议室集合 │                   │
│           └───────────────┘                  │
│                       3 人已读 >             │   ← GroupReadIndicator
└──────────────────────────────────────────────┘
```

- 文本：`{N} 人已读 >`（末尾灰色 chevron 图标）
- 字号：12sp，颜色：`ByteImColors.PrimaryGreen`
- 对齐：跟随 outgoing 气泡右对齐
- 与气泡间距：4dp
- 命中区：整行 80% 宽度

### `GroupReadDetailSheet`（底部弹窗）

```
┌──────────────────────────────────────────────┐
│              ─────                           │   ← Material drag handle
│                                              │
│              已读 3 人                        │   ← 居中, 16sp Medium
│                                              │
│  ──────────────────────────────────────      │   ← HorizontalDivider
│                                              │
│  [头像] 张三                                  │
│  [头像] 李四                                  │
│  [头像] 王五                                  │
└──────────────────────────────────────────────┘
```

- `ModalBottomSheet` 配置：`skipPartiallyExpanded = true`
- 标题 padding：vertical 16dp
- 分割线：`HorizontalDivider`（项目既有）
- 头像：40dp（小于 GroupInfoScreen 的 48dp，因为是 row 不是 grid）
- 头像与昵称间距：12dp
- 昵称样式：`bodyLarge`，`TextPrimary`
- 列表项内边距：horizontal 16dp / vertical 10dp
- 下滑或点击外部 dismiss

## 验收标准

### 显示规则
1. 单聊页完全不显示 `GroupReadIndicator`。
2. 群聊页中 A 收到的他人消息不显示 indicator。
3. 群聊页中 A 发出的、非最新的 status=SENT 消息不显示 indicator。
4. 群聊页中 A 发出的最新一条 status=SENT 消息，且 X > 0 时显示 `X 人已读`。
5. X = 0 时隐藏整行。
6. A 最新消息处于 SENDING / FAILED 状态时，indicator 挂在更早一条 status=SENT 消息上。
7. A 撤回最新一条消息后，indicator 挂回上一条未撤回的 status=SENT 消息。
8. A 在群里没有任何 status=SENT 消息时，整页不显示 indicator。
9. 当前群聊页打开时其他成员的 READ_ACK 到达后，UI 自动重渲染人数。

### Bottom sheet
10. 点击 indicator 弹出 sheet。
11. 顶部居中 `已读 X 人`。
12. 标题下方一条横向分割线。
13. 列表按 `readAt` 降序排列。
14. 每行显示头像 + 昵称。
15. 昵称缺失时 fallback 为 userId。
16. 下滑或点击外部 dismiss。
17. 打开 sheet 期间收到新 READ_ACK 实时更新。

### 重启 / 重连
18. App 杀进程重启后，进入群聊页能立即看到正确的 `X 人已读`。
19. 群成员 B 离线期间 A 撤回了消息，B 重新上线后 A 端 indicator 已正确指向上一条。

## 测试计划

### Android 单元测试

| 测试类 | 覆盖点 |
|---|---|
| `GroupReadReceiptPolicyTest` | latestEligibleOwnSentMessageId 各 status / 撤回 / serverSeq=null 边界 |
| `GroupReadReceiptPolicyTest` | readersOf 排除发送者、按 readAt 排序、cursor 阈值 |
| `MessageRepositoryGroupReadAckTest` | applyIncomingGroupReadAck 单调更新 / 通知 emit |
| `MessageRepositoryGroupReadAckTest` | sendGroupReadAck 去重不重发同/小 cursor |
| `ChatViewModelGroupReadReceiptTest` | 进入群聊页触发 sendGroupReadAck 一次 |
| `ChatViewModelGroupReadReceiptTest` | combine 流正确推导 latest-own-sent + readers |
| `ChatViewModelTest` (回归) | 单聊不触发 group read 路径 |
| `MessagePacketProcessorTest` | READ_ACK 按 conversationType 路由 |
| `AndroidGroupReadCursorDaoContractTest` | CRUD + 单调 UPSERT + index 工作 |

### Mock-server 测试

| 测试类 | 覆盖点 |
|---|---|
| `MessageRouterGroupReadAckTest` | 群 READ_ACK 广播给所有在线成员 |
| `MessageRouterGroupReadAckTest` | 非群成员发 READ_ACK 被丢弃 |
| `MessageRouterGroupReadAckTest` | readerId 与认证身份不符被丢弃 |
| `MessageRouterGroupReadAckTest` | 单调过滤旧 cursor 不广播 |
| `MessageRouterGroupReadAckTest` | 同 cursor 重复不广播 |
| `SQLiteGroupReadCursorStoreTest` | upsertIfGreater 单调语义 |
| `SQLiteGroupReadCursorStoreTest` | findByMemberOf 多群多成员组合查询 |
| `SessionRegistryReplayTest` | auth/reconnect 时补推 cursor |
| `SessionRegistryReplayTest` | 表缺失时 CREATE TABLE IF NOT EXISTS 兼容 |

### 手工验证（最少 3 设备）

```
设备 A (sender) / B / C (members) 同一群 g_test

T0:  A 进入群聊页
T1:  A 发 m1 ("第一条")          → A 看到 m1，无 indicator
T2:  B 打开群聊页 → READ_ACK     → A 端 m1 下方 "1 人已读 >"
T3:  A 发 m2 ("第二条")          → m1 上 indicator 消失；m2 无 (0 人已读)
T4:  B、C 同时打开聊天页          → A 端 m2 下方 "2 人已读 >"
T5:  A 点击 "2 人已读 >"          → sheet 打开，标题 "已读 2 人"
                                    列表 B (top, readAt 较新) / C
T6:  A 在 2 分钟内撤回 m2         → m2 → 居中系统提示
                                    m1 下方现 "2 人已读 >"
T7:  A 杀进程重启进入群聊         → m1 下方仍 "2 人已读 >"
T8:  A 发 m3，A 杀进程
T9:  B 在 A 离线期间打开聊天页    → 读了 m3
T10: A 重新启动                  → m1 上 indicator 消失
                                    m3 下方出现 "1 人已读 >"
```

## 文档更新

实现完成后必须更新：

- `docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md` — `READ_ACK` 加 `conversationType` 字段，
  补充群聊单聊两套 JSON 示例。
- `docs/status/B12-message-recall-and-read-receipts.md` — 新增 "Group Read Receipts" 子节，
  状态 = 已实现；将 "Group read-member state remains out of scope" 改为 "Group read indicator
  implemented; per-member read list deferred unless requested"。
- `docs/bg/DEVELOPMENT_STATUS.md` — B12 行从「单聊已读回执已实现」改为「单聊 + 群聊已读回执已实现」。

## 风险

- **协议 `conversationType` 兼容**：老客户端发的 `READ_ACK` 不带该字段时必须 fallback 为 SINGLE，
  否则会被群聊路径误处理。Mock-server 解析时显式默认值处理。
- **`MessagePacketProcessor` 单 consumer 约束**：B12 已明确"Android 必须由
  login-session scoped `MessagePacketProcessor` 唯一消费 WebSocket 包"。新分支必须放在
  `MessagePacketProcessor.handleReadAck` 内部分发；**禁止**在 ChatViewModel 直接监听 READ_ACK。
- **单调更新破坏**：旧 cursor 覆盖新 cursor 会导致 UI 从"已读"回退到"未读"。
  store 层和 Android dao 必须严格 `>` 比较。
- **离线补推风暴**：100 群 × 50 成员 = 5000 行级别可接受；但若群规模显著上升（>500 成员）
  需评估是否按"涉及当前用户作为 sender 的群"裁剪。当前不做。
- **撤回与 indicator 重新计算**：`isRecalled` 变化必须能触发 ChatViewModel 重组合。
  确保 `observeMessages` flow 在 RECALL_NOTIFY 应用后 emit。
- **退群后历史不清理**：现网用户预期"退群后看不到我读过的消息"，但本设计保留 cursor 用于
  历史一致性。明确不做。
- **ModalBottomSheet 在键盘抬起时的 imePadding 行为**：sheet 打开时聊天页输入法已经收起，
  通常不会与 imePadding 冲突；若发现冲突则在 sheet 内显式 `windowInsets = WindowInsets(0)`。
