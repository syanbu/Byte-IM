# WebSocket 协议消息与客户端状态说明

本文说明 WebSocket 网络协议消息和 Android 本地连接状态之间的区别。

## 两个不同层次

WebSocket protocol message 是 Android client 和 mock server 之间通过网络发送的 packet。

Android `ConnectionState` 是客户端本地用于描述连接生命周期的 UI/状态机状态。

两者不能混在一起理解：

```text
Protocol message (ImEnvelope payload):
  auth / auth_ack / send_message / message_ack / receive_message / ...

Android local state:
  Disconnected / Connecting / Authenticating / Authenticated / Reconnecting / Failed
```

协议消息是“线上传输的命令”；本地状态是“客户端当前连接处于什么阶段”。

## WebSocket 协议消息类型

协议 v2 起，所有线上消息都是 Protobuf `ImEnvelope`（schema 见 `protocol-schema/src/main/proto/im/v2/im_protocol.proto`）：一个 WebSocket 二进制消息 = 一个 envelope，消息类型由 `oneof payload` 唯一标识，不再存在数值 cmd。核心 payload 包括：

| payload | 方向 | 含义 |
|---|---|---|
| `auth` | client -> server | WebSocket 建立后，客户端发送 access token 做鉴权 |
| `auth_ack` | server -> client | 服务端鉴权成功 |
| `auth_nack` | server -> client | 服务端鉴权失败 |
| `send_message` | client -> server | 客户端发送聊天消息 |
| `message_ack` | server -> sender | 服务端接受消息并分配 `server_seq` |
| `receive_message` | server -> receiver | 服务端向接收方转发消息 |
| `delivery_ack` | receiver -> server | 接收方已将消息本地持久化 |
| `heartbeat` | client -> server | 客户端心跳；字段为 `client_time` + `unacked_message_ids`，捎带发送方未确认消息 id（上限 100 条）用于对账 |
| `heartbeat_ack` | server -> client | 服务端心跳响应；字段为 `server_time` + `received_message_ids`，返回服务端已持久化接受的 id 子集（仅当请求携带 `unacked_message_ids` 时返回该字段） |
| `read_ack` | receiver -> server -> sender | 已读回执 |
| `recall_message` | client -> server | 请求撤回消息 |
| `recall_ack` | server -> requester | 撤回结果 |
| `recall_notify` | server -> peer | 通知对方消息已撤回 |
| `recall_notify_ack` | receiver -> server | 接收方已将撤回状态本地持久化 |

每个 envelope 带 `protocol_version = 2`，恰好设置一个 `oneof payload`，编解码大小上限 64 KiB。没有应用层 magic/length/CRC：消息边界由 WebSocket 保证，生产环境完整性由 WSS/TLS 保证。

## Android `ConnectionState`

Android 本地连接状态用于驱动 UI 和重连逻辑，不等同于协议命令。

常见状态包括：

- `Disconnected`：当前没有连接。
- `Connecting`：正在建立 WebSocket。
- `Authenticating`：WebSocket 已打开，正在发送或等待 `AUTH` / `AUTH_ACK`。
- `Authenticated`：WebSocket 已鉴权，可以收发业务 packet。
- `Reconnecting`：连接失效后等待重连。
- `Failed`：不可恢复或当前无法继续的失败状态。

页面 UI 应根据本地 `ConnectionState` 展示连接状态；协议处理器应根据 envelope 的 `payloadCase` 分发网络消息。

## 启动流程

正常启动流程：

```text
用户登录或恢复登录态
  -> ConnectionLifecycleManager.connect()
  -> OkHttp WebSocket onOpen
  -> client 发送 AUTH
  -> mock server 校验 access token
  -> server 返回 AUTH_ACK
  -> Android 进入 Authenticated
  -> MessagePacketProcessor 开始处理业务消息
  -> MessageOutboxWorker 重试到期 pending 消息
```

`ChatViewModel` 和 `ConversationListViewModel` 不应直接消费 WebSocket 入站 packet。它们只从 repository/storage update event 刷新 UI。

## 心跳与重连流程

B7 在 raw OkHttp connection 外包了一层 Android-side `ConnectionLifecycleManager`。

它负责：

- 前台使用较短心跳间隔。
- 后台使用较长心跳间隔。
- 发送 `heartbeat`（捎带未确认消息 id 列表）。
- 等待 `heartbeat_ack`（读取 `received_message_ids` 并回调给 Outbox 做对账）。
- 如果心跳 ACK 超时，断开并进入重连。
- 连接失败后按指数退避重连。
- 网络恢复时唤醒已有重连等待。

失败写入也使用同一套重连策略：

```text
send(packet) failed
  -> disconnect
  -> Reconnecting(delayMillis, reason)
  -> delay
  -> reconnect with fresh token
```

前后台策略：

- 前台：更快的心跳和重连响应。
- 后台：更慢的心跳，减少资源消耗。
- 回到前台：恢复更快心跳间隔。

B7 的重连只恢复 WebSocket session。B9 在 `ConnectionState.Authenticated` 之上增加登录会话级 outbox worker，用于 reconnect 后重试到期 pending sender message。

## 消息有序性

B8 定义了两个 sequence 字段，它们的信任边界不同：

- ~~`clientSeq`~~：已移除（proto 字段 reserved）。ACK 关联由 `messageId` 承担，本地发送顺序由单调递增 `createdAt` 承担。
- `serverSeq`：服务端按 conversation 分配，是跨端展示顺序的权威依据。

本地 mock server 在通过 `MockImServer` 运行时，会把每个 conversation 的最后分配序号持久化到：

```text
mock-server/data/mock-im-sequences.sqlite
```

这样 server 重启后不会分配比 Android 本地已存消息更小的 `serverSeq`，避免新消息看起来比旧消息更早。

accepted chat messages 另存在：

```text
mock-server/data/mock-im-messages.sqlite
```

该存储保留 sender `messageId` 幂等和 receiver 未投递重放状态，不改变 `message_ack` 或 `delivery_ack` 的含义。

Android 展示排序策略：

- 已确认/已接收并带 `serverSeq` 的消息按 `serverSeq` 排序。
- 本地尚未 ACK 的 outgoing message 使用本地时间插入当前列表。
- 重复 `messageId` 不应插入第二条本地消息。

## 消息状态

协议 ACK 和本地消息状态也不是同一层。

当前本地消息状态包括：

- `UPLOADING`
- `UPLOAD_FAILED`
- `SENDING`
- `SENT`
- `FAILED`
- `RECEIVED`

Outgoing 消息流程：

```text
create local row
  -> SENDING 或 UPLOADING
  -> send_message
  -> message_ack
  -> SENT
```

如果发送或上传失败：

```text
UPLOADING -> UPLOAD_FAILED
SENDING   -> FAILED
```

Incoming 消息流程：

```text
receive_message
  -> persist message locally
  -> RECEIVED
  -> send delivery_ack
```

`message_ack` 只表示 server 接受 sender 的消息。它不表示 receiver 已收到，更不表示 receiver 已读。

`delivery_ack` 只表示 receiver 设备已经持久化消息。它不表示用户已经看到消息。

## 已读回执

B12 使用 `read_ack` payload 表示已读回执，并且明确和 `delivery_ack` 分离。

**单聊字段（`ReadAck`）：**

```text
conversation_id          = "single:u_1001:u_1002"
conversation_type        = CONVERSATION_TYPE_SINGLE
reader_id                = "13900113900"
peer_id                  = "u_1002"
read_up_to_server_seq    = 1010
read_at                  = 1717000000000
```

Server forwards this envelope verbatim to the peer identified by `peer_id` (if online). B12 behavior.

**群聊字段（B12-G 新增路径）：**

```text
conversation_id          = "group:g_1001"
conversation_type        = CONVERSATION_TYPE_GROUP
reader_id                = "13900113900"
read_up_to_server_seq    = 1010
read_at                  = 1717000000000
```

群路径 `conversation_type` 必须是 `CONVERSATION_TYPE_GROUP`。The server:
1. Upserts `(groupId, readerId) -> (readUpToServerSeq, readAt)` monotonically (`>` only).
2. If the upsert actually advanced, broadcasts the same payload to every online group member.

当 `conversation_type` 未设置（`UNSPECIFIED`）时，server 按单聊处理（legacy fallback）。

Android 在 conversation 上保存 peer read cursor，并且只允许它向前移动。

聊天 UI 用下面条件推导 outgoing 消息的已读标记：

```text
message.direction == OUTGOING
message.serverSeq != null
message.serverSeq <= conversation.peerReadUpToServerSeq
```

## 消息撤回

B12 的撤回是状态更新，不是删除消息。

Android 保留原始 `messages` row，只标记为 recalled；UI 渲染撤回提示，而不是原始文本或图片。

撤回相关 payload：

```text
recall_message
recall_ack
recall_notify
recall_notify_ack
```

成功的 `recall_ack` 和 `recall_notify` 包含：

```text
message_id       = "m1"
conversation_id  = "single:u1:u2"
recalled_by      = "u1"
recalled_at      = 1717000000000
```

本地收到成功撤回结果后：

- 标记消息 `isRecalled = true`。
- 保存 `recalledAt` 和 `recalledBy`。
- 如果该消息仍是 conversation 最新消息，更新 conversation preview。
- 聊天 UI 渲染居中撤回提示。

接收方处理 `recall_notify` 并成功持久化本地撤回状态后，发送
`recall_notify_ack`：

```text
message_id       = "m1"
conversation_id  = "single:u1:u2"
receiver_id      = "u2"
recalled_at      = 1717000000000
```

`recall_notify_ack` 的语义与 `delivery_ack` 平行但不混用：

- `delivery_ack`：原消息内容已经写入接收端本地库。
- `recall_notify_ack`：撤回状态已经写入接收端本地库。

Mock server 会按 `(message_id, receiver_id)` 追踪 `recall_notified`，未
ACK 的撤回通知会在接收方 auth/reconnect 后重放。

## 群文本和群图片消息

B10 群文本和群图片消息复用既有消息 payload：

```text
send_message
message_ack
receive_message
delivery_ack
```

首个群消息 slice 不需要新的 payload 类型。`ChatMessagePayload` 通过字段携带群会话信息：

```text
conversation_id       = "group:g_1001"
conversation_type     = CONVERSATION_TYPE_GROUP
group_id              = "g_1001"
sender_id             = "u1"
receiver_id           = "g_1001"
message_type          = MESSAGE_TYPE_TEXT
content               = "hello"
mentioned_user_ids    = ["u2"]
client_time           = 1717000000000
```

Server ACK（`MessageAck`）保持和单聊相同的形态：

```text
message_id       = "m1"
conversation_id  = "group:g_1001"
server_seq       = 1008
server_time      = 1717000000100
```

转发给群成员的 `receive_message` 保留 group `conversation_id`，但 `receiver_id` 使用具体接收人 user id，这样 `delivery_ack` 仍然可以按具体 receiver 清除：

```text
message_id          = "m1"
conversation_id     = "group:g_1001"
conversation_type   = CONVERSATION_TYPE_GROUP
group_id            = "g_1001"
sender_id           = "u1"
receiver_id         = "u2"
server_seq          = 1008
message_type        = MESSAGE_TYPE_TEXT
content             = "hello"
client_time         = 1717000000000
```

群图片消息复用单聊图片的 `image`（`ImagePayload`），同时保留 group 字段：

```text
conversation_id     = "group:g_1001"
conversation_type   = CONVERSATION_TYPE_GROUP
group_id            = "g_1001"
message_type        = MESSAGE_TYPE_IMAGE
content             = "[image]"
image = {
  image_url      = "https://example.com/origin.jpg"
  thumbnail_url  = "https://example.com/thumb.jpg"
  width          = 1280
  height         = 960
  mime_type      = "image/jpeg"
  size_bytes     = 123456
}
```

Android 按 payload 中的 `conversation_id` 存储群消息。单聊为了兼容本地既有数据，仍然会根据 sender/receiver 本地 canonicalize 成 `single:<a>:<b>`。

mock-server 群聊 slice 将 group metadata 持久化到：

```text
data/mock-im-groups.sqlite
```

accepted group message delivery 按具体接收人持久化在 `accepted_messages` 中，主键使用 `(message_id, receiver_id)`。因此群消息的 `delivery_ack` 只清除对应 receiver 的未投递状态，离线重放也能跨 server 重启保留。

群创建当前使用已鉴权 HTTP endpoint，而不是 WebSocket command：

```text
POST /groups
GET /groups
GET /groups/{groupId}
PATCH /groups/{groupId}
GET /groups/{groupId}/members
```

## 当前 mock-server 日志

成功 WebSocket 鉴权示例：

```text
[IM] WS_OPEN ...
[IM] AUTH_OK ...
```

发送消息示例：

```text
[IM] SEND_MESSAGE ...
[IM] MESSAGE_ACK ...
[IM] RECEIVE_MESSAGE ...
```

这些日志只是诊断信息。真实网络协议是上面描述的 Protobuf `ImEnvelope` 消息流。
