# ByteIM / SelfHostedIM

ByteIM 是一个本地自研 IM 项目，包含 Android 客户端和 Java/Netty mock server。项目目标不是接入成熟 IM 云服务，而是在 Android 端完整实现一套即时通讯客户端的核心链路：登录鉴权、长连接、自定义协议、心跳重连、消息可靠性、本地持久化、会话列表、历史分页、群聊、图片消息、撤回、已读回执和 mock 推送。

项目明确不使用现成 IM SDK，也不使用 Room。Android 端使用 Kotlin、Coroutines、Flow、Jetpack Compose 和手写 `SQLiteOpenHelper`；服务端使用 Java 21、Netty、Maven 和 SQLite，作为本地联调、协议验证和自动化验证环境。

## 项目目标

原始目标见 [docs/bg/PROJECT_TARGET.md](docs/bg/PROJECT_TARGET.md)。它定义了 13 个功能方向：

| 编号 | 功能 | 当前状态 |
| --- | --- | --- |
| B1 | 登录 / 注册 | 已完成。支持 HTTP 注册登录、JWT access token、refresh token 轮换、登录态恢复和登出清理。 |
| B2 | 单聊文本消息 | 已完成。通过 WebSocket 实时收发单聊文本消息。 |
| B3 | 会话列表 | 已完成。支持最近会话、最后消息预览、最后消息时间、未读数和进入会话清未读。 |
| B4 | 历史消息分页 | 已完成。本地 SQLite 历史分页已完成，服务端历史查询还未完全完成。 |
| B5 | 本地消息持久化 | 已完成。消息、会话、pending outbox、profile、group 等数据使用手写 SQLite 持久化。 |
| B6 | 自定义二进制协议 | 已完成。WebSocket 消息统一走 header/body/CRC 的自定义协议帧。 |
| B7 | 心跳与断线重连 | 已完成。支持前后台心跳策略、心跳 ACK、指数退避重连和连接状态展示。 |
| B8 | 消息有序性 | 已完成。服务端按会话分配 `serverSeq`，客户端以 `serverSeq` 作为权威排序键。 |
| B9 | 消息可靠性 | 已完成。支持 ACK、超时重试、失败状态和 `messageId` 幂等去重。 |
| B10 | 群聊 + @ 提醒 | 部分完成。支持服务端建群、群消息收发、群成员存储、@ 元数据和 @ 我未读计数，完整群管理体验仍待收敛。 |
| B11 | 图片消息 | 已实现。支持图库选择、多图拆分发送、OSS upload-target、上传/发送失败分层、缩略图缓存和渐进式展示。 |
| B12 | 消息撤回 / 已读回执 | 已实现。支持单聊已读回执、群聊已读人数/读者列表、撤回状态持久化和 UI 展示。 |
| B13 | 推送 | 已实现完整的 mock 推送并验证。 |

在 B 系列之外，项目还补充了 Profile/Chat 自设计 UI、Messages/Contacts/Me 顶层 tab、资料展示与编辑、头像上传、联系人入口、聊天输入栏优化、统一返回语义和应用内顶部消息弹窗。

## 功能概览

- 账号体系：手机号注册登录，access token 与 refresh token 分离，refresh token rotation 防复用。
- 长连接：OkHttp WebSocket 连接 mock server，WebSocket 鉴权后进入 IM 收发状态。
- 协议层：自定义二进制协议帧，区分协议命令和 Android 本地连接状态。
- 消息收发：支持单聊、群聊、文本消息、图片消息、撤回、已读和投递确认。
- 可靠性：sender-side `MESSAGE_ACK`、receiver-side `DELIVERY_ACK`、超时重试、失败标记、幂等去重和离线重放。
- 排序：客户端生成本地 `clientSeq` 用于关联，服务端 `serverSeq` 作为会话内最终排序依据。
- 存储：Android 使用 `SQLiteOpenHelper` 手写 DAO，服务端使用 SQLite 保存用户、好友、群、消息和投递状态。
- 会话：会话列表展示最近聊天、未读数、最后消息预览和总未读角标。
- 历史：聊天页支持本地分页加载历史消息，服务端历史查询协议已预留。
- 体验：Compose UI 包含登录、消息、联系人、我的、资料编辑、聊天页、群聊和消息顶部弹窗。
- 媒体：服务端签发 OSS PUT 上传 URL，客户端直传头像和聊天图片。
- 推送：使用 mock token、服务端 pending push 和 Android WorkManager 轮询模拟后台推送流程。

## 项目结构

```text
.
├── app/                 # Android 客户端，包名 com.buyansong.im
├── mock-server/         # Java/Netty 本地联调服务
├── mock-test/           # 本地消息播种和端到端冒烟脚本
├── docs/                # 项目目标、开发状态、设计说明和问题修复记录
└── README.md            # 当前项目说明
```

## 技术栈

Android 客户端：

- Kotlin
- Jetpack Compose / Material3
- Navigation Compose
- Coroutines / Flow / Channel
- OkHttp WebSocket
- Gson
- Coil
- WorkManager
- 手写 `SQLiteOpenHelper`

Mock server：

- Java 21
- Netty 4
- Maven
- SQLite JDBC
- Gson
- 本地 JWT / refresh token 管理
- 阿里云 OSS upload-target 签发

## 启动方式

建议先启动 mock server，再启动 Android App。

### 1. 启动 mock server

Windows PowerShell：

```powershell
cd mock-server
Copy-Item .env.local.example.ps1 .env.local.ps1
# 编辑 .env.local.ps1，填入 OSS 配置；不配置也能启动，但头像/图片上传会不可用
.\start-mock-server.ps1
```

macOS / Linux：

```bash
cd mock-server
cp .env.local.example.sh .env.local.sh
# 编辑 .env.local.sh，填入 OSS 配置；不配置也能启动，但头像/图片上传会不可用
./start-mock-server.sh
```

默认服务地址：

- HTTP：`http://127.0.0.1:8080`
- WebSocket：`ws://127.0.0.1:8080/ws`

### 2. 配置 App 访问地址

App 从下面的 assets 文件读取 mock server 地址：

```text
app/src/main/assets/mock-server.properties
```

常见调试方式：

| 环境 | `host=` 配置 | 额外步骤 |
| --- | --- | --- |
| Android 模拟器 | `host=10.0.2.2` | 无，`10.0.2.2` 指向宿主机。 |
| USB 真机 | `host=127.0.0.1` | 先执行 `adb reverse tcp:8080 tcp:8080`。 |
| 局域网真机 | `host=<电脑局域网 IP>` | 手机和电脑需在同一网段。 |

当前配置默认面向 Android 模拟器。

### 3. 构建 App

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到设备：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. 播种演示好友

mock server 不内置账号，需要先注册再登录。若要使用本地演示账号和好友关系，可以在 mock server 目录运行播种脚本：

```powershell
cd mock-server
.\seed-mock-friends.ps1
```

脚本使用的演示账号：

```text
15000000000 / 123456
15000000001 / 123456
15000000002 / 123456
15000000003 / 123456
```

## 关键语义

- `MESSAGE_ACK` 表示服务端已接收发送方消息并分配 `serverSeq`，不表示接收方已投递或已读。
- `DELIVERY_ACK` 表示接收方设备已经把消息写入本地库，不表示用户已读。
- `READ_ACK` 才表示已读回执。
- `serverSeq` 是同一会话内 confirmed/received 消息的权威排序键。
- `clientSeq` 只用于本地关联和 ACK correlation，不能替代服务端排序。
- UI 页面只渲染 repository/ViewModel 暴露的状态，不把页面状态作为 IM 持久事实来源。
- 登录后的消息接收由会话级处理器统一完成，不能依赖某个聊天页是否正在显示。

## 文档导航

- 文档总索引：[docs/readme.md](docs/readme.md)
- 原始项目目标：[docs/bg/PROJECT_TARGET.md](docs/bg/PROJECT_TARGET.md)
- 当前项目背景：[docs/bg/PROJECT_BACKGROUND.md](docs/bg/PROJECT_BACKGROUND.md)
- 开发状态索引：[docs/bg/DEVELOPMENT_STATUS.md](docs/bg/DEVELOPMENT_STATUS.md)
- 协议说明：[docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md](docs/feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)
- 模块状态：[docs/status/](docs/status/)
- 功能设计说明：[docs/feature-notes/](docs/feature-notes/)
- 修复记录：[docs/bug/](docs/bug/)

## 项目重点

这个项目的核心不是聊天界面本身，而是自研 IM 客户端的底层能力。重点在于客户端自己维护协议、连接生命周期、心跳重连、ACK、重试、去重、排序、本地 SQLite 持久化、会话/未读状态和消息 UI 展示，并用本地 mock server 提供可验证的服务端协作环境。
