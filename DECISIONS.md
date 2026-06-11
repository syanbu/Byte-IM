# DECISIONS

本文用 ADR（Architecture Decision Record）格式记录项目中的关键架构决策。当前实现状态请结合 [`docs/bg/DEVELOPMENT_STATUS.md`](docs/bg/DEVELOPMENT_STATUS.md) 阅读。

## ADR-001: 自研 IM 客户端核心链路

**状态：** 已采纳

**背景：**  
项目目标要求实现自研 IM 客户端，不接入腾讯云 IM、融云、环信、火山 RTC IM 等现成 IM SDK。服务端可以是本地 mock server，但客户端需要自己处理协议、连接、ACK、重试、去重、排序、持久化和 UI 状态。

**决策：**  
Android 客户端自行实现 IM 核心链路；mock-server 仅作为本地联调、协议验证和功能验证环境。

**影响：**

- 客户端代码需要覆盖连接生命周期、消息可靠性、数据库和 UI 状态。
- 服务端可以快速演进，但不能隐藏客户端链路问题。
- 文档必须解释协议、状态机和可靠性语义。

**参考：**

- [`docs/bg/PROJECT_TARGET.md`](docs/bg/PROJECT_TARGET.md)
- [`docs/bg/PROJECT_BACKGROUND.md`](docs/bg/PROJECT_BACKGROUND.md)

## ADR-002: WebSocket 上使用自定义二进制协议帧

**状态：** 已采纳

**背景：**  
项目需要体现协议设计能力，而不是仅发送普通 JSON 文本。协议需要携带命令、长度和校验信息，并能区分认证、心跳、消息、ACK、撤回、已读等命令。

**决策：**  
在 OkHttp WebSocket 之上封装自定义二进制协议帧，包含 header、body 和 CRC。body 当前可用 JSON 表达业务字段。

**影响：**

- Android 和 mock-server 都维护协议枚举和编解码器。
- 协议命令与 Android 本地 `ConnectionState` 分层，避免把网络命令当 UI 状态。
- 后续扩展 `DELIVERY_ACK`、`RECALL_NOTIFY_ACK`、`HISTORY_QUERY` 等命令时有明确位置。

**参考：**

- [`docs/status/B6-binary-protocol.md`](docs/status/B6-binary-protocol.md)
- [`docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)

## ADR-003: Android 本地存储使用手写 SQLiteOpenHelper

**状态：** 已采纳

**背景：**  
B5 要求所有消息、会话、未读数等数据持久化到本地 SQLite，并明确禁用 Room。

**决策：**  
使用 `SQLiteOpenHelper` 和明确 DAO 边界管理本地存储，包括 messages、conversations、pending_messages、profiles、groups、group_members、friend_contacts 和 group_read_cursors 等表。

**影响：**

- 数据结构和迁移逻辑更透明，符合项目要求。
- 需要自己维护 SQL、索引、cursor 解析和事务边界。
- UI 不直接保存 IM 事实，最终状态来自 DAO / repository。

**参考：**

- [`docs/status/B5-local-persistence.md`](docs/status/B5-local-persistence.md)
- [`docs/bg/PROJECT_BACKGROUND.md`](docs/bg/PROJECT_BACKGROUND.md)

## ADR-004: `serverSeq` 是会话内权威排序键

**状态：** 已采纳

**背景：**  
单聊和群聊都可能出现多端、多发送方、乱序 ACK 和离线重放。客户端本地 `clientSeq` 只能表示本机发送顺序，不能代表会话最终顺序。

**决策：**  
mock-server 按会话分配 `serverSeq`；Android 对已确认和已接收消息按 `serverSeq` 排序。`clientSeq` 只用于本地发送和 ACK 关联。

**影响：**

- 接收端最终展示顺序稳定。
- pending 消息和 confirmed 消息需要在 UI 上处理临时状态。
- 服务端 sequence store 成为可靠排序的核心组件之一。

**参考：**

- [`docs/status/B8-message-ordering.md`](docs/status/B8-message-ordering.md)
- [`docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)

## ADR-005: ACK 分层为发送确认、投递确认和已读确认

**状态：** 已采纳

**背景：**  
IM 消息生命周期至少包含服务端收到、接收设备落库、用户已读三个不同事实。如果混用一个 ACK，重试、离线重放和已读 UI 都会变得含糊。

**决策：**  
定义三类语义：

- `MESSAGE_ACK`：服务端接收并分配 `serverSeq`。
- `DELIVERY_ACK`：接收设备已经本地持久化。
- `READ_ACK`：用户已读到某个会话或群的游标。

**影响：**

- B9 sender-side 重试只依赖 `MESSAGE_ACK`。
- B9.5 receiver-side 重放依赖 `DELIVERY_ACK`。
- B12 已读回执依赖 `READ_ACK`，不从投递确认推导已读。

**参考：**

- [`docs/status/B9-message-reliability.md`](docs/status/B9-message-reliability.md)
- [`docs/status/B9.5-delivery-ack.md`](docs/status/B9.5-delivery-ack.md)
- [`docs/status/B12-message-recall-and-read-receipts.md`](docs/status/B12-message-recall-and-read-receipts.md)

## ADR-006: 入站消息由登录会话级处理器统一消费

**状态：** 已采纳

**背景：**  
如果聊天页或会话列表页各自 collect WebSocket packets，页面不可见时可能漏收消息；多个 collector 也可能重复处理同一个 packet。

**决策：**  
使用登录会话级 `MessagePacketProcessor` 统一消费入站消息，持久化后通过 repository update event 让页面刷新。

**影响：**

- App 登录后即使在 Contacts 或 Me 页面也能接收和持久化消息。
- 页面 ViewModel 不再承担协议 packet 的唯一消费职责。
- 减少重复 ACK、重复插入和生命周期相关 bug。

**参考：**

- [`docs/status/B9.5-delivery-ack.md`](docs/status/B9.5-delivery-ack.md)
- [`docs/bg/PROJECT_BACKGROUND.md`](docs/bg/PROJECT_BACKGROUND.md)

## ADR-007: B13 使用 mock push 方案

**状态：** 已采纳

**背景：**  
真实厂商推送接入成本高，需要平台账号、证书和厂商设备验证。原始需求允许在成本较高时使用 mock 方案。

**决策：**  
B13 第一版使用 mock push：Android 注册 mock token，mock-server 在接收方离线时写入 pending push，Android WorkManager 定期拉取并展示通知，点击通知 deep-link 到会话。

**影响：**

- 可以验证 push payload、离线入队、通知展示、ack 和 deep-link。
- 不能证明真实厂商通道在被杀进程下的唤起能力。
- 后续如果要生产化，需要替换 token 来源和 push 投递通道。

**参考：**

- [`docs/status/B13-mock-push.md`](docs/status/B13-mock-push.md)
- [`docs/feature-notes/B13-mock-push.md`](docs/feature-notes/B13-mock-push.md)

## ADR-008: 图片首帧优化采用有限等待预热

**状态：** 已采纳

**背景：**  
从会话列表或通知进入包含图片的聊天页时，消息元数据可能已缓存，但 Coil bitmap 未必已解码，导致首帧灰色占位。直接在主线程阻塞等待解码会带来卡顿风险。

**决策：**  
导航前短时间等待本地缩略图预热：限制图片数量、限制并发、设置超时。超时后继续导航，保留 UI 兜底加载。

**影响：**

- 冷缓存下减少灰色占位首帧。
- 极端情况下仍以响应速度优先，不阻塞用户过久。
- 预热逻辑集中在 `ChatInitialImagePrewarmer`，入口复用 `openPreloadedChat`。

**参考：**

- [`docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md`](docs/bug/Fix-ChatImagePrewarmBeforeNavigation.md)
- [`docs/feature-notes/coil-image-preload-mechanism.md`](docs/feature-notes/coil-image-preload-mechanism.md)

## ADR-009: 运行状态文档优先于历史计划文档

**状态：** 已采纳

**背景：**  
`docs/superpowers/plans/` 和 `docs/superpowers/specs/` 保留了开发过程中的设计和计划，但随着实现演进，它们不一定代表当前最终状态。

**决策：**  
项目状态以 `docs/bg/DEVELOPMENT_STATUS.md`、`docs/status/` 和 `docs/feature-notes/` 为准；计划和规格文档只作为历史上下文。

**影响：**

- 新读者先看当前状态，再追溯历史计划。
- 避免把旧计划误认为当前实现。
- README 和 docs README 都指向当前状态入口。

**参考：**

- [`docs/README.md`](docs/README.md)
- [`docs/bg/DEVELOPMENT_STATUS.md`](docs/bg/DEVELOPMENT_STATUS.md)

## ADR-010: 鉴权采用双 Token 与 refresh-token rotation

**状态：** 已采纳

**背景：**  
客户端需要支持登录态恢复、过期自动续期、WebSocket 鉴权和退出登录。单一长期 token 会让泄漏风险和撤销语义变差；只使用短期 access token 又会导致用户频繁重新登录。

**决策：**  
HTTP 登录/注册返回 access token 与 refresh token。Android 将 token 会话保存在 `SharedPreferences`；access token 过期且 refresh token 有效时静默刷新。mock-server 在 `/refresh` 中同时签发新的 access/refresh token，并在同一个 SQLite transaction 中废弃旧 refresh token；退出登录时撤销 refresh token。

**影响：**

- WebSocket `AUTH` 只接受有效 access token。
- Android 发起鉴权 HTTP 请求前需要通过统一 session provider 获取新鲜 token。
- 旧 refresh token 不能复用，降低 token 泄漏后的长期风险。
- 当前签名使用 mock-server 对称 HS256，不代表生产级非对称密钥体系。

**参考：**

- [`docs/status/B1-auth.md`](docs/status/B1-auth.md)

## ADR-011: 本地 IM 数据按账号隔离

**状态：** 已采纳

**背景：**  
同一设备可能切换多个手机号账号。如果消息、会话、群资料和联系人共享同一个本地数据库，退出/切换账号后容易看到前一个账号的数据，且未读数、群成员和 profile 缓存会互相污染。

**决策：**  
Android 本地 SQLite 数据库使用账号维度命名和打开，登录会话恢复后绑定到当前 user id。IM 事实数据从账号所属 DAO / repository 读取，登出只清理鉴权 token，不把其他账号的本地历史混在当前账号视图中。

**影响：**

- 多账号本地数据互不污染。
- DAO contract 需要覆盖账号库名稳定性和安全文件名。
- 需要在登录态切换时重建 repository / database helper，而不是继续复用旧账号实例。

**参考：**

- [`docs/status/B5-local-persistence.md`](docs/status/B5-local-persistence.md)
- [`docs/status/B1-auth.md`](docs/status/B1-auth.md)

## ADR-012: mock-server 持久化 accepted message 与 per-receiver delivery state

**状态：** 已采纳

**背景：**  
仅用内存保存服务端已接收消息会导致 mock-server 重启后丢失 `messageId` 幂等、离线重放和后续历史查询基础。B9 的发送侧可靠性需要服务端在重启后仍能识别重复发送。

**决策：**  
mock-server 使用 SQLite 保存 accepted messages、原始 ACK / RECEIVE body、`serverSeq` 和接收方投递状态。运行时仍保留 `acceptedMessagesById` 与 `undeliveredMessagesByReceiver` 内存索引，但启动时从 SQLite hydrate，`DELIVERY_ACK` 同步更新持久化 delivered 标记。

**影响：**

- mock-server 重启后重复 `messageId` 仍返回原始 `MESSAGE_ACK`，不会分配新的 `serverSeq`。
- 离线接收方重连后可以继续收到未 `DELIVERY_ACK` 的消息。
- 为未来 server-backed history 保留 accepted message 数据基础。
- `MESSAGE_ACK`、`DELIVERY_ACK` 的语义不变，只是补上重启恢复能力。

**参考：**

- [`docs/status/B5.5-mock-server-message-persistence.md`](docs/status/B5.5-mock-server-message-persistence.md)
- [`docs/status/B9.5-delivery-ack.md`](docs/status/B9.5-delivery-ack.md)

## ADR-013: 连接生命周期由单一 supervisor 管理

**状态：** 已采纳

**背景：**  
如果各页面各自连接、重连或维护心跳，容易出现重复连接、页面切换断链、后台策略不一致和重连风暴。IM 连接是登录会话级资源，不应由某个页面独占。

**决策：**  
Android 使用 `ConnectionLifecycleManager` 作为单一连接 supervisor，统一管理 WebSocket 连接、鉴权后心跳、前后台心跳频率、断线重连、发送失败重连和网络恢复唤醒。UI 只观察 `ConnectionState`，不直接抢占连接生命周期。

**影响：**

- 前台心跳 15 秒，后台心跳 75 秒。
- 断开、失败、心跳超时、发送失败都进入同一指数退避重连策略。
- Android 网络恢复可取消等待中的 backoff 并立即重连。
- ViewModel 不能再各自发起重复 direct connect。

**参考：**

- [`docs/status/B7-heartbeat-reconnect.md`](docs/status/B7-heartbeat-reconnect.md)

## ADR-014: 会话列表与聊天历史采用本地 cursor pagination

**状态：** 已采纳

**背景：**  
一次性加载大量会话或历史消息会拖慢启动和滚动；offset pagination 在新消息插入并导致排序变化时容易跳页或重复。当前项目已经以本地 SQLite 为 IM 事实源，远端历史查询仍暂缓。

**决策：**  
会话列表使用 `(lastMessageTime, conversationId)` cursor 分页，按 `last_message_time DESC, conversation_id ASC` 排序；聊天历史使用最早可见消息的 `createdAt` 作为 `beforeTime` cursor。两者都从本地 SQLite 加载，刷新时按 id 合并，避免丢失已加载页。

**影响：**

- Messages 首屏固定读取 50 条，并在后台预取下一页。
- Chat 首屏固定读取最近 20 条，滚动到旧消息端附近自动加载更早消息。
- 远端历史仍必须走预留的 `HISTORY_QUERY` / `HISTORY_RESULT`，不能绕开协议另开临时 HTTP 通道。
- UI 需要维护加载中、是否还有更多、本地内存上限等状态。

**参考：**

- [`docs/status/B3-conversation-list.md`](docs/status/B3-conversation-list.md)
- [`docs/status/B4-history-pagination.md`](docs/status/B4-history-pagination.md)

## ADR-015: 单聊和群聊共用 conversation abstraction

**状态：** 已采纳

**背景：**  
早期单聊逻辑以 peerId 为中心，但群聊不能建模为一个假的 peer 用户。群聊还需要稳定 group id、群成员、@ 元数据、群名和 per-member 投递状态。如果继续沿用 peer-only 路由，会产生 `single:<sender>:group:<groupId>` 这类错误会话。

**决策：**  
以 canonical `conversationId` 表示会话目标：单聊使用 `single:<lowerUserId>:<higherUserId>`，群聊使用 `group:<groupId>`。Android model、SQLite schema、repository、导航路由和 mock-server fanout 都携带 `ConversationType` / `groupId`，聊天页通过 `chat/{conversationId}` 打开单聊或群聊。

**影响：**

- 单聊和群聊共用聊天 UI、消息表、会话表和可靠性协议。
- 群聊消息使用真实 senderId，并按具体接收成员发送 `DELIVERY_ACK`。
- 群创建必须走 server-backed `POST /groups`，不再创建长期 local-only 群。
- @ 提醒基于持久化 `mentionedUserIds`，不依赖纯文本解析作为权威来源。

**参考：**

- [`docs/status/B10-group-chat-and-mention.md`](docs/status/B10-group-chat-and-mention.md)

## ADR-016: 图片消息拆分为资源上传阶段与 IM 消息阶段

**状态：** 已采纳

**背景：**  
图片消息既有大文件上传，又有 IM 消息可靠投递。OSS 上传和 WebSocket 发送无法做真正跨系统事务。如果把图片 bytes 放入 IM pending 表，重试成本和数据体积都会失控；如果上传失败和消息发送失败不区分，用户也无法理解 retry 发生在哪一步。

**决策：**  
图片消息先在本地创建 `UPLOADING` 消息行，上传缩略图和原图到 OSS；上传成功后进入正常 `SEND_MESSAGE -> MESSAGE_ACK` 流程，并将最终 packet 写入 `pending_messages`。上传失败标记 `UPLOAD_FAILED`，不创建 pending row；发送失败沿用文本消息 outbox 重试。

**影响：**

- `pending_messages` 只保存 IM packet，不保存图片 bytes。
- 图片消息保留 `content = "[图片]"`，会话预览兼容文本路径。
- receiver 只预取缩略图，本地缩略图缓存完成前可暂时隐藏 incoming image 行，原图按需预览。
- 引入 `coil-compose` 用于聊天图片加载，头像仍可走轻量缓存路径。
- 接受学习 demo 范围内的 OSS orphan file 风险，不做生产级清理。

**参考：**

- [`docs/status/B11-image-message-design-status.md`](docs/status/B11-image-message-design-status.md)

## ADR-017: 撤回是状态变更，不物理删除消息

**状态：** 已采纳

**背景：**  
撤回如果直接删除 SQLite 消息行，会破坏时间线位置、会话预览、排序、read cursor 和离线通知补发。接收方离线时还需要在重连后可靠同步“该消息已撤回”这个事实。

**决策：**  
撤回通过 `RECALL_MESSAGE` / `RECALL_ACK` / `RECALL_NOTIFY` / `RECALL_NOTIFY_ACK` 表示状态同步。Android 和 mock-server 都保留原消息记录，只标记 `is_recalled`、`recalled_at`、`recalled_by`。UI 在原时间线位置渲染居中的撤回提示，不显示原文本/图片、头像或读未读标记。

**影响：**

- mock-server 以服务端时间校验 2 分钟撤回窗口。
- 离线接收方的 pending recall notification 会持久化并在 auth/reconnect 后重放，直到收到 `RECALL_NOTIFY_ACK`。
- 单聊和群聊共享聊天 UI 的撤回呈现路径。
- 对同一消息重复撤回需要保持幂等。

**参考：**

- [`docs/status/B12-message-recall-and-read-receipts.md`](docs/status/B12-message-recall-and-read-receipts.md)

## ADR-018: 已读回执使用 conversation-level read cursor

**状态：** 已采纳

**背景：**  
已读不是投递，不能从 `DELIVERY_ACK` 推导。逐条已读 ACK 会增加协议噪音；IM 会话天然可以用“读到某个服务端序号”为游标表达已读进度。

**决策：**  
客户端在打开会话或打开会话期间收到新的 incoming 消息后，按本地已持久化 incoming 消息的最大 `serverSeq` 发送 `READ_ACK`。单聊在 `conversations` 中保存 peer read cursor；群聊在 `group_read_cursors` 中保存成员游标。UI 基于 `serverSeq <= readCursor` 推导当前用户已发送消息的读/未读展示。

**影响：**

- read cursor 单调更新，旧 ACK 不能把 UI 从已读倒退为未读。
- 只对已经本地落库且有 `serverSeq` 的消息发送已读。
- 群聊已读人数和读者列表可以从群成员与游标表推导。
- `DELIVERY_ACK` 继续只表示本地持久化，不影响已读 UI。

**参考：**

- [`docs/status/B12-message-recall-and-read-receipts.md`](docs/status/B12-message-recall-and-read-receipts.md)

## ADR-019: Profile、Contacts、Messages、Me 作为登录后主导航结构

**状态：** 已采纳

**背景：**  
早期工程 UI 更像联调页面，聊天页暴露连接状态、原始 ID 和调试文本。随着 profile、联系人、群聊、头像、通知 deep-link 等能力增加，需要更稳定的应用级导航结构和身份展示来源。

**决策：**  
登录后使用 Navigation Compose 管理 authenticated graph，并将 `Messages`、`Contacts`、`Me` 作为顶层 tab。会话列表是登录后的起点；聊天、联系人资料、群资料、个人资料编辑等作为子路由。用户昵称、头像、联系人和群资料通过 profile/contact/group repository 与本地 SQLite 缓存提供。

**影响：**

- logout 从 Messages 移到 Me，聊天页只保留返回语义。
- 系统 Back 在顶层路由按 `TopLevelBackPolicy` 处理，聊天 Back 回到会话列表。
- 会话和聊天页面显示昵称/头像，而不是裸手机号 ID。
- profile/avatar/contacts 不只是 UI 细节，已经成为会话展示和群聊体验的数据依赖。

**参考：**

- [`docs/status/self-design-profile-chat-ui-status.md`](docs/status/self-design-profile-chat-ui-status.md)
- [`docs/status/B3-conversation-list.md`](docs/status/B3-conversation-list.md)

## ADR-020: Push 只做通知唤醒提示，不作为消息事实来源

**状态：** 已采纳

**背景：**  
B13 允许使用 mock push，但真实消息可靠性已经由 WebSocket、mock-server accepted message store 和 receiver `DELIVERY_ACK` 保证。如果 push payload 直接伪造完整消息入库，会和 WebSocket 重放产生双写、顺序和去重问题。

**决策：**  
mock push 只作为离线通知与 deep-link 机制：离线时 mock-server 写入 pending push，Android WorkManager 拉取并展示系统通知；点击通知打开对应 conversation。真正消息仍通过 WebSocket auth/reconnect 后的 undelivered replay 入库，并继续发送 `DELIVERY_ACK`。

**影响：**

- push payload 只需要发送方昵称、消息预览和 conversation deep-link 信息。
- push 入队不能替代服务端 undelivered 持久化。
- Android 收到 push 后不能跳过 `MessagePacketProcessor` 和 repository 入站路径。
- mock push 无法证明真实厂商通道在 force-stop 等场景下的生产能力。

**参考：**

- [`docs/status/B13-mock-push.md`](docs/status/B13-mock-push.md)
