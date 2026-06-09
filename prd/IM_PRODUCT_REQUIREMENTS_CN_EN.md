# ByteIM Product Requirements Document / 自研 IM 产品需求文档

版本 / Version: 2026-06-01  
范围 / Scope: Android IM client and local Java/Netty mock server  
产品参照 / Product reference: WeChat-style messaging, contacts, profile, and group-chat interaction

---

# 中文版

## 1. 产品定位

ByteIM 是一个运行在 Android 上的自研即时通讯客户端，配套本地 Java/Netty mock server 用于账号、鉴权、WebSocket 长连接、消息转发、ACK、离线暂存和本地验证。

产品体验参照微信的基础信息架构：

- `Message`：消息首页，会话列表、最近消息、未读数、发起群聊入口。
- `Contacts`：联系人页，从联系人进入单聊。
- `Me`：个人页，查看和修改头像、昵称、账号 ID，退出登录。
- `Chat`：聊天详情页，承载单聊、群聊、文本、图片、撤回、已读/未读、@ 提醒。

当前产品目标不是完整复刻微信，而是完成一个可演示、可验证、具备可靠性基础的 IM 闭环：注册登录、单聊、群聊、消息持久化、会话未读、连接保活、断线重连、发送重试、送达 ACK、单聊已读回执、消息撤回和基础个人资料能力。

## 2. 信息架构与导航

### 2.1 未登录状态

启动 App 后，如果本地没有可恢复登录态，进入登录页。

登录页元素：

- 标题：`ByteIM`
- 手机号输入框：`Phone number`
- 密码输入框：`Password`
- 登录按钮：`Login`
- 创建账号按钮：`Create account`
- 加载态：请求中展示加载指示
- 错误态：展示本地校验或服务端错误信息

路径：

```text
App 启动 -> Login
Login -> 输入手机号和密码 -> 点击 Login -> 成功后进入 Message
Login -> 点击 Create account -> 注册模式
```

### 2.2 注册状态

注册页仍在 `LoginScreen` 内切换。

注册页元素：

- 手机号输入框
- 密码输入框
- 确认密码输入框：`Confirm password`
- 注册按钮：`Register`
- 返回登录按钮：`Back to login`

路径：

```text
Login -> 点击 Create account -> Register
Register -> 输入大陆手机号、密码、确认密码 -> 点击 Register -> 成功后进入 Message
Register -> 点击 Back to login -> 返回 Login
```

规则：

- 账号 ID 使用大陆手机号。
- 注册默认昵称为手机号。
- 密码和确认密码必须一致。
- 服务端保存随机盐和 PBKDF2-SHA256 密码哈希，不保存明文密码。
- 注册成功后返回 access token、refresh token 和基础用户资料。

### 2.3 已登录主框架

登录成功或本地登录态恢复成功后，进入主框架，默认首页为 `Message`。

底部导航包含三个顶层 Tab：

- `Message`
- `Contacts`
- `Me`

路径：

```text
Message <-> Contacts <-> Me
```

顶层返回规则：

- 在 `Message`、`Contacts`、`Me` 按 Android 系统返回键：将 App 移到后台，类似微信返回桌面，不结束登录态。
- 在二级页面按左上返回或系统返回：回到上一级页面。
- 在 `Chat` 按左上返回或系统返回：返回 `Message`。

## 3. 登录、鉴权与会话恢复

### 3.1 登录

用户输入手机号和密码后点击 `Login`。

成功行为：

1. Android 调用 HTTP 登录接口。
2. 服务端校验手机号、密码哈希。
3. 服务端返回 access token、refresh token、过期时间和用户资料。
4. Android 将 token 写入 `SharedPreferences`。
5. Android 进入 `Message` 首页。
6. WebSocket 使用最新 access token 发送 `AUTH`。
7. 服务端返回 `AUTH_ACK` 后，本地连接状态进入 `Authenticated`。

失败行为：

- 密码错误、账号不存在、响应格式错误或 token 无效时，停留登录页并展示错误。
- 过期、废弃、被撤销或旧格式登录态会被清理。

### 3.2 注册

用户输入大陆手机号、密码、确认密码后点击 `Register`。

成功行为：

1. Android 先做确认密码一致性校验。
2. Android 调用 HTTP 注册接口。
3. 服务端创建账号，默认昵称为手机号。
4. 服务端返回 token 和资料。
5. Android 进入 `Message` 首页。

失败行为：

- 确认密码不一致：不发请求，页面展示本地错误。
- 手机号格式不符合要求、账号已存在或服务端失败：停留注册页并展示错误。

### 3.3 登录态恢复与刷新

App 启动时自动尝试恢复登录态：

- access token 未过期：直接恢复会话。
- access token 过期、refresh token 有效：静默刷新，服务端返回新的 access/refresh token 对。
- refresh token 过期、被撤销或刷新失败：清理本地 token，回到登录页。

约束：

- 每次受保护 HTTP 请求必须在发送前调用统一会话提供者获取最新可用 access token。
- 不允许长期复用页面或 ViewModel 中缓存的旧 access token。
- WebSocket 重连也必须通过同一套有效会话逻辑拿 token。

## 4. Message 首页

### 4.1 页面目标

`Message` 页对应微信的消息首页。它展示的是会话摘要列表，而不是完整聊天记录。

每一行会话展示：

- 头像
- 会话名称：单聊为对方昵称，群聊为群名
- 最后一条消息摘要
- 最后一条消息时间
- 未读数角标
- 群聊 @ 我提醒：当 `mentionUnreadCount > 0` 时，摘要前展示红色 `[有人@我]`

顶部栏展示：

- 标题：无未读时为 `Message`
- 有未读时为 `Message(n)`，`n` 遵循 `1..99` 显示真实数字，`>=100` 显示 `99+`
- 右侧 `+` 操作按钮

底部 `Message` Tab 也展示总未读角标：

- `0`：不展示
- `1..99`：展示真实数字
- `>=100`：展示 `99+`

### 4.2 页面路径

```text
登录成功 -> Message
底部点击 Message -> Message
Message -> 点击某个会话行 -> Chat
Message -> 点击右上角 + -> 展开菜单
Message -> + 菜单点击 发起群聊 -> Group Create
Message -> + 菜单点击 添加朋友 -> 当前仅关闭菜单，后续扩展
```

### 4.3 会话列表排序

会话列表按 `lastMessageTime DESC` 排序，最新有消息的会话排在最上方。

当时间相同，使用 `conversationId ASC` 保持稳定顺序。

### 4.4 空列表

空 `Message` 页不自动创建假会话。联系人入口在 `Contacts` 页，只有当用户从联系人发起会话、创建群聊或真实消息产生后，才会出现会话行。

### 4.5 点击会话

点击单聊会话：

```text
Message -> 点击单聊行 -> chat/single:<较小用户ID>:<较大用户ID>
```

点击群聊会话：

```text
Message -> 点击群聊行 -> chat/group:<groupId>
```

进入会话时：

- 设置当前活跃会话。
- 清零该会话 `unreadCount`。
- 群聊同时清零 `mentionUnreadCount`。
- 单聊会根据本地最大 incoming `serverSeq` 发送 `READ_ACK`。
- 底部 `Message` 总未读角标同步刷新。

## 5. Contacts 联系人页

### 5.1 页面目标

`Contacts` 页用于展示当前可发起会话的联系人。当前版本使用本地 demo 联系人解析器，不做真实好友添加和搜索。

每个联系人展示：

- 头像
- 昵称
- 用户 ID / 手机号

### 5.2 页面路径

```text
底部点击 Contacts -> Contacts
Contacts -> 点击联系人行 -> Chat
```

点击联系人后：

1. 根据当前用户 ID 和联系人 ID 生成单聊 `conversationId`。
2. 路由到 `chat/single:<较小用户ID>:<较大用户ID>`。
3. 若该会话还没有消息，聊天页可以为空，但用户可直接发送第一条消息。

## 6. Chat 聊天页

### 6.1 页面目标

`Chat` 页承载单聊和群聊，共用同一个页面。

顶部栏：

- 左侧返回按钮。
- 中间标题：单聊展示对方昵称，群聊展示群名。
- 群聊右侧展示 `...`，用于修改群名称。

消息区：

- 使用反向列表展示，最新消息在底部视觉位置，并支持新消息自动滚动。
- 本地初始加载最近 20 条消息。
- 用户向上滚动接近历史边界时，再加载更早 20 条。
- 本地会话内最多保留 2,000 条内存历史缓存，避免单页无限增长。

输入区：

- 文本输入框。
- 草稿为空时显示 `Image` 按钮。
- 草稿非空时显示 `Send` 按钮。
- 点击 `Image` 可选择最多 9 张图片。
- 群聊中草稿末尾输入 `@` 时展示群成员选择器。

### 6.2 单聊发起路径

从联系人发起：

```text
Contacts -> 点击联系人 -> Chat(single)
Chat -> 输入文本 -> 点击 Send -> 发送单聊文本
Chat -> 点击 Image -> 选择图片 -> 上传并发送图片
```

从消息列表继续：

```text
Message -> 点击已有单聊会话 -> Chat(single)
```

### 6.3 群聊进入路径

```text
Message -> 点击已有群聊会话 -> Chat(group)
Group Create -> 创建成功 -> Chat(group)
```

群聊内可选操作：

```text
Chat(group) -> 点击右上角 ... -> 修改群名称弹窗
修改群名称弹窗 -> 输入新名称 -> 点击 保存 -> PATCH /groups/{groupId}
修改群名称弹窗 -> 点击 取消 -> 关闭弹窗
```

### 6.4 文本消息发送逻辑

用户输入文本后点击 `Send`：

1. 客户端生成 `messageId`。
2. 客户端按会话生成本地 `clientSeq`。
3. 消息以 `SENDING` 状态写入 `messages`。
4. 更新 `conversations` 会话摘要，不增加未读。
5. 写入 `pending_messages`，用于 ACK 超时重试。
6. 通过 WebSocket 发送 `SEND_MESSAGE`。
7. 服务端接收后分配会话内递增 `serverSeq`。
8. 服务端返回 `MESSAGE_ACK`。
9. 客户端将消息标记为 `SENT`，保存 `serverSeq`，删除 pending 记录。

发送失败或 ACK 超时：

- Outbox worker 在连接进入 `Authenticated` 后重试到期 pending 消息。
- 重试次数耗尽后消息标记为 `FAILED`。
- 图片上传失败独立标记为 `UPLOAD_FAILED`。
- 失败图片可点击失败标记重试。

### 6.5 图片消息发送逻辑

用户点击 `Image` 后选择图片：

1. 客户端压缩和准备本地原图/缩略图。
2. 本地插入 `UPLOADING` 图片消息，内容摘要为 `[图片]`。
3. 请求后端图片上传目标。
4. 上传成功后补齐 `imageUrl`、`thumbnailUrl`、尺寸、MIME、大小等字段。
5. 状态改为 `SENDING`。
6. 写入 pending 并发送 `SEND_MESSAGE`。
7. 后续 ACK、重试、失败逻辑与文本一致。

群聊图片必须以 `conversationId = group:<groupId>` 持久化和发送，不能被建模成发给 `group:<groupId>` 这个假用户的单聊。

### 6.6 消息展示规则

普通消息：

- 自己发送的消息在右侧，浅绿色气泡，右侧展示自己的头像。
- 对方或群成员消息在左侧，浅灰/白色气泡，左侧展示发送者头像。
- 群聊消息的发送者身份取真实 `senderId` 的用户资料，而不是群头像。
- 文本气泡长按展示动作条。
- 图片气泡点击进入预览，长按可展示动作条。

撤回消息：

- 不删除原始消息行。
- UI 展示居中的系统提示。
- 自己撤回：`你撤回了一条消息`
- 对方撤回：`对方撤回了一条消息`
- 撤回提示不展示头像、气泡、复制、已读/未读状态。

长按动作：

- 文本消息：可展示 `复制 | 撤回`。
- 图片消息：可展示 `撤回`。
- 复制只复制文本消息的原始 `content`。
- 撤回只对当前用户自己发送、已成功发送、有 `serverSeq`、未撤回、且在 2 分钟窗口内的消息展示。

### 6.7 已读/未读状态

必须区分三种状态：

- `MESSAGE_ACK`：服务端已接收发送方消息，并分配 `serverSeq`。
- `DELIVERY_ACK`：接收端已把 `RECEIVE_MESSAGE` 持久化到本地，不代表用户已读。
- `READ_ACK`：接收方用户打开单聊页并读到某个 `serverSeq` 游标，才代表已读。

单聊已读 UI：

- 只在自己发送的消息旁展示。
- 未读：灰色对勾。
- 已读：绿色对勾。
- 判断条件：

```text
message.direction == OUTGOING
message.serverSeq != null
message.serverSeq <= conversation.peerReadUpToServerSeq
```

已读触发：

- 进入单聊页时发送一次 `READ_ACK`。
- 停留在单聊页期间收到新消息后，如果本地最大 incoming `serverSeq` 变大，再发送一次。
- 如果本次游标没有超过上次已发送游标，不重复发送。

群聊当前不展示群已读成员列表，也不发送群聊 `READ_ACK`。群聊已读语义延后。

### 6.8 新消息到达处理

消息接收不依赖当前页面是否可见。登录会话级 `MessagePacketProcessor` 统一消费 WebSocket 入站包，并交给 `MessageRepository` 处理。

收到 `RECEIVE_MESSAGE` 后：

1. 解析消息类型、会话类型、发送者、接收者、`serverSeq`、内容、图片字段、@ 字段。
2. 以 `messageId` 幂等写入 `messages`，重复包不重复插入。
3. 如果是新消息，更新 `conversations` 会话摘要。
4. 如果当前会话不是该消息所在会话，`unreadCount + 1`。
5. 如果是群聊且 `mentionedUserIds` 包含当前用户，并且当前不在该群聊，`mentionUnreadCount + 1`。
6. 发送 `conversationUpdates`，刷新 `Message` 列表和底部未读角标。
7. 如果消息带 `serverSeq`，发送 `DELIVERY_ACK`。
8. 如果是当前打开的单聊，尝试发送前进后的 `READ_ACK`。
9. 如果是图片消息，尝试缓存缩略图；缩略图未准备好时暂不进入聊天可见列表。

在不同页面的表现：

- 用户在 `Message`：对应会话行摘要、时间、未读实时刷新。
- 用户在 `Contacts` 或 `Me`：消息仍会落库，底部 `Message` 角标刷新。
- 用户在当前聊天页：消息直接显示，不增加该会话未读。
- 用户在其他聊天页：目标会话未读增加，列表排序按最后消息时间调整。

## 7. 创建群聊

### 7.1 入口

```text
Message -> 点击右上角 + -> 点击 发起群聊 -> Group Create
```

### 7.2 创建页面

页面元素：

- 标题：`发起群聊`
- 右上创建按钮：`创建` / 创建中显示 `创建中`
- 联系人列表
- 每个联系人一行：复选框、头像、昵称、用户 ID
- 错误信息展示区

用户操作：

```text
Group Create -> 勾选一个或多个联系人 -> 点击 创建
Group Create -> 系统返回或页面返回 -> Message
```

### 7.3 创建成功

创建成功流程：

1. ViewModel 获取最新有效 access token。
2. 调用 `POST /groups`，请求体包含群名和成员 ID。
3. 服务端创建稳定 `groupId`，持久化群资料和成员。
4. 客户端持久化 `groups`、`group_members`。
5. 客户端插入 `GROUP` 会话行，`conversationId = group:<groupId>`。
6. 页面直接进入新群聊 `chat/group:<groupId>`。

### 7.4 创建失败

- token 刷新失败：停留创建页，展示错误，不创建本地群。
- 服务端创建失败：停留创建页，展示错误。
- 禁止在服务端创建失败后插入本地-only 群会话。

## 8. Me 页面

### 8.1 页面目标

`Me` 页对应微信“我”页，展示当前用户资料，并提供资料编辑和退出登录。

首页展示：

- 标题：`Me`
- 个人资料入口：头像、昵称、`ID: <手机号>`、右箭头
- `Logout` 按钮
- 错误信息

路径：

```text
底部点击 Me -> Me
Me -> 点击个人资料入口 -> Profile Detail
Me -> 点击 Logout -> 清理本地会话并返回 Login
```

### 8.2 个人资料详情

详情页展示：

- 返回按钮
- 标题：Profile
- `Avatar` 行：头像、右箭头
- `Name` 行：昵称、右箭头
- `ID` 行：手机号，只读

路径：

```text
Me -> 点击个人资料入口 -> Profile Detail
Profile Detail -> 点击 Avatar -> 系统图片选择器 -> 选择图片 -> 上传并保存头像
Profile Detail -> 点击 Name -> Name Editor
Profile Detail -> 返回 -> Me
```

### 8.3 修改昵称

昵称编辑页展示：

- 返回按钮
- 标题：Name
- 右上保存按钮：`Save`，保存中显示 `Saving`
- 下划线样式文本输入框

路径：

```text
Profile Detail -> 点击 Name -> Name Editor
Name Editor -> 修改昵称 -> 点击 Save -> 保存成功后返回 Profile Detail
Name Editor -> 返回 -> 取消编辑并返回 Profile Detail
```

### 8.4 修改头像

头像修改流程：

1. 用户点击 `Avatar` 行。
2. 打开系统图片选择器。
3. 选择图片后，客户端压缩为 1 MB 以内 JPEG。
4. 客户端请求后端 OSS 上传目标。
5. 客户端将图片字节 PUT 到签名 URL。
6. 客户端通过 `PUT /users/me` 保存新的 `avatarUrl` 和对象 key。
7. 本地 `user_profiles` 更新。
8. `Me`、`Message` 会话头像、`Chat` 头像通过资料缓存刷新。

### 8.5 退出登录

用户点击 `Logout`：

1. 关闭当前活跃会话。
2. 断开 WebSocket。
3. 调用登出逻辑，清理本地 token。
4. 服务端撤销 refresh token。
5. 返回登录页。

## 9. 本地持久化机制

### 9.1 总体原则

Android 端使用 SQLiteOpenHelper 直接管理 SQLite，不使用 Room。

本地数据库名：

```text
self_hosted_im.db
```

核心表：

- `messages`：完整消息记录。
- `conversations`：消息首页会话摘要缓存。
- `pending_messages`：待 ACK 或待重试的发送包。
- `user_profiles`：用户资料缓存。
- `groups`：群资料。
- `group_members`：群成员。

Token 不存 SQLite，存 `SharedPreferences`。

### 9.2 用户资料持久化

表：`user_profiles`

字段：

- `user_id`
- `phone`
- `nickname`
- `avatar_url`
- `avatar_updated_at`
- `updated_at`

用途：

- `Me` 展示当前用户。
- `Message` 展示单聊对方昵称和头像。
- `Chat` 展示顶部昵称、头像、群聊发送者资料。
- 头像字节额外使用内存和磁盘缓存，磁盘缓存位于 Android `cacheDir/avatar-images`。

刷新来源：

- 登录/注册响应。
- `GET /users/me`
- `GET /users/{userId}`
- `POST /users/batch`
- `PUT /users/me`

### 9.3 单聊记录持久化

单聊 `conversationId` 规则：

```text
single:<较小用户ID>:<较大用户ID>
```

这样 A 和 B 双方本地都能稳定归一到同一个会话 ID。

`messages` 保存单聊完整记录：

- `message_id` 唯一去重。
- `conversation_id`
- `sender_id`
- `receiver_id`
- `client_seq`
- `server_seq`
- `content`
- `message_type`
- 图片字段
- 撤回字段
- `status`
- `direction`
- `created_at`
- `updated_at`

`conversations` 保存首页摘要：

- 单聊对方 ID 和昵称。
- 最后一条消息 ID、摘要、时间。
- 会话未读数。
- 对方已读游标 `peer_read_up_to_server_seq` 和 `peer_read_at`。

### 9.4 群聊持久化

群聊 `conversationId` 规则：

```text
group:<groupId>
```

群资料表 `groups`：

- `group_id`
- `name`
- `avatar_url`
- `owner_id`
- `created_at`
- `updated_at`

群成员表 `group_members`：

- `group_id`
- `user_id`
- `display_name`
- `avatar_url`
- `role`
- `joined_at`
- `updated_at`

群消息仍保存在 `messages`：

- `conversation_type = GROUP`
- `group_id = <groupId>`
- `conversation_id = group:<groupId>`
- `receiver_id` 发送时为 `groupId`，服务端转发给成员时为具体接收者 ID。
- `mentions_json` 保存被 @ 的用户 ID 数组。

群会话摘要保存在 `conversations`：

- `conversation_type = GROUP`
- `title = 群名`
- `mention_unread_count` 保存 @ 我未读。
- `unread_count` 保存普通会话未读总数。

### 9.5 发送重试持久化

表：`pending_messages`

字段：

- `message_id`
- `packet_cmd`
- `packet_body`
- `retry_count`
- `next_retry_at`
- `created_at`

规则：

- 发送消息时先写本地消息和 pending，再发 WebSocket。
- 收到 `MESSAGE_ACK` 后删除 pending。
- 连接恢复到 `Authenticated` 后，Outbox worker 查找已到期 pending 并重试。
- 重试耗尽后，消息标记为 `FAILED` 并删除 pending。

## 10. 服务端持久化与可靠性

mock server 负责本地联调所需的可靠性基础。

服务端数据库：

- 用户与 refresh token：`mock-server/data/mock-im-users.sqlite`
- 已接收消息与离线投递状态：`mock-server/data/mock-im-messages.sqlite`
- 会话序列号：`mock-server/data/mock-im-sequences.sqlite`
- 群资料和成员：`mock-server/data/mock-im-groups.sqlite`

服务端消息可靠性：

- `MESSAGE_ACK` 表示服务端已接收并分配 `serverSeq`。
- 单聊离线消息暂存到接收者未送达队列。
- 群聊 fanout 对每个具体接收者记录投递状态。
- `DELIVERY_ACK` 清除该接收者对应的未送达状态。
- 服务端重启后恢复 accepted message 和 undelivered 状态，避免重复分配 `serverSeq`，并能继续离线重放。
- 重复 `messageId` 发送必须幂等，返回原 ACK，不重复分配 `serverSeq`。

## 11. 消息排序规则

排序使用两个序列：

- `clientSeq`：发送端本地会话内序号，用于发送顺序和 ACK 关联，不作为最终权威顺序。
- `serverSeq`：服务端按 `conversationId` 分配的会话内权威顺序。

展示规则：

- 已确认 outgoing 和 incoming 消息，有 `serverSeq` 时按 `serverSeq` 排序。
- 本地 `SENDING` 且无 `serverSeq` 的消息，用 `createdAt`、`clientSeq`、`messageId` 保持临时可见。
- 收到乱序 `RECEIVE_MESSAGE` 时，先持久化，再重新查询/合并，最终按排序策略展示。
- 当前不实现缺口等待、gap buffer 和远端历史补拉。

## 12. 会话摘要与未读规则

`Message` 首页不从 `messages` 每次聚合，而是读取 `conversations` 摘要表。

收到新消息：

- 写入 `messages`。
- 更新或插入 `conversations`。
- 最新消息比当前摘要更新时，替换 preview 和时间。
- 当前不在该会话：未读 +1。
- 当前正在该会话：不增加未读。

自己发送消息：

- 写入 `messages`。
- 更新 `conversations` preview 和时间。
- 不增加未读。

进入会话：

- 清零 `unread_count`。
- 群聊同时清零 `mention_unread_count`。
- 刷新 Message 列表和底部角标。

底部 Message 总未读：

```sql
SELECT COALESCE(SUM(unread_count), 0) FROM conversations
```

## 13. 非目标与延后范围

当前 PRD 不要求：

- 完整好友体系、好友申请、好友搜索。
- 生产级推送通知。
- 群聊已读成员列表。
- 群主转让、管理员、禁言、退群、踢人。
- 远端历史分页拉取。
- 多端同步完整一致性。
- 生产级数据库迁移策略。
- 生产安全级 OSS 权限和私有访问体系。

---

# English Version

## 1. Product Positioning

ByteIM is a custom Android instant messaging client with a local Java/Netty mock server for account management, authentication, WebSocket sessions, message routing, ACKs, offline storage, and local verification.

The product follows a WeChat-style information architecture:

- `Message`: conversation list, recent messages, unread badges, group creation entry.
- `Contacts`: contact list and single-chat entry.
- `Me`: current user profile, avatar/name editing, logout.
- `Chat`: single chat and group chat detail page, including text, image, recall, read receipt, unread status, and @ mention behavior.

The goal is not to fully clone WeChat. The goal is to provide a demonstrable and testable IM loop with login/register, single chat, group chat, local persistence, unread counts, heartbeat, reconnect, retry, delivery ACK, single-chat read receipts, message recall, and basic profile support.

## 2. Information Architecture And Navigation

### 2.1 Logged-Out State

When the app starts without a restorable session, it opens the login page.

Login page elements:

- Title: `ByteIM`
- `Phone number` input
- `Password` input
- `Login` button
- `Create account` button
- Loading indicator
- Error message area

Routes:

```text
App launch -> Login
Login -> enter phone and password -> tap Login -> Message
Login -> tap Create account -> Register mode
```

### 2.2 Register State

Registration is a mode inside `LoginScreen`.

Register page elements:

- Phone number input
- Password input
- Confirm password input
- `Register` button
- `Back to login` button

Routes:

```text
Login -> tap Create account -> Register
Register -> enter mainland China phone, password, confirm password -> tap Register -> Message
Register -> tap Back to login -> Login
```

Rules:

- The account ID is the mainland China phone number.
- The default nickname is the phone number.
- Password and confirm password must match.
- The server stores random salt plus PBKDF2-SHA256 password hash, never plaintext passwords.
- Register success returns access token, refresh token, and profile fields.

### 2.3 Authenticated Main Frame

After login or session restore, the app opens the main frame. The default destination is `Message`.

Bottom tabs:

- `Message`
- `Contacts`
- `Me`

Routes:

```text
Message <-> Contacts <-> Me
```

Back behavior:

- Android Back on `Message`, `Contacts`, or `Me`: move the task to background, like WeChat returning to launcher.
- Android Back or top-left Back on secondary pages: return to the previous page.
- Android Back or top-left Back on `Chat`: return to `Message`.

## 3. Login, Auth, And Session Restore

### 3.1 Login

After the user enters phone and password and taps `Login`:

1. Android calls the HTTP login API.
2. The server validates phone and password hash.
3. The server returns access token, refresh token, expiry times, and profile data.
4. Android stores token state in `SharedPreferences`.
5. Android navigates to `Message`.
6. WebSocket sends `AUTH` with the latest access token.
7. After `AUTH_ACK`, local connection state becomes `Authenticated`.

Failure behavior:

- Wrong password, missing account, invalid response, or invalid token keeps the user on Login and shows an error.
- Expired, revoked, stale, or legacy sessions are cleared.

### 3.2 Register

After the user enters a mainland China phone number, password, and confirmation:

1. Android validates that password and confirmation match.
2. Android calls the register API.
3. The server creates the account and default nickname.
4. The server returns token and profile data.
5. Android navigates to `Message`.

Failure behavior:

- Password mismatch: no request is sent; a local error is shown.
- Invalid phone, duplicate account, or server failure: stay on Register and show an error.

### 3.3 Session Restore And Refresh

On app launch:

- Valid access token: restore session directly.
- Expired access token plus valid refresh token: silently refresh and persist the new access/refresh token pair.
- Expired, revoked, or failed refresh token: clear local token state and return to Login.

Constraints:

- Every authenticated HTTP request must resolve a fresh valid access token immediately before sending.
- Page-level or ViewModel-level access token snapshots must not be reused for later requests.
- WebSocket reconnect must use the same valid-session provider.

## 4. Message Home

### 4.1 Page Goal

`Message` is the WeChat-style conversation home. It displays conversation summaries, not full chat records.

Each row displays:

- Avatar
- Conversation name: peer nickname for single chat, group name for group chat
- Last-message preview
- Last-message time
- Unread badge
- Group @ reminder: if `mentionUnreadCount > 0`, show red `[有人@我]` before the preview

Top bar:

- Title is `Message` when there is no unread count.
- Title becomes `Message(n)` when unread exists.
- `1..99` shows the real number; `>=100` shows `99+`.
- Right-side `+` action button.

Bottom `Message` tab unread badge:

- `0`: hidden
- `1..99`: real number
- `>=100`: `99+`

### 4.2 Page Routes

```text
Login success -> Message
Bottom tab Message -> Message
Message -> tap conversation row -> Chat
Message -> tap top-right + -> open menu
Message -> + menu tap 发起群聊 -> Group Create
Message -> + menu tap 添加朋友 -> currently only closes the menu
```

### 4.3 Conversation Sorting

Conversations are sorted by `lastMessageTime DESC`; the most recently active conversation is on top.

If timestamps are equal, `conversationId ASC` keeps a stable order.

### 4.4 Empty List

An empty `Message` page does not create fake rows. Contacts live under `Contacts`. A row appears only after the user opens a contact, creates a group, or real messages exist.

### 4.5 Opening A Conversation

Single-chat route:

```text
Message -> tap single row -> chat/single:<lowerUserId>:<higherUserId>
```

Group-chat route:

```text
Message -> tap group row -> chat/group:<groupId>
```

On open:

- Mark this conversation active.
- Clear `unreadCount`.
- Clear `mentionUnreadCount` for group chat.
- For single chat, send `READ_ACK` based on local max incoming `serverSeq`.
- Refresh `Message` list and bottom unread badge.

## 5. Contacts

### 5.1 Page Goal

`Contacts` shows available contacts that can start a chat. The current version uses local demo contact resolution and does not include real friend adding or search.

Each contact row displays:

- Avatar
- Display name
- User ID / phone number

### 5.2 Page Routes

```text
Bottom tab Contacts -> Contacts
Contacts -> tap contact row -> Chat
```

After tapping a contact:

1. Build a single-chat `conversationId` from current user ID and contact ID.
2. Navigate to `chat/single:<lowerUserId>:<higherUserId>`.
3. If no message exists yet, the chat page is empty but the user can send the first message.

## 6. Chat

### 6.1 Page Goal

`Chat` supports both single chat and group chat in one screen.

Top bar:

- Left Back button.
- Center title: peer nickname for single chat, group name for group chat.
- Group chat shows `...` on the right for group rename.

Message list:

- Reverse list layout with newest messages visually at the bottom.
- Initial local load: latest 20 messages.
- When the user scrolls near the older-message boundary, load 20 earlier messages.
- The current chat session keeps a grow-only in-memory cache capped at 2,000 messages.

Composer:

- Text input.
- Empty draft shows `Image`.
- Non-empty draft shows `Send`.
- `Image` lets the user pick up to 9 images.
- In group chat, typing `@` at the end of the draft shows a member picker.

### 6.2 Single-Chat Entry

From contacts:

```text
Contacts -> tap contact -> Chat(single)
Chat -> enter text -> tap Send -> send single-chat text
Chat -> tap Image -> pick image -> upload and send image
```

From message list:

```text
Message -> tap existing single conversation -> Chat(single)
```

### 6.3 Group-Chat Entry

```text
Message -> tap existing group conversation -> Chat(group)
Group Create -> create success -> Chat(group)
```

Group rename:

```text
Chat(group) -> tap top-right ... -> rename dialog
Rename dialog -> enter new name -> tap 保存 -> PATCH /groups/{groupId}
Rename dialog -> tap 取消 -> close dialog
```

### 6.4 Text Send Logic

When the user enters text and taps `Send`:

1. Client generates `messageId`.
2. Client generates local `clientSeq` for the conversation.
3. Message is inserted into `messages` with `SENDING`.
4. `conversations` summary is updated without increasing unread.
5. `pending_messages` is inserted for ACK timeout retry.
6. Client sends WebSocket `SEND_MESSAGE`.
7. Server accepts it and allocates conversation-local `serverSeq`.
8. Server returns `MESSAGE_ACK`.
9. Client marks the message `SENT`, stores `serverSeq`, and removes the pending row.

Failure and retry:

- Outbox worker retries due pending messages after connection becomes `Authenticated`.
- Retry exhaustion marks the message `FAILED`.
- Image upload failure is represented as `UPLOAD_FAILED`.
- Failed images can be retried by tapping the failure indicator.

### 6.5 Image Send Logic

When the user taps `Image` and picks images:

1. Client compresses and prepares local original/thumbnail paths.
2. Client inserts an `UPLOADING` image message with preview content `[图片]`.
3. Client requests upload target from backend.
4. After upload success, client stores `imageUrl`, `thumbnailUrl`, dimensions, MIME type, and size.
5. Status changes to `SENDING`.
6. Pending row is inserted and `SEND_MESSAGE` is sent.
7. ACK, retry, and failure handling follow the text-message path.

Group images must persist and send with `conversationId = group:<groupId>`. They must not be modeled as a single chat to a fake user ID `group:<groupId>`.

### 6.6 Message Rendering

Normal messages:

- Outgoing messages are right aligned, light green bubbles, with current user avatar on the right.
- Incoming peer or group-member messages are left aligned, light gray/white bubbles, with sender avatar on the left.
- Group message sender identity comes from the real `senderId` profile, not the group avatar.
- Long press on text bubbles opens an action bar.
- Tap image bubbles to preview; long press may open an action bar.

Recalled messages:

- The original row is not deleted.
- UI renders a centered system notice.
- Sender-side copy: `你撤回了一条消息`
- Receiver-side copy: `对方撤回了一条消息`
- Recall notices do not show avatar, bubble, copy action, or read/unread status.

Long-press actions:

- Text message: `复制 | 撤回`
- Image message: `撤回`
- Copy uses the original text `content`.
- Recall is shown only for current user's own sent message that has `serverSeq`, is not recalled, and is within the 2-minute recall window.

### 6.7 Read And Unread Status

Three statuses must remain separate:

- `MESSAGE_ACK`: server accepted sender message and assigned `serverSeq`.
- `DELIVERY_ACK`: receiver device persisted `RECEIVE_MESSAGE`; this is not read.
- `READ_ACK`: receiver user opened the single-chat page and read up to a `serverSeq` cursor.

Single-chat read UI:

- Display only next to outgoing messages.
- Unread: hollow circle.
- Read: green check.
- Decision rule:

```text
message.direction == OUTGOING
message.serverSeq != null
message.serverSeq <= conversation.peerReadUpToServerSeq
```

Read trigger:

- Send once when entering a single chat.
- While staying in a single chat, send again after receiving a new incoming message if the local max incoming `serverSeq` advances.
- Do not resend when the cursor does not advance.

Group chat does not currently show group read members and does not send group `READ_ACK`. Group read semantics are deferred.

### 6.8 Incoming Message Handling

Message receiving must not depend on the visible page. The login-session scoped `MessagePacketProcessor` consumes inbound WebSocket packets and passes them to `MessageRepository`.

On `RECEIVE_MESSAGE`:

1. Parse message type, conversation type, sender, receiver, `serverSeq`, content, image payload, and mention fields.
2. Insert into `messages` idempotently by `messageId`.
3. If inserted, update `conversations`.
4. If the message conversation is not active, increment `unreadCount`.
5. If it is a group message, mentions the current user, and the group is not active, increment `mentionUnreadCount`.
6. Emit `conversationUpdates` to refresh `Message` list and bottom unread badge.
7. If `serverSeq` exists, send `DELIVERY_ACK`.
8. If this is the active single chat, try to send an advanced `READ_ACK`.
9. If it is an image message, cache the thumbnail; do not render it in chat until local thumbnail is ready.

Visible-page behavior:

- User on `Message`: row preview, time, and unread count refresh.
- User on `Contacts` or `Me`: message still persists; bottom `Message` unread badge refreshes.
- User on the active chat: message appears without increasing unread.
- User on another chat: target conversation unread increases, and list sorting updates by last-message time.

## 7. Create Group Chat

### 7.1 Entry

```text
Message -> tap top-right + -> tap 发起群聊 -> Group Create
```

### 7.2 Create Page

Page elements:

- Title: `发起群聊`
- Top-right button: `创建`; loading copy `创建中`
- Contact list
- Each contact row: checkbox, avatar, display name, user ID
- Error message area

Actions:

```text
Group Create -> select one or more contacts -> tap 创建
Group Create -> system Back or page Back -> Message
```

### 7.3 Create Success

Success flow:

1. ViewModel resolves a fresh valid access token.
2. Calls `POST /groups` with group name and member IDs.
3. Server creates a stable `groupId`, group metadata, and members.
4. Client persists `groups` and `group_members`.
5. Client inserts a `GROUP` conversation row with `conversationId = group:<groupId>`.
6. App navigates directly to `chat/group:<groupId>`.

### 7.4 Create Failure

- Token refresh failure: stay on create page, show error, do not create local group.
- Server creation failure: stay on create page, show error.
- The app must not insert a local-only group row after server-backed creation fails.

## 8. Me

### 8.1 Page Goal

`Me` is the WeChat-style current-user profile page. It shows profile data, profile editing entry, and logout.

Home shows:

- Title: `Me`
- Profile entry: avatar, nickname, `ID: <phone>`, chevron
- `Logout` button
- Error message

Routes:

```text
Bottom tab Me -> Me
Me -> tap profile entry -> Profile Detail
Me -> tap Logout -> clear local session and return to Login
```

### 8.2 Profile Detail

Detail shows:

- Back button
- Title: Profile
- `Avatar` row: avatar and chevron
- `Name` row: nickname and chevron
- `ID` row: phone number, read-only

Routes:

```text
Me -> tap profile entry -> Profile Detail
Profile Detail -> tap Avatar -> system image picker -> choose image -> upload and save avatar
Profile Detail -> tap Name -> Name Editor
Profile Detail -> Back -> Me
```

### 8.3 Edit Name

Name editor shows:

- Back button
- Title: Name
- Top-right `Save`; loading copy `Saving`
- Underline-style text input

Routes:

```text
Profile Detail -> tap Name -> Name Editor
Name Editor -> edit nickname -> tap Save -> save and return to Profile Detail
Name Editor -> Back -> cancel edit and return to Profile Detail
```

### 8.4 Edit Avatar

Avatar flow:

1. User taps `Avatar`.
2. System image picker opens.
3. Client compresses the selected image to JPEG under 1 MB.
4. Client requests OSS upload target from backend.
5. Client PUTs image bytes to signed URL.
6. Client saves new `avatarUrl` and object key through `PUT /users/me`.
7. Local `user_profiles` updates.
8. `Me`, `Message` row avatars, and `Chat` avatars refresh through profile cache.

### 8.5 Logout

After tapping `Logout`:

1. Close active conversation.
2. Disconnect WebSocket.
3. Clear local token state.
4. Revoke refresh token on server.
5. Return to Login.

## 9. Local Persistence

### 9.1 Principles

Android uses SQLiteOpenHelper directly. Room is not used.

Database name:

```text
self_hosted_im.db
```

Core tables:

- `messages`: full message records.
- `conversations`: conversation summary cache for `Message`.
- `pending_messages`: packets waiting for ACK or retry.
- `user_profiles`: user profile cache.
- `groups`: group metadata.
- `group_members`: group members.

Tokens are stored in `SharedPreferences`, not SQLite.

### 9.2 User Profile Persistence

Table: `user_profiles`

Fields:

- `user_id`
- `phone`
- `nickname`
- `avatar_url`
- `avatar_updated_at`
- `updated_at`

Uses:

- `Me` current-user display.
- `Message` peer nickname and avatar.
- `Chat` title, avatars, and group sender profiles.
- Avatar bytes are additionally cached in memory and under Android `cacheDir/avatar-images`.

Refresh sources:

- Login/register response.
- `GET /users/me`
- `GET /users/{userId}`
- `POST /users/batch`
- `PUT /users/me`

### 9.3 Single-Chat Persistence

Single-chat `conversationId`:

```text
single:<lowerUserId>:<higherUserId>
```

This normalizes both sides of A/B into the same stable conversation ID.

`messages` stores full records:

- `message_id`
- `conversation_id`
- `sender_id`
- `receiver_id`
- `client_seq`
- `server_seq`
- `content`
- `message_type`
- image fields
- recall fields
- `status`
- `direction`
- `created_at`
- `updated_at`

`conversations` stores home summaries:

- Peer ID and nickname.
- Last message ID, preview, and time.
- Conversation unread count.
- Peer read cursor: `peer_read_up_to_server_seq` and `peer_read_at`.

### 9.4 Group-Chat Persistence

Group-chat `conversationId`:

```text
group:<groupId>
```

`groups`:

- `group_id`
- `name`
- `avatar_url`
- `owner_id`
- `created_at`
- `updated_at`

`group_members`:

- `group_id`
- `user_id`
- `display_name`
- `avatar_url`
- `role`
- `joined_at`
- `updated_at`

Group messages still use `messages`:

- `conversation_type = GROUP`
- `group_id = <groupId>`
- `conversation_id = group:<groupId>`
- `receiver_id` is `groupId` when sending, and the concrete recipient user ID when forwarded by server.
- `mentions_json` stores mentioned user IDs.

Group summary uses `conversations`:

- `conversation_type = GROUP`
- `title = group name`
- `mention_unread_count` stores @-me unread count.
- `unread_count` stores normal unread count.

### 9.5 Retry Persistence

Table: `pending_messages`

Fields:

- `message_id`
- `packet_cmd`
- `packet_body`
- `retry_count`
- `next_retry_at`
- `created_at`

Rules:

- On send, write local message and pending row before WebSocket send.
- On `MESSAGE_ACK`, delete pending row.
- After connection becomes `Authenticated`, outbox worker retries due pending rows.
- Retry exhaustion marks the message `FAILED` and deletes the pending row.

## 10. Server Persistence And Reliability

The mock server provides local-test reliability support.

Server databases:

- Users and refresh tokens: `mock-server/data/mock-im-users.sqlite`
- Accepted messages and offline delivery state: `mock-server/data/mock-im-messages.sqlite`
- Conversation sequence numbers: `mock-server/data/mock-im-sequences.sqlite`
- Group metadata and members: `mock-server/data/mock-im-groups.sqlite`

Reliability rules:

- `MESSAGE_ACK` means server accepted the message and assigned `serverSeq`.
- Single-chat offline messages are queued for the receiver.
- Group fanout records delivery state per concrete receiver.
- `DELIVERY_ACK` clears the receiver's undelivered state.
- Server restart restores accepted messages and undelivered state, avoids reusing `serverSeq`, and continues offline replay.
- Duplicate `messageId` sends are idempotent: return original ACK and do not allocate a new `serverSeq`.

## 11. Message Ordering

Two sequence fields exist:

- `clientSeq`: sender-local sequence for send order and ACK correlation. It is not globally authoritative.
- `serverSeq`: server-assigned conversation-local authoritative order.

Display rules:

- Confirmed outgoing and incoming messages with `serverSeq` are ordered by `serverSeq`.
- Local `SENDING` messages without `serverSeq` remain visible using temporary `createdAt`, `clientSeq`, and `messageId` ordering.
- Out-of-order `RECEIVE_MESSAGE` packets are persisted first, then the list is re-queried or merged according to the ordering policy.
- Gap buffering, wait windows, and remote history backfill are not implemented in the current scope.

## 12. Conversation Summary And Unread Rules

`Message` home reads `conversations`; it does not aggregate from `messages` every time.

Incoming message:

- Insert into `messages`.
- Upsert `conversations`.
- If the new message is newer than the current summary, replace preview and time.
- If the conversation is not active: unread +1.
- If the conversation is active: unread does not increase.

Outgoing message:

- Insert into `messages`.
- Update `conversations` preview and time.
- Do not increase unread.

Opening a conversation:

- Clear `unread_count`.
- For group chat, also clear `mention_unread_count`.
- Refresh `Message` list and bottom badge.

Bottom `Message` total unread:

```sql
SELECT COALESCE(SUM(unread_count), 0) FROM conversations
```

## 13. Non-Goals And Deferred Scope

This PRD does not require:

- Full friend system, friend request, or friend search.
- Production push notifications.
- Group read-member list.
- Group owner transfer, admins, mute, leave group, or remove member.
- Remote history pagination.
- Full multi-device consistency.
- Production-grade database migration strategy.
- Production-grade OSS permission and private-access design.
