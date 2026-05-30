# B12 消息撤回与已读回执设计

## 目标

B12 要实现两个消息状态同步能力：

- 消息撤回：发送方在 2 分钟内撤回自己发送的消息，双方本地消息状态同步为已撤回。
- 已读回执：接收方打开单聊页后，上报自己已经读到的消息位置，发送方 UI 将未读圆圈更新为绿色对勾。

这两个能力都不是普通聊天消息，而是对已有消息或会话读状态的更新。它们应基于现有 WebSocket 自定义二进制协议传输，SQLite 保存最终状态，Compose UI 根据本地状态刷新。

## 现有基础

当前项目已经具备 B12 需要复用的基础：

- `SEND_MESSAGE`：发送普通消息。
- `MESSAGE_ACK`：服务端确认消息已接收并分配 `serverSeq`。
- `RECEIVE_MESSAGE`：服务端转发消息给接收方。
- `DELIVERY_ACK`：接收端确认消息已落本地库，但这不是已读。
- `READ_ACK`：协议枚举中已预留，适合作为已读回执命令。
- `serverSeq`：服务端按会话分配的权威消息顺序，可作为已读游标。
- `MessagePacketProcessor`：登录会话级别的消息包消费者，适合统一处理已读和撤回通知。
- SQLite `messages` 表：保存本地消息状态，适合扩展撤回字段。

## 单聊已读回执设计

### 核心理解

单聊中只有 A 和 B：

```text
A = 发送方
B = 接收方
```

A 发消息给 B 时，消息状态经历三层：

```text
A 发消息 -> 服务端收到
服务端转发 -> B 的手机收到并存入 SQLite
B 打开和 A 的聊天页 -> 这才算已读
```

对应协议语义：

```text
MESSAGE_ACK  = 服务端告诉 A：我收到了你的消息
DELIVERY_ACK = B 的设备告诉服务端：我已经把消息存入本地
READ_ACK     = B 告诉服务端：用户已经看到这些消息
```

`DELIVERY_ACK` 不能作为已读回执，因为它只证明设备持久化了消息，不证明用户看到了消息。

### B 如何判断自己打开了 A 的聊天页

这个判断发生在客户端，不由后端主动判断。

当 B 客户端进入聊天页：

```text
ChatScreen(currentUserId = B, peerId = A)
```

客户端即可认为用户正在查看与 A 的单聊会话。此时客户端自动发送 `READ_ACK` WebSocket 包给服务端。

第一版不需要新增 HTTP 接口。因为项目已经有长连接和自定义二进制协议，已读状态应直接走 WebSocket。HTTP 以后最多用于 App 重启后拉取远端最新已读游标的补偿能力。

### READ_ACK 数据结构

B 打开与 A 的聊天页后，从本地消息中找到当前会话已收到消息的最大 `serverSeq`：

```text
maxIncomingServerSeq = max(serverSeq where direction = INCOMING)
```

然后发送：

```json
{
  "conversationId": "single:A:B",
  "readerId": "B",
  "peerId": "A",
  "readUpToServerSeq": 1008,
  "readAt": 1717000000000
}
```

服务端收到后，记录或转发给 A：

```json
{
  "conversationId": "single:A:B",
  "readerId": "B",
  "readUpToServerSeq": 1008,
  "readAt": 1717000000000
}
```

A 客户端收到后，更新本地会话读游标。

### 已读 UI

只在自己发出的消息末尾展示已读状态：

```text
未读：空心圆圈
已读：绿色对勾
```

A 本地判断逻辑：

```text
message.direction == OUTGOING
message.serverSeq != null
message.serverSeq <= conversation.peerReadUpToServerSeq
```

满足条件则显示绿色对勾，否则显示空心圆圈。

### 单聊本地存储

单聊第一版推荐将读游标放在 `conversations` 表，而不是给每条消息单独写 `read_at`：

```sql
ALTER TABLE conversations ADD COLUMN peer_read_up_to_server_seq INTEGER;
ALTER TABLE conversations ADD COLUMN peer_read_at INTEGER;
```

原因：

- 单聊只关心“对方读到哪里”。
- `serverSeq` 是会话内递增的，使用游标即可推导每条消息是否已读。
- 避免每次已读都批量更新大量 `messages` 行。
- 以后扩展群聊时，可以改用成员维度的读游标表。

### 已读触发时机

第一版建议：

- 进入聊天页时发送一次 `READ_ACK`。
- 停留在聊天页期间收到新消息后，再发送一次新的 `READ_ACK`。
- 只有当前会话是打开状态时才发送已读。
- 只对已落库并拥有 `serverSeq` 的收到消息发送已读。

为了避免频繁发送，可以做简单去重：

```text
如果本次 readUpToServerSeq <= 上次已发送 readUpToServerSeq，则不重复发送。
```

## 单聊消息撤回设计

### 核心理解

撤回不是删除消息，而是把消息标记为已撤回。

消息原始记录仍保留在 SQLite 中，但 UI 不再展示原文本或图片，而是在聊天列表中显示一条提示：

```text
发送方看到：你撤回了一条消息
接收方看到：对方撤回了一条消息
```

这样聊天记录顺序不会突然断裂，也方便会话列表显示最后一条消息为撤回提示。

### 协议命令

建议新增三个命令：

```text
RECALL_MESSAGE = 15  客户端 -> 服务端，请求撤回消息
RECALL_ACK     = 16  服务端 -> 请求方，返回撤回结果
RECALL_NOTIFY  = 17  服务端 -> 对方，通知消息已撤回
```

撤回请求：

```json
{
  "messageId": "m_001",
  "conversationId": "single:A:B",
  "requesterId": "A",
  "requestAt": 1717000000000
}
```

撤回成功 ACK：

```json
{
  "messageId": "m_001",
  "conversationId": "single:A:B",
  "success": true,
  "recalledBy": "A",
  "recalledAt": 1717000001000
}
```

撤回失败 ACK：

```json
{
  "messageId": "m_001",
  "conversationId": "single:A:B",
  "success": false,
  "reason": "EXPIRED"
}
```

撤回通知：

```json
{
  "messageId": "m_001",
  "conversationId": "single:A:B",
  "recalledBy": "A",
  "recalledAt": 1717000001000
}
```

### 服务端校验规则

撤回应由服务端做最终裁决：

- 消息必须存在。
- 请求人必须是原消息发送者。
- 消息必须属于当前会话。
- 当前时间距离服务端接收消息时间不超过 2 分钟。
- 已撤回消息重复撤回应幂等处理。

客户端可以提前隐藏不符合条件的撤回菜单，但不能只依赖客户端校验。

### 撤回本地存储

`messages` 表建议增加字段：

```sql
ALTER TABLE messages ADD COLUMN is_recalled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN recalled_at INTEGER;
ALTER TABLE messages ADD COLUMN recalled_by TEXT;
```

Android model 可扩展：

```kotlin
data class ChatMessage(
    ...
    val isRecalled: Boolean = false,
    val recalledAt: Long? = null,
    val recalledBy: String? = null
)
```

DAO 增加：

```kotlin
fun markRecalled(
    messageId: String,
    recalledBy: String,
    recalledAt: Long
): Boolean
```

### 撤回 UI

消息渲染时优先判断撤回状态：

```text
if message.isRecalled:
    if message.senderId == currentUserId:
        显示“你撤回了一条消息”
    else:
        显示“对方撤回了一条消息”
else:
    正常显示文本或图片
```

撤回提示仍然作为 `LazyColumn` 中的一项展示，不直接从列表中过滤掉。

会话列表 preview 建议同步更新为：

```text
你撤回了一条消息
对方撤回了一条消息
```

如果撤回的不是当前最后一条消息，则不需要改会话 preview。

## 聊天气泡与长按菜单入口设计

当前聊天页的消息列表位于 `ChatScreen.kt`：

```text
LazyColumn
  -> items(state.messages)
  -> ChatMessageRow(...)
```

目前 `ChatMessageRow` 中，文本消息只是直接渲染为 `Text(message.content)`，因此缺少两个能力：

- 类似微信的文本气泡背景。
- 长按消息后弹出“复制 / 撤回”等操作菜单。

这些能力应该放在聊天消息行内部，而不是放在 `LazyColumn` 外层，也不应该放在 ViewModel 中。ViewModel 只负责执行撤回等业务动作。

### 推荐组件拆分

建议将聊天消息展示拆成以下结构：

```text
ChatScreen.kt
  LazyColumn
    ChatMessageRow
      ChatTextBubble
      ChatImageBubble
      ChatMessageActionMenu
```

职责划分：

- `ChatMessageRow`：负责消息左右布局、头像、发送状态、已读状态。
- `ChatTextBubble`：负责文本消息气泡背景、圆角、文本内容、长按手势。
- `ChatImageBubble`：负责图片消息展示，后续也支持长按菜单。
- `ChatMessageActionMenu`：负责展示“复制 / 撤回 / 重发 / 保存图片”等菜单项。
- `ChatDisplayPolicy`：负责菜单项是否显示、撤回时间窗口、提示文案等纯判断逻辑。

第一版可以先实现文本气泡和文本长按菜单，图片气泡后续复用同一个菜单模型。

### 文本气泡设计

文本消息不再直接使用裸 `Text`，而是新增：

```kotlin
@Composable
fun ChatTextBubble(
    message: ChatMessage,
    currentUserId: String,
    onCopy: (String) -> Unit,
    onRecall: (ChatMessage) -> Unit
)
```

视觉建议：

- 自己发送的消息：右侧展示，浅绿色气泡。
- 对方发送的消息：左侧展示，浅灰或白色气泡。
- 气泡使用圆角背景和合适内边距。
- 文字最大宽度应受屏幕宽度约束，避免长文本横向撑开。

### 长按菜单设计

文本气泡使用 Compose 的长按手势：

```kotlin
Modifier.combinedClickable(
    onClick = {},
    onLongClick = {
        showMenu = true
    }
)
```

长按后展示 `DropdownMenu`：

```text
复制
撤回
```

菜单项显示规则：

```kotlin
fun canCopy(message: ChatMessage): Boolean {
    return message.type == MessageType.TEXT && !message.isRecalled
}

fun canRecall(message: ChatMessage, currentUserId: String, now: Long): Boolean {
    return message.senderId == currentUserId &&
        message.status == MessageStatus.SENT &&
        message.serverSeq != null &&
        !message.isRecalled &&
        now - message.createdAt <= 2 * 60 * 1000
}
```

在 B12 撤回字段落库前，可以先做气泡背景和“复制”。等 `isRecalled`、`RECALL_MESSAGE` 等能力完成后，再接入“撤回”菜单项。

### 复制与撤回职责边界

复制：

- 不需要经过 ViewModel。
- `ChatTextBubble` 或上层 `ChatScreen` 可以直接使用 `ClipboardManager`。
- 复制内容为当前文本消息的 `content`。
- 已撤回消息不能复制。

撤回：

- UI 只负责展示菜单入口。
- 点击撤回后调用 `viewModel.recallMessage(message.messageId)`。
- ViewModel 再调用 Repository 发送 `RECALL_MESSAGE`。
- 撤回成功或失败最终由 `RECALL_ACK` 驱动本地状态和 UI 更新。

### 和已读状态的位置关系

已读/未读图标属于消息行状态，不属于气泡内容本身。

推荐布局：

```text
对方消息：
头像  气泡

自己消息：
未读/已读图标  气泡  头像
```

也可以将已读图标放在气泡右下角或消息末尾，但第一版建议跟随现有 `OutgoingMessageStatus` 位置，改造成本更低。

## 单聊完整流程

### 已读流程

```text
1. A 给 B 发送消息。
2. 服务端返回 MESSAGE_ACK，A 显示消息已发送，但末尾是空心圆圈。
3. 服务端转发 RECEIVE_MESSAGE 给 B。
4. B 落库后返回 DELIVERY_ACK。
5. B 打开与 A 的聊天页。
6. B 客户端计算当前会话最大 incoming serverSeq。
7. B 发送 READ_ACK(readUpToServerSeq) 给服务端。
8. 服务端转发 READ_ACK 给 A。
9. A 更新 conversations.peer_read_up_to_server_seq。
10. A 的消息末尾从空心圆圈变为绿色对勾。
```

### 撤回流程

```text
1. A 长按自己已发送成功的消息。
2. 如果消息在 2 分钟内，客户端展示“撤回”菜单。
3. A 点击撤回，发送 RECALL_MESSAGE 给服务端。
4. 服务端校验发送者、会话、时间窗口。
5. 服务端返回 RECALL_ACK 给 A。
6. 服务端发送 RECALL_NOTIFY 给 B。
7. A 和 B 都将本地消息标记为 is_recalled = 1。
8. UI 将原消息替换为“你撤回了一条消息”或“对方撤回了一条消息”。
```

## 群聊扩展思路

单聊第一版设计要为群聊保留扩展空间。

### 群聊已读

单聊可以将读游标放在 `conversations` 表中，因为只有一个对方。

群聊需要按成员记录读游标：

```sql
CREATE TABLE conversation_read_cursors (
  conversation_id TEXT NOT NULL,
  reader_id TEXT NOT NULL,
  read_up_to_server_seq INTEGER NOT NULL,
  read_at INTEGER NOT NULL,
  PRIMARY KEY(conversation_id, reader_id)
);
```

群聊 READ_ACK 仍然使用同一种协议结构：

```json
{
  "conversationId": "group:g001",
  "readerId": "U1001",
  "readUpToServerSeq": 2008,
  "readAt": 1717000000000
}
```

服务端收到后广播或按需同步给群成员。

群聊 UI 可以分阶段实现：

- 第一阶段：显示“已读 N 人”。
- 第二阶段：点击查看已读成员列表。
- 第三阶段：区分已读和未读成员列表。

### 群聊撤回

群聊撤回规则与单聊基本一致：

- 只能撤回自己发送的消息。
- 2 分钟内可撤回。
- 服务端广播 `RECALL_NOTIFY` 给群成员。
- 所有成员本地将该消息标记为已撤回。

群聊 UI 文案可设计为：

```text
你撤回了一条消息
张三撤回了一条消息
```

## 开发步骤

### 第 1 步：先补齐聊天气泡和长按菜单基础

修改：

- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/chat/ChatDisplayPolicy.kt`
- 可新增 `app/src/main/java/com/codex/im/chat/ChatTextBubble.kt`
- 可新增 `app/src/main/java/com/codex/im/chat/ChatMessageActionMenu.kt`

目标：

- 将文本消息从裸 `Text` 改为 `ChatTextBubble`。
- 为文本消息增加气泡背景、圆角、内边距、最大宽度。
- 为文本气泡增加长按菜单。
- 第一版至少支持“复制”。
- B12 撤回落地后，将“撤回”菜单接到 `viewModel.recallMessage(...)`。

建议先补测试：

- `ChatDisplayPolicy.canCopy(...)`：文本且未撤回时允许复制。
- `ChatDisplayPolicy.canRecall(...)`：只有本人、`SENT`、有 `serverSeq`、未撤回、2 分钟内允许撤回。
- 超过 2 分钟或非本人消息不允许撤回。

### 第 2 步：扩展协议枚举和协议文档

修改 Android 和 mock-server 的 `ImCommand`：

- Android：`app/src/main/java/com/codex/im/protocol/ImCommand.kt`
- Mock server：`mock-server/src/main/java/com/codex/imserver/protocol/ImCommand.java`

新增：

```text
RECALL_MESSAGE(15)
RECALL_ACK(16)
RECALL_NOTIFY(17)
```

更新 `docs/WEBSOCKET_PROTOCOL_AND_STATES.md`，补充 `READ_ACK`、`RECALL_MESSAGE`、`RECALL_ACK`、`RECALL_NOTIFY` 的方向和语义。

### 第 3 步：扩展 Android 本地存储

修改：

- `app/src/main/java/com/codex/im/storage/ImDatabaseHelper.kt`
- `app/src/main/java/com/codex/im/storage/StorageModels.kt`
- `app/src/main/java/com/codex/im/storage/MessageDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidMessageDao.kt`
- `app/src/main/java/com/codex/im/storage/ConversationDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidConversationDao.kt`

消息表新增撤回字段：

```sql
is_recalled INTEGER NOT NULL DEFAULT 0
recalled_at INTEGER
recalled_by TEXT
```

会话表新增单聊已读游标：

```sql
peer_read_up_to_server_seq INTEGER
peer_read_at INTEGER
```

DAO 增加能力：

- 根据 `messageId` 标记消息已撤回。
- 查询当前会话最大 incoming `serverSeq`。
- 更新会话的对方已读游标。
- 根据撤回状态生成会话 preview。

### 第 4 步：实现 Android 已读发送

修改：

- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`

在进入聊天页时：

```text
openConversation(currentUserId, peerId)
-> clearUnread
-> findMaxIncomingServerSeq
-> send READ_ACK
```

停留在聊天页收到新消息时，如果该消息属于当前会话，也发送新的 `READ_ACK`。

需要避免重复发送相同或更小的 `readUpToServerSeq`。

### 第 5 步：实现 Android 已读接收

修改：

- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- `app/src/main/java/com/codex/im/message/MessagePacketProcessor.kt`

`handlePacket()` 中处理 `READ_ACK`：

```text
收到 READ_ACK
-> 校验 conversationId
-> 更新 conversations.peer_read_up_to_server_seq
-> notifyConversationChanged()
```

Chat UI 根据会话读游标渲染自己发出消息末尾的状态：

```text
未读：空心圆圈
已读：绿色对勾
```

### 第 6 步：实现 mock-server 已读转发

修改：

- `mock-server/src/main/java/com/codex/imserver/session/MessageRouter.java`
- `mock-server/src/main/java/com/codex/imserver/netty/WebSocketFrameHandler.java`

服务端收到 `READ_ACK`：

```text
1. 确认发送 ACK 的用户是 readerId。
2. 从单聊 conversationId 或 peerId 找到对方。
3. 如果对方在线，转发 READ_ACK。
4. 第一版可以不持久化；如果要支持重启恢复，再保存读游标。
```

### 第 7 步：实现 Android 撤回请求

修改：

- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`

UI 长按自己发送成功的消息时，显示撤回入口：

```text
message.direction == OUTGOING
message.status == SENT
message.serverSeq != null
message.isRecalled == false
now - message.createdAt <= 2 分钟
```

点击撤回后发送 `RECALL_MESSAGE`。

### 第 8 步：实现 mock-server 撤回校验和通知

修改：

- `mock-server/src/main/java/com/codex/imserver/session/MessageRouter.java`
- mock-server accepted message persistence 相关结构

服务端收到 `RECALL_MESSAGE`：

```text
1. 查找 messageId。
2. 校验 requesterId 是 senderId。
3. 校验当前时间距离 serverTime 不超过 2 分钟。
4. 标记服务端消息为 recalled。
5. 给请求方发送 RECALL_ACK。
6. 给对方发送 RECALL_NOTIFY。
```

第一版如果接收方离线，可以在服务端持久化撤回状态，并在对方重新 auth 后补发 `RECALL_NOTIFY`，否则会出现对方本地仍显示原消息的问题。

### 第 9 步：实现 Android 撤回接收和 UI 渲染

修改：

- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- `app/src/main/java/com/codex/im/chat/ChatDisplayPolicy.kt`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/src/main/java/com/codex/im/conversation/ConversationListViewModel.kt`

处理：

- `RECALL_ACK(success = true)`：请求方本地标记消息已撤回。
- `RECALL_ACK(success = false)`：显示失败原因，比如超过 2 分钟。
- `RECALL_NOTIFY`：接收方本地标记消息已撤回。

UI 渲染：

```text
if message.isRecalled:
    当前用户是 sender -> 你撤回了一条消息
    当前用户不是 sender -> 对方撤回了一条消息
else:
    正常消息气泡
```

### 第 10 步：补测试

Android 单元测试建议覆盖：

- 文本消息渲染使用气泡容器而不是裸文本。
- 长按文本消息可以展示复制入口。
- 可撤回消息展示撤回入口。
- 进入聊天页会发送 `READ_ACK`。
- 收到 `READ_ACK` 后更新会话读游标。
- outgoing 消息根据读游标显示未读圆圈或绿色对勾。
- 2 分钟内的自己消息可以发起撤回。
- 超过 2 分钟的消息不展示撤回入口。
- 收到 `RECALL_NOTIFY` 后消息标记为已撤回。
- 已撤回消息渲染为提示文案，不再渲染文本或图片气泡。

Mock-server 测试建议覆盖：

- `READ_ACK` 转发给单聊对方。
- 非本人不能撤回消息。
- 超过 2 分钟不能撤回。
- 撤回成功后请求方收到 `RECALL_ACK`。
- 撤回成功后对方收到 `RECALL_NOTIFY`。
- 重复撤回幂等。
- 对方离线后重连能同步撤回状态。

### 第 11 步：更新状态文档和验收说明

修改：

- `docs/DEVELOPMENT_STATUS.md`
- `docs/WEBSOCKET_PROTOCOL_AND_STATES.md`
- 可新增 `docs/status/B12-message-recall-and-read-receipts.md`

验收点：

- 文本消息显示为左右气泡，而不是裸文本。
- 长按文本气泡可以复制消息内容。
- 自己 2 分钟内的已发送消息长按后可以看到撤回入口。
- A 给 B 发消息，B 未打开聊天页时，A 看到空心圆圈。
- B 打开与 A 的聊天页后，A 看到绿色对勾。
- A 在 2 分钟内撤回消息，A 显示“你撤回了一条消息”。
- B 收到撤回通知后，B 显示“对方撤回了一条消息”。
- A 超过 2 分钟不能撤回。
- 重启或重连后，已读和撤回状态不应丢失。
