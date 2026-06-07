# 消息首页会话摘要与未读设计说明

## 当前实现状态（2026-05-28）

当前项目中的 `Messages` 首页会话摘要、会话未读数，以及底部 `Messages` 图标总未读角标都已经实现。

其中底部 `Messages` 图标角标的规则是：

- 所有会话未读数总和为 `0` 时不显示角标
- 总未读数为 `1..99` 时显示真实数字
- 总未读数大于 `99` 时显示 `99+`

对应实现和验证已经完成：

- 数据源：`ConversationDao.totalUnreadCount()`
- 刷新触发：`MessageRepository.conversationUpdates`
- 角标策略：`MessagesTabUnreadBadgePolicy`
- 状态控制：`MessagesTabUnreadBadgeController`
- 已覆盖的测试：
  - `MessagesTabUnreadBadgePolicyTest`
  - `MessagesTabUnreadBadgeControllerTest`
  - `ConversationDaoContractTest`
  - `MessageRepositoryTest`

本文说明当前项目中 `Messages` 首页的数据设计，重点解释：

- 为什么首页不直接从完整消息表聚合
- `ConversationDao` 和 `MessageDao` 的分工
- 最后一条消息摘要、会话未读数是如何维护的
- 底部 `Messages` 图标未读角标是如何联动更新的

这份说明基于当前项目实际代码，而不是抽象方案。

## 1. 这块功能在当前项目里对应什么

可以把它理解成微信首页“消息列表”那一页。

在当前项目里，`Messages` 页展示的是“会话摘要列表”，而不是完整聊天记录。对应代码主要有：

- 会话摘要模型：`app/src/main/java/com/buyansong/im/storage/StorageModels.kt`
- 会话摘要存储：`app/src/main/java/com/buyansong/im/storage/ConversationDao.kt`
- SQLite 会话摘要实现：`app/src/main/java/com/buyansong/im/storage/AndroidConversationDao.kt`
- 消息收发后更新摘要：`app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- `Messages` 页列表映射：`app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`
- 底部 `Messages` 未读角标：`app/src/main/java/com/buyansong/im/MessagesTabUnreadBadge.kt`

当前项目是单 `Activity` + Compose 导航架构，`Messages`、`Contacts`、`Me` 都在同一个 `MainActivity` 下切换，因此消息首页列表和底部角标都需要用全局可复用的数据源，而不是依赖某一个页面单独计算。

## 2. 为什么首页不直接从 Message 表查

完整消息保存在 `ChatMessage` 中，一条消息一行，对应聊天详情页的数据粒度：

```kotlin
data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val clientSeq: Long,
    val serverSeq: Long?,
    val content: String,
    val status: MessageStatus,
    val direction: MessageDirection,
    val createdAt: Long,
    val updatedAt: Long
)
```

但 `Messages` 首页只关心每个会话的一行摘要：

- 对方是谁
- 最后一条消息是什么
- 最后一条消息时间是多少
- 当前有几条未读

理论上当然可以每次打开首页都从完整消息表按 `conversation_id` 分组，再去算：

- 每个会话最新一条消息
- 每个会话的最后时间
- 每个会话的未读数
- 最后按时间倒序排序

但是这样会让首页每次都做聚合计算。消息少时问题不大，消息多时就会越来越重。

所以当前项目采用的是更常见的做法：

```text
Message 表保存真实聊天记录
Conversation 表保存首页快速展示用的会话摘要
```

也可以理解成：`Conversation` 是 `Messages` 首页的摘要缓存。

## 3. 当前项目里的 Conversation 模型长什么样

当前项目的会话摘要模型定义在 `StorageModels.kt`：

```kotlin
data class Conversation(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val lastMessageId: String?,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val updatedAt: Long
)
```

SQLite 表结构定义在 `ImDatabaseHelper.kt`：

```sql
CREATE TABLE conversations (
  conversation_id TEXT PRIMARY KEY,
  peer_id TEXT NOT NULL,
  peer_name TEXT NOT NULL,
  last_message_id TEXT,
  last_message_preview TEXT NOT NULL,
  last_message_time INTEGER NOT NULL,
  unread_count INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
)
```

这张表的核心特点是：

```text
一个会话只对应一行
```

不是一条消息一行。

完整聊天记录仍然在 `MessageDao` 对应的消息表里；`ConversationDao` 管的是首页摘要。

## 4. ConversationDao 在当前项目里是干什么的

`ConversationDao` 目前只有 4 个核心职责：

```kotlin
interface ConversationDao {
    fun upsertFromMessage(message: ChatMessage, incrementUnread: Boolean)
    fun listConversations(limit: Int): List<Conversation>
    fun clearUnread(conversationId: String)
    fun totalUnreadCount(): Int
}
```

可以直接这样记：

```text
MessageDao：管聊天详情页
ConversationDao：管消息首页列表
```

再展开一点：

| 组件 | 作用 | 数据粒度 |
|---|---|---|
| `MessageDao` | 保存和查询完整聊天消息 | 一条消息一行 |
| `ConversationDao` | 保存和查询会话摘要 | 一个会话一行 |

其中：

- `listConversations(...)`：给 `Messages` 页读列表
- `clearUnread(...)`：进入聊天页后把该会话的首页未读清零
- `totalUnreadCount()`：给底部 `Messages` 图标角标汇总所有会话未读数

## 5. 收到一条新消息时，当前项目做了什么

消息处理主入口在 `MessageRepository.handleIncoming(...)`。

当前项目的顺序是：

1. 先把完整消息写入消息表
2. 再调用 `conversationDao.upsertFromMessage(...)` 更新会话摘要
3. 最后发出 `conversationUpdates` 事件，让首页和角标刷新

对应关键逻辑：

```kotlin
val inserted = messageDao.insertOrIgnore(message)
if (inserted) {
    conversationDao.upsertFromMessage(
        message = message,
        incrementUnread = message.conversationId != activeConversationId
    )
    notifyConversationChanged()
}
```

这里有一个项目内很重要的语义：

```text
如果当前用户正在这个聊天页里，就不增加未读；
如果当前不在这个聊天页里，就把 unread_count + 1
```

也就是说，当前项目不是简单地“收到消息就必定未读 +1”，而是会结合 `activeConversationId` 判断你是不是正在看这个会话。

## 6. upsertFromMessage(...) 在当前项目里具体做什么

`upsert` 就是 `update + insert`。

意思是：

```text
如果这个会话已经存在，就更新它；
如果这个会话还不存在，就插入一行新摘要。
```

当前 SQLite 实现位于 `AndroidConversationDao.kt`，核心逻辑是：

1. 先按 `conversationId` 找当前摘要
2. 根据消息方向确定 `peerId`
3. 计算新的 `unreadCount`
4. 如果这条消息比当前摘要更新，就替换最后一条消息摘要和时间
5. 用 `CONFLICT_REPLACE` 写回 `conversations`

这里的行为和我们前面举的微信例子是一致的：

- 收到别人消息：
  - `last_message_preview` 更新为最新内容
  - `last_message_time` 更新为最新时间
  - 如果当前不在这个聊天页，`unread_count + 1`
- 自己发送消息：
  - 也会更新 `last_message_preview`
  - 也会更新 `last_message_time`
  - 但 `unread_count` 不增加

## 7. 为什么自己发消息也要更新 Conversation 摘要

这是因为 `Messages` 首页展示的是“最后一条消息”，不管这条消息是谁发的。

比如原来首页显示：

```text
张三    在吗？    10:01
```

你回复了一条：

```text
我：在
```

首页就应该马上变成：

```text
张三    在    10:02
```

所以 `sendText(...)` 里也会更新摘要：

```kotlin
messageDao.insertOrIgnore(message)
conversationDao.upsertFromMessage(message, incrementUnread = false)
```

这里明确写死了：

```text
发送消息会更新最后一条消息摘要，但不会增加未读数
```

## 8. listConversations(...) 在当前项目里是干什么的

`listConversations(...)` 就是 `Messages` 首页读取会话列表的入口。

当前 SQLite 实现是：

```sql
SELECT * FROM conversations
ORDER BY last_message_time DESC, conversation_id ASC
LIMIT ?
```

这意味着首页列表的排序规则就是：

```text
最近有消息的会话排在最前面
```

而 `ConversationListViewModel` 会把 `Conversation` 映射成页面可用的 `ConversationListItem`：

```kotlin
data class ConversationListItem(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val peerAvatarUrl: String?,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int
)
```

所以 `Messages` 页最终展示的是：

- 对方昵称 / 头像
- 最后一条消息摘要
- 最后消息时间
- 会话未读数

## 9. clearUnread(...) 在当前项目里是干什么的

当用户点击进入某个聊天页时，首页上的该会话未读数应该清零。

当前项目在 `MessageRepository.openConversation(...)` 中做这件事：

```kotlin
fun openConversation(currentUserId: String, peerId: String): String {
    val conversationId = conversationIdFor(currentUserId, peerId)
    activeConversationId = conversationId
    conversationDao.clearUnread(conversationId)
    notifyConversationChanged()
    return conversationId
}
```

这里有两个效果：

1. 把这个会话设成当前活跃会话
2. 把首页该会话的 `unread_count` 清零

当前项目里，`clearUnread(...)` 只负责清首页摘要表上的未读数。

也就是说，项目当前维护的是：

```text
会话级未读数
```

而不是每条消息一个 `read=true/false` 字段的完整已读系统。这个做法对当前单聊、本地联调范围是够用的。

## 10. 当前项目里 conversation_id 是怎么生成的

当前项目不是把“我和张三”“张三和我”当成两个不同会话，而是统一归一化成一个 `conversationId`。

逻辑在 `MessageRepository.conversationIdFor(...)`：

```kotlin
val participants = listOf(firstUserId, secondUserId).sorted()
return "single:${participants[0]}:${participants[1]}"
```

这意味着：

```text
u1 和 u2 的单聊，始终对应同一个 conversationId
```

这样首页摘要、历史消息、未读数都能稳定落到同一个会话上。

## 11. 底部 Messages 图标未读角标是怎么做的

最近新增的底部 `Messages` 图标红点/数字角标，不是直接从 `ConversationListViewModel.items` 求和，而是走了一个全局链路。

原因很简单：

```text
底部栏是全局的；
Messages / Contacts / Me 都能看到它；
所以它不能依赖 Messages 页面当前是否处于可见状态。
```

当前实现分成两层：

### 11.1 角标显示策略

`MessagesTabUnreadBadgePolicy` 负责把总未读数转成角标文案：

```text
0 -> 不显示
1..99 -> 显示真实数字
>=100 -> 显示 99+
```

### 11.2 角标状态控制器

`MessagesTabUnreadBadgeController` 负责监听会话变化，并重新读取总未读数：

```kotlin
repository.conversationUpdates
    .onSubscription { emit(Unit) }
    .collect { refreshUnreadCount() }
```

这里的关键点是：

- `conversationUpdates` 不是“只表示未读变化”
- 它表示“会话摘要相关数据变了，需要重读一次”

比如这些场景都会触发：

- 收到新消息，最后一条消息摘要变化
- 收到新消息，未读数变化
- 进入聊天页，未读被清零
- 自己发送消息，最后一条消息摘要变化

然后控制器会通过：

```kotlin
repository.totalUnreadCount()
```

去汇总所有会话的未读总数，再把结果交给底部导航的 `Messages` 图标。

## 12. totalUnreadCount() 在当前项目里有什么用

`ConversationDao.totalUnreadCount()` 是当前项目里新增的会话级未读汇总能力。

SQLite 版本直接执行：

```sql
SELECT COALESCE(SUM(unread_count), 0) FROM conversations
```

它的用途不是给 `Messages` 首页列表本身，而是给：

```text
底部 Messages 图标上的全局未读角标
```

这样无论用户现在停留在：

- `Messages`
- `Contacts`
- `Me`

只要来了新的未读消息，底部 `Messages` 图标都能更新。

## 13. 当前项目里完整的数据流是什么

结合现在的实现，可以把流程理解成下面这样：

### 场景 A：收到别人新消息，当前不在这个聊天页

1. `MessagePacketProcessor` 把收到的协议包交给 `MessageRepository`
2. `MessageRepository` 写入完整消息
3. `ConversationDao.upsertFromMessage(..., incrementUnread = true)` 更新会话摘要
4. `ConversationListViewModel` 收到更新后刷新 `Messages` 页列表
5. `MessagesTabUnreadBadgeController` 收到更新后重查 `totalUnreadCount()`
6. 底部 `Messages` 图标显示或更新角标

### 场景 B：进入这个聊天页

1. `MessageRepository.openConversation(...)`
2. `ConversationDao.clearUnread(conversationId)`
3. 发出 `conversationUpdates`
4. `Messages` 页该会话未读数清零
5. 底部 `Messages` 图标总未读同步减少或消失

### 场景 C：自己发送消息

1. `MessageRepository.sendText(...)`
2. 写入完整消息
3. `ConversationDao.upsertFromMessage(..., incrementUnread = false)`
4. 首页会话摘要变成你刚发的内容
5. 未读数不增加

## 14. 和微信首页体验最接近的理解方式

如果用一句最接近日常产品理解的话来说：

```text
Message 表是聊天详情页的数据真相
Conversation 表是微信首页消息列表的数据缓存
```

首页那一行会显示：

- 谁给我发了消息
- 最后说了什么
- 什么时候说的
- 我还有几条没看

而这些信息在当前项目里，正是由 `ConversationDao` / `conversations` 表维护出来的。

底部 `Messages` 图标上的总未读角标，则是在这个会话摘要层之上，再做了一次全局汇总。

## 15. 一句话总结

`ConversationDao` 在当前项目里管的是：

```text
Messages 首页每一行会话卡片的数据，以及底部 Messages 图标的未读汇总基础数据
```

它不保存完整聊天记录，只维护：

- 这个会话是谁
- 最后一条消息摘要是什么
- 最后消息时间是多少
- 当前会话未读数是多少

收到或发送消息后，用 `upsertFromMessage(...)` 更新摘要；
进入聊天页后，用 `clearUnread(...)` 清掉该会话首页未读；
底部 `Messages` 图标再通过 `totalUnreadCount()` 汇总所有会话的未读总数。
