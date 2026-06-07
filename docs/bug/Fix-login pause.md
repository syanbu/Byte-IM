下面是整理好的 Markdown 文档内容：

# IM 安卓客户端断网启动与双令牌登录态优化方案

## 当前状态（2026-05-28）

本次 bug 已按最小修复思路落地，当前实现状态如下：

- 已将“本地 session 恢复”和“在线 token 校验/刷新”拆开。
- App 启动时，`restoreSession()` 现在优先读取本地可恢复 session；只要本地 `refresh_token` 仍有效，就允许进入主界面，不再阻塞等待服务端 refresh。
- 当 `access_token` 已过期但当前断网、超时或服务端暂不可达时，`ensureValidSession()` 不再清空本地登录态，而是保留本地 session，让界面维持已登录但离线的状态。
- 只有在 `refresh_token` 本地已过期，或服务端明确返回会话失效/需要重新登录时，才会清理本地 session 并回到登录页。
- 已补充回归测试，覆盖“离线恢复本地登录态”“refresh 网络失败不清 session”“登录页状态保持已认证”三个关键场景。

这意味着当前行为已经从：

```text
断网 / refresh 失败 -> 误判未登录 -> 停留登录页
```

调整为：

```text
断网 / refresh 失败 -> 保留本地登录态 -> 进入主界面并显示离线
```

## 增量修复（2026-05-31）：并发 refresh 轮换误清登录态

### 现象

在 access token 过期后，客户端理论上会通过 `ensureValidSession()` 自动使用 refresh token 刷新。但实际仍可能出现进入 App 后被拉回登录界面的问题。

### 根因

当前 mock-server 的 refresh token 已改为轮换机制：

```text
refresh 成功 -> 服务端签发新的 accessToken + refreshToken -> 旧 refreshToken 立即作废
```

App 进入主界面后，多个模块可能几乎同时请求有效 session：

```text
WebSocket tokenProvider
ConversationListViewModel.refresh()
Profile / Group 同步
其它需要 accessToken 的页面请求
```

如果这些协程同时调用 `AuthRepository.ensureValidSession()`，就可能发生：

```text
协程 A 读取旧 refreshToken -> refresh 成功 -> 保存新 refreshToken
协程 B 也已读取旧 refreshToken -> refresh 失败：expired or revoked
旧逻辑把协程 B 的失败当成会话失效 -> clearStoredSession()
LoginViewModel 收到 sessionState = null -> 回到登录页
```

这不是用户真的登录过期，而是并发 refresh 与 refresh token 轮换之间的竞态。

### 修复

`AuthRepository.ensureValidSession()` 现在使用 `Mutex` 串行化本地读取、refresh、写回流程：

```text
第一个调用发现 accessToken 过期 -> 使用 refreshToken 刷新并保存新 session
第二个调用进入锁后重新读取本地 session -> 发现 accessToken 已更新且未过期 -> 直接返回
```

这样同一轮过期恢复只会触发一次 refresh，不会再用已经被轮换作废的旧 refresh token 发起第二次刷新，也不会误清本地登录态。

### 回归测试

新增测试：

```text
AuthRepositoryTest.concurrentEnsureValidSessionRefreshesOnlyOnceWhenRefreshTokenRotates
```

该测试先复现两个并发 `ensureValidSession()` 同时遇到过期 access token 的场景，并验证：

```text
refresh API 只调用一次
两个调用都返回刷新后的 session
本地 tokenStore 保留刷新后的 session
repository.sessionState 保留刷新后的 session
```

验证命令：

```text
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.auth.AuthRepositoryTest --tests com.buyansong.im.auth.LoginViewModelTest --tests com.buyansong.im.auth.OkHttpAuthApiTest --console=plain
```

验证结果：

```text
AuthRepositoryTest
LoginViewModelTest
OkHttpAuthApiTest
```

认证相关测试已通过。

## 一、问题背景

当前安卓 IM App 在启动时存在一个问题：

当手机处于断网状态时，用户进入 App 后会被卡在登录界面，无法进入主界面。

初步原因是：

App 启动时强依赖服务端 JWT 校验流程。也就是说，程序需要先请求服务端验证当前 token 是否有效，验证通过后才允许用户进入主页。

但是在断网情况下，服务端无法访问，JWT 无法验证，导致 App 误以为用户没有登录，从而停留在登录页。

对于一个类似微信、飞书的 IM 系统来说，这种体验不合理。用户即使断网，也应该能够进入 App，查看本地缓存的会话列表和聊天记录。

---

## 二、核心问题

当前设计把两个概念混在了一起：

1. 本地是否曾经登录过
2. 服务端当前是否认可这个登录态

这两个概念应该分开处理。

### 1. 本地是否曾经登录过

这个判断只依赖本地数据，例如：

```text
user_id
access_token
refresh_token
last_login_time
local_session_valid
```

只要本地存在有效的 session 信息，就说明用户曾经登录过，可以先进入 App 主界面。

### 2. 服务端当前是否认可登录态

这个判断需要联网，例如：

```text
access_token 是否过期
refresh_token 是否有效
账号是否被冻结
账号是否被踢下线
密码是否被修改
```

这一步应该放到后台异步执行，而不是阻塞 App 启动。

---

## 三、推荐启动流程

### 当前不推荐流程

```text
打开 App
  ↓
读取 access_token / refresh_token
  ↓
请求服务端验证 JWT
  ↓
验证成功 -> 进入主页
验证失败 / 网络超时 / 断网 -> 停留在登录页
```

这个流程的问题是：

```text
网络状态决定了用户能不能进入 App
```

断网不应该等价于未登录。

---

### 推荐改进流程

```text
打开 App
  ↓
读取本地 session
  ↓
判断本地是否存在登录态
  ↓
如果没有本地 session
      -> 进入登录页
  ↓
如果有本地 session
      -> 直接进入主页
      -> 加载 SQLite 本地会话和聊天记录
      -> 显示离线状态
      -> 后台尝试刷新 token
      -> 后台尝试建立 WebSocket
```

核心原则是：

```text
本地 session 决定能不能进入 App；
服务端校验决定当前是 Online、Offline 还是 SessionExpired。
```

---

## 四、本地 session 应该存在哪里？

启动时确实需要读取本地持久化数据，但不建议所有数据都放在 SQLite 中。

推荐分开存储：

| 数据类型          | 推荐存储位置                                          | 说明          |
| ------------- | ----------------------------------------------- | ----------- |
| access_token  | EncryptedSharedPreferences / Android Keystore   | 敏感数据，需要加密存储 |
| refresh_token | EncryptedSharedPreferences / Android Keystore   | 敏感数据，需要加密存储 |
| user_id       | DataStore / SQLite / EncryptedSharedPreferences | 用于恢复当前用户身份  |
| 用户昵称、头像       | SQLite / DataStore                              | 可作为本地缓存     |
| 会话列表          | SQLite                                          | IM 本地缓存核心数据 |
| 聊天消息          | SQLite                                          | 支持离线查看历史消息  |
| 待发送消息         | SQLite                                          | 支持断网后恢复发送   |

更推荐的结构是：

```text
token / session 信息 -> EncryptedSharedPreferences 或 DataStore
聊天记录 / 会话列表 -> SQLite
```

---

## 五、为什么 token 不建议直接放普通 SQLite？

SQLite 文件本质上也是本地文件，例如：

```text
/data/data/包名/databases/app.db
```

虽然普通用户一般无法直接访问，但在 root、调试环境、备份或恶意环境下，仍然有被提取的风险。

JWT 和 refresh_token 一旦泄露，可能导致登录态被盗用。

因此更推荐：

```text
access_token / refresh_token -> EncryptedSharedPreferences / Android Keystore
```

而 SQLite 更适合存储业务数据，例如：

```text
会话列表
聊天记录
联系人缓存
未读数
待发送消息
```

---

## 六、双令牌模式下的启动逻辑

双令牌模式一般包含：

```text
access_token：短期有效，用于普通 API 请求和 WebSocket 鉴权
refresh_token：长期有效，用于刷新 access_token
```

App 启动时不应该强依赖 access_token 是否有效。

更合理的判断方式是：

```text
本地有 refresh_token + user_id -> 可以先进入主页
本地没有 refresh_token -> 进入登录页
```

因为 access_token 可能已经过期，但 refresh_token 仍然可以用于恢复登录态。

---

## 七、推荐的登录态判断逻辑

可以封装一个 `SessionStore`：

```kotlin
data class LocalSession(
    val userId: String,
    val accessToken: String?,
    val refreshToken: String,
    val lastLoginAt: Long
)
```

启动时判断：

```kotlin
fun hasLocalSession(): Boolean {
    return sessionStore.getUserId() != null &&
           sessionStore.getRefreshToken() != null
}
```

启动流程：

```kotlin
fun onAppStart() {
    val session = sessionStore.getSession()

    if (session == null) {
        navigateToLogin()
        return
    }

    navigateToMain()

    authManager.verifyOrRefreshInBackground()
    websocketManager.connectIfPossible()
}
```

注意：

```text
navigateToMain() 不应该等待 verifyOrRefreshInBackground() 完成。
```

也就是说，进入主页和联网校验应该解耦。

---

## 八、登录状态与连接状态设计

可以将登录状态设计为以下几种：

```kotlin
enum class AuthState {
    NoSession,        // 本地没有登录态
    LocalSession,     // 本地有登录态，但还没联网验证
    Online,           // 服务端验证成功
    Offline,          // 本地有登录态，但当前无网络
    RefreshingToken,  // 正在刷新 token
    SessionExpired    // refresh_token 失效，需要重新登录
}
```

启动时：

```text
本地无 session -> NoSession -> 登录页
本地有 session -> LocalSession -> 主界面
```

进入主页后：

```text
网络可用 + access_token 有效 -> Online
网络不可用 -> Offline
access_token 过期 -> RefreshingToken
refresh_token 刷新成功 -> Online
refresh_token 失效 -> SessionExpired -> 登录页
```

---

## 九、错误处理规则

不要把所有错误都当成登录失效。

应该区分：

| 场景                | 处理方式                 |
| ----------------- | -------------------- |
| 本地没有 session      | 进入登录页                |
| 本地有 session，但当前断网 | 进入主页，显示离线            |
| 请求超时              | 进入主页，显示连接异常          |
| access_token 过期   | 使用 refresh_token 刷新  |
| refresh_token 有效  | 更新 access_token，继续使用 |
| refresh_token 失效  | 提示重新登录               |
| 服务端返回账号被踢         | 清理本地 session，回到登录页   |
| WebSocket 连接失败    | 不影响进入主页，只显示未连接       |
| 服务端不可用            | 保持本地登录态，显示离线或服务异常    |

核心规则：

```text
NetworkError ≠ LoginExpired
```

断网、超时、服务端不可达，不应该直接跳转登录页。

---

## 十、IM 本地数据设计

IM App 断网可用的前提是本地有缓存数据。

SQLite 可以存储：

```text
conversation_list
message_table
contact_table
user_profile_cache
pending_message_table
```

### 会话表示例

```sql
CREATE TABLE conversation (
    conversation_id TEXT PRIMARY KEY,
    peer_id TEXT NOT NULL,
    title TEXT,
    avatar_url TEXT,
    last_message TEXT,
    last_message_time INTEGER,
    unread_count INTEGER
);
```

### 消息表示例

```sql
CREATE TABLE message (
    local_id TEXT PRIMARY KEY,
    server_id TEXT,
    conversation_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    content TEXT,
    status TEXT,
    created_at INTEGER
);
```

断网进入 App 后，可以直接从 SQLite 读取：

```text
最近会话列表
历史聊天记录
联系人缓存
未读数缓存
```

这样用户不会看到空白页面或被强制卡在登录页。

---

## 十一、断网消息发送设计

IM 系统中，断网时用户仍然可能发送消息。

推荐处理方式：

```text
用户点击发送
  ↓
生成 client_msg_id
  ↓
写入 SQLite
  ↓
消息状态 = PENDING
  ↓
UI 立即展示该消息
  ↓
网络恢复后自动重发
  ↓
服务端确认后状态 = SENT
```

消息状态可以设计为：

```kotlin
enum class MessageStatus {
    PENDING,   // 待发送
    SENDING,   // 发送中
    SENT,      // 已发送
    FAILED     // 失败，可重试
}
```

这样断网时用户体验更接近真实 IM App。

---

## 十二、WebSocket 与 Token 刷新流程

进入主页后，后台可以尝试建立 WebSocket：

```text
进入主页
  ↓
检查网络
  ↓
有网络 -> 使用 access_token 建立 WebSocket
  ↓
连接成功 -> 同步消息
  ↓
access_token 过期 -> 使用 refresh_token 刷新
  ↓
刷新成功 -> 重新连接 WebSocket
  ↓
刷新失败 -> 标记 SessionExpired
```

如果没有网络：

```text
进入主页
  ↓
读取本地会话和消息
  ↓
WebSocket 不连接
  ↓
UI 显示 Offline
  ↓
等待网络恢复后重连
```

---

## 十四、推荐整体架构

```text
MainActivity / AppStartup
  ↓
SessionStore 读取本地登录态
  ↓
判断是否存在本地 session
  ↓
没有 session
      -> LoginScreen
  ↓
有 session
      -> MainScreen
      -> 从 SQLite 加载会话列表
      -> 从 SQLite 加载消息记录
      -> AuthManager 后台校验 / 刷新 token
      -> WebSocketManager 后台连接
      -> MessageSyncManager 同步离线消息
```

可以拆成几个模块：

| 模块                     | 职责                    |
| ---------------------- | --------------------- |
| SessionStore           | 保存和读取本地 session、token |
| AuthManager            | 负责 token 校验、刷新、登录态维护  |
| WebSocketManager       | 负责长连接、心跳、重连           |
| MessageRepository      | 负责消息本地读写和同步           |
| ConversationRepository | 负责会话列表本地读写和同步         |
| NetworkMonitor         | 监听网络变化                |
| SyncManager            | 网络恢复后同步消息和状态          |

---

## 十五、最终总结

当前问题的根本原因是：

```text
App 启动时强依赖服务端 JWT 校验，导致断网场景下无法进入主界面。
```

改进方案是：

```text
将登录态判断拆成“本地会话存在性”和“服务端登录态有效性”两层。
```

启动时：

```text
只要本地存在 session，就允许进入主界面。
```

进入主界面后：

```text
从 SQLite 加载本地会话和聊天记录；
后台异步校验 token；
后台尝试刷新 access_token；
后台建立 WebSocket；
网络恢复后同步离线消息。
```

只有在以下情况才跳转登录页：

```text
本地没有 session
refresh_token 失效
账号被踢下线
账号被冻结
服务端明确要求重新登录
```

最终原则：

```text
本地 session 决定能不能进入 App；
SQLite 决定进入 App 后能展示什么；
服务端校验决定当前是否 Online；
WebSocket 决定是否可以实时通信；
断网只代表 Offline，不代表未登录。
```

---

## 实现落地点（2026-05-28 已完成）

本次实际代码改动集中在认证恢复链路：

```text
AuthRepository.restoreSession()
    -> 改为只做本地 session 恢复

AuthRepository.ensureValidSession()
    -> access_token 过期时尝试 refresh
    -> 若 refresh 因网络/服务异常失败，保留本地 session
    -> 若 refresh 明确失效，清理本地 session

OkHttpAuthApi
    -> 对网络失败、服务端失败、会话失效做基础分类
```

对应验证结果：

```text
AuthRepositoryTest
LoginViewModelTest
```

已通过，覆盖本次修复涉及的核心启动与恢复场景。
