# Android 消息接收至 UI 刷新完整链路

本文梳理一条 WebSocket 消息从服务端到达 Android 客户端，经过连接管理、协议处理、本地持久化、ViewModel 状态更新，最终触发 Compose `LazyColumn` 更新的完整链路。

> 注意：下方代码用于集中表达调用关系。实际实现分散在 `OkHttpImConnection`、`ConnectionLifecycleManager`、`MessagePacketProcessor`、`MessageRepository`、`ChatViewModel` 和 Compose 页面等文件中。

## 一、组件职责

| 组件 | 主要职责 |
|---|---|
| `OkHttpImConnection` | 创建真正的 OkHttp WebSocket，收发二进制帧，完成协议编解码 |
| `ConnectionLifecycleManager` | 包装底层连接，管理 Token、心跳、前后台状态和自动重连 |
| `MessagePacketProcessor` | 作为登录会话级唯一入站业务包消费者，把协议包交给 Repository |
| `MessageRepository` | 处理消息协议、读写 SQLite、维护 ACK/pending/未读状态并发送数据变化通知 |
| `ChatViewModel` | 监听 Repository 通知，重新查询消息并生成 `ChatUiState` |
| Compose UI | 监听 `StateFlow<ChatUiState>`，根据新状态重组并更新消息列表 |

## 二、创建与组装连接对象

```kotlin
// 真正操作 OkHttp WebSocket 的底层连接
val rawConnection = OkHttpImConnection(webSocketUrl)

// 在底层连接外面包装一层生命周期管理
val connection = ConnectionLifecycleManager(
    connection = rawConnection,
    tokenProvider = {
        authRepository.ensureValidSession()?.accessToken
    }
)

// Repository 和 MessagePacketProcessor 拿到的都是包装后的 connection
val messageRepository = MessageRepository(
    messageDao = messageDao,
    conversationDao = conversationDao,
    pendingMessageDao = pendingMessageDao,
    connection = connection,
    // ...
)

val messagePacketProcessor = MessagePacketProcessor(
    repository = messageRepository,
    connection = connection
)
```

上层依赖的是 `ConnectionLifecycleManager` 暴露的 `ImConnection` 接口，而不是直接操作 `OkHttpImConnection`。

## 三、建立 WebSocket 连接

### 1. ConnectionLifecycleManager 发起连接

```kotlin
connection.connect(session.accessToken)
```

调用 `ConnectionLifecycleManager.connect(token)`：

```kotlin
override fun connect(token: String) {
    requestedToken = token

    // 监听底层连接状态和心跳 ACK
    collectConnectionState()
    collectHeartbeatAcks()

    // 获取最新有效 Token，并建立底层连接
    attemptConnect(token)
}
```

获得有效 Token 后，最终调用：

```kotlin
rawConnection.connect(resolvedToken)
```

### 2. OkHttpImConnection 创建实际连接

```kotlin
override fun connect(token: String) {
    mutableStates.value = ConnectionState.Connecting

    val request = Request.Builder()
        .url(webSocketUrl)
        .build()

    webSocket = client.newWebSocket(
        request,
        Listener(token)
    )
}
```

WebSocket 打开后发送鉴权包：

```kotlin
override fun onOpen(
    webSocket: WebSocket,
    response: Response
) {
    mutableStates.value = ConnectionState.Connected
    send(AuthPacketFactory.create(token))
}
```

服务端返回 `AUTH_ACK` 后，连接状态进入 `ConnectionState.Authenticated`。

## 四、接收并发布协议包

### 3. OkHttpImConnection 收到二进制消息

```kotlin
override fun onMessage(
    webSocket: WebSocket,
    bytes: ByteString
) {
    // 二进制数据解码成项目协议包
    val packet = ImPacketCodec.decode(
        bytes.toByteArray()
    )

    // AUTH_ACK 等连接控制包可能改变连接状态
    ConnectionStateReducer
        .stateAfterIncomingPacket(packet)
        ?.let { newState ->
            mutableStates.value = newState
        }

    // 将协议包发布到 SharedFlow
    mutableIncomingPackets.tryEmit(packet)
}
```

此时数据完成第一次转换：

```text
WebSocket 二进制帧
        ↓
ImPacket
        ↓
incomingPackets: SharedFlow<ImPacket>
```

### 4. ConnectionLifecycleManager 暴露底层消息流

```kotlin
override val incomingPackets: SharedFlow<ImPacket> =
    rawConnection.incomingPackets
```

`ConnectionLifecycleManager` 不复制或转换业务包，而是将底层连接的 `incomingPackets` 原样暴露给上层。

它自己也会监听心跳 ACK：

```kotlin
rawConnection.incomingPackets.collect { packet ->
    if (packet.cmd == ImCommand.HEARTBEAT_ACK.value) {
        heartbeatAckGeneration += 1
    }
}
```

因此，同一个 `SharedFlow` 有不同职责的订阅者：

```text
incomingPackets
      ├── ConnectionLifecycleManager
      │       只关心 HEARTBEAT_ACK
      │
      └── MessagePacketProcessor
              把协议包交给 Repository
```

## 五、统一处理入站业务包

### 5. MessagePacketProcessor 消费入站包

登录会话建立后启动：

```kotlin
messagePacketProcessor.start()
```

内部持续监听连接的入站包：

```kotlin
fun start() {
    job = scope.launch(dispatcher) {
        connection.incomingPackets.collect { packet ->
            repository.handlePacket(packet)
        }
    }
}
```

`ChatViewModel` 不直接监听 `incomingPackets`。所有入站业务包统一由登录会话级的 `MessagePacketProcessor` 消费，避免多个页面消费者重复处理消息、重复落库或重复发送 ACK。

## 六、Repository 处理协议并写入 SQLite

### 6. 根据命令分发协议包

```kotlin
fun handlePacket(packet: ImPacket) {
    when (packet.cmd) {
        ImCommand.MESSAGE_ACK.value -> {
            handleAck(packet.body.decodeToString())
        }

        ImCommand.RECEIVE_MESSAGE.value -> {
            handleIncoming(packet.body.decodeToString())
        }

        ImCommand.READ_ACK.value -> {
            handleReadAck(packet.body.decodeToString())
        }

        ImCommand.RECALL_ACK.value -> {
            handleRecallAck(packet.body.decodeToString())
        }

        ImCommand.RECALL_NOTIFY.value -> {
            handleRecallNotify(packet.body.decodeToString())
        }
    }
}
```

收到普通聊天消息时，进入 `handleIncoming(json)`。

### 7. 解析消息、写库并发送 ACK

```kotlin
private fun handleIncoming(json: String) {
    val body = JsonParser
        .parseString(json)
        .asJsonObject

    val message = ChatMessage(
        messageId = body.requiredString("messageId"),
        senderId = body.requiredString("senderId"),
        receiverId = body.requiredString("receiverId"),
        content = body.requiredString("content"),
        status = MessageStatus.RECEIVED,
        direction = MessageDirection.INCOMING,
        // ...
    )

    // 通过 messageId 去重并写入 messages 表
    val inserted = messageDao.insertOrIgnore(message)

    if (inserted) {
        // 更新 conversations 表和未读数
        conversationDao.upsertFromMessage(
            message = message,
            incrementUnread =
                message.conversationId != activeConversationId
        )

        // 通知监听者：本地消息数据已经变化
        notifyConversationChanged(
            message.conversationId
        )
    }

    // 告诉服务端：接收方已经处理并持久化消息
    connection.send(
        createDeliveryAck(message)
    )
}
```

SQLite 是消息的事实来源，主要涉及：

```text
messages
conversations
pending_messages
```

## 七、Repository 发出数据变化通知

### 8. notifyConversationChanged

```kotlin
private fun notifyConversationChanged(
    conversationId: String?
) {
    // 清除对应会话的内存缓存
    if (conversationId != null) {
        initialPageCache.remove(conversationId)
    }

    // 发出的只是“数据变了”的信号
    mutableConversationUpdates.tryEmit(Unit)
}
```

内部使用可写的 `MutableSharedFlow`，对外只暴露只读 `SharedFlow`：

```kotlin
private val mutableConversationUpdates =
    MutableSharedFlow<Unit>(
        extraBufferCapacity = 64
    )

val conversationUpdates: SharedFlow<Unit> =
    mutableConversationUpdates.asSharedFlow()
```

`Unit` 不携带具体消息，只表达：

> Repository 中与会话有关的数据发生了变化，请监听者重新查询。

## 八、ChatViewModel 查询数据并生成 UI 状态

### 9. 监听 Repository 通知

```kotlin
repository.conversationUpdates.collect {
    refreshKeepingHistory()
    sendGroupReadAckIfNeeded()
    recomputeGroupReadIndicator()
    refreshProfiles()
}
```

`conversationUpdates` 和 `ChatViewModel.state` 是两条独立的 Flow。上面的 `collect` 代码块是连接它们的桥梁。

### 10. 重新查询 Repository

```kotlin
private fun refreshKeepingHistory() {
    val currentMessages =
        mutableState.value.messages

    // Repository 最终通过 DAO 查询 SQLite
    val latestMessages =
        repository.historyPageByConversationId(
            conversationId = conversationId,
            beforeTime = null,
            limit = limit
        )

    val mergedMessages = mergeMessages(
        currentMessages,
        latestMessages
    )

    // 产生新的 ChatUiState
    mutableState.value =
        mutableState.value.copy(
            messages = mergedMessages,
            errorMessage = null
        )
}
```

Repository 查询数据库：

```kotlin
fun historyPageByConversationId(
    conversationId: String,
    beforeTime: Long?,
    limit: Int
): List<ChatMessage> {
    return messageDao.queryPage(
        conversationId,
        beforeTime,
        limit
    )
}
```

### 11. 对外暴露 UI StateFlow

```kotlin
private val mutableState =
    MutableStateFlow(ChatUiState())

val state: StateFlow<ChatUiState> =
    mutableState.asStateFlow()
```

- `mutableState` 供 `ChatViewModel` 内部修改。
- `state` 供 Compose UI 只读监听。

两条 Flow 的关系如下：

```text
Repository 的通知 Flow
conversationUpdates
        │
        │ ChatViewModel collect
        ▼
refreshKeepingHistory()
        │
        │ 查询 Repository / SQLite
        ▼
得到最新 messages
        │
        │ 更新页面状态
        ▼
mutableState.value = 新 ChatUiState
        │
        ▼
UI 监听的 StateFlow
state
```

## 九、Compose 监听状态并更新 LazyColumn

### 12. 将 StateFlow 转换为 Compose State

```kotlin
val uiState by viewModel.state.collectAsState()
```

也可以使用生命周期感知版本：

```kotlin
val uiState by
    viewModel.state.collectAsStateWithLifecycle()
```

当 ViewModel 执行：

```kotlin
mutableState.value =
    mutableState.value.copy(
        messages = mergedMessages
    )
```

`collectAsState()` 会收到新的 `ChatUiState`，读取该状态的 Composable 随后进入重组。

### 13. LazyColumn 使用最新消息列表

```kotlin
LazyColumn {
    items(
        items = uiState.messages,
        key = { message ->
            message.messageId
        }
    ) { message ->
        MessageItem(message)
    }
}
```

提供稳定的 `messageId` 作为 `key` 后，Compose 可以识别消息项身份，并尽量只更新发生变化的列表内容。

## 十、完整接收链路

```text
WebSocket 服务器
        ↓ 二进制帧
OkHttpImConnection.onMessage()
        ↓ 解码
ImPacket
        ↓ tryEmit
incomingPackets: SharedFlow<ImPacket>
        │
        ├── ConnectionLifecycleManager
        │       处理连接状态、心跳和重连
        │
        ▼
MessagePacketProcessor.collect
        ↓
MessageRepository.handlePacket()
        ↓
MessageRepository.handleIncoming()
        ↓
写入 messages / conversations
        ↓
notifyConversationChanged()
        ↓
conversationUpdates.tryEmit(Unit)
        ↓
ChatViewModel collect
        ↓
重新查询 MessageRepository / SQLite
        ↓
mutableState.value = 新 ChatUiState
        ↓
state: StateFlow<ChatUiState>
        ↓
Compose collectAsState()
        ↓
ChatScreen 重组
        ↓
LazyColumn 更新消息项
```

## 十一、一句话理解每一层

```text
OkHttpImConnection：收到并解码 WebSocket 包
ConnectionLifecycleManager：保证连接健康并处理心跳、重连
MessagePacketProcessor：统一转交入站业务包
MessageRepository：处理消息、写库、发送变化通知
ChatViewModel：收到通知后查库并生成 UI 状态
Compose：监听 UI 状态并重组 LazyColumn
```

