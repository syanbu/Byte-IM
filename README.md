# ByteIM

一个本地自研的 IM（Instant Messaging）项目，包含一个 **Android 客户端** 和一个配套的 **Java/Netty 后端**（源码在 `mock-server/`）。
项目的目标是自己在 Android 上把 IM 客户端的核心链路（鉴权、长连接、自定义二进制协议、心跳/重连、消息可靠投递、本地持久化、会话/群聊/图片消息/撤回/已读回执等）从头实现一遍，并配套实现一个独立运行的后端服务，提供账号、好友、群、消息收发、媒体上传等完整的本地联调能力。

模块与文档入口：

- 客户端源码：`app/`
- 后端源码：`mock-server/`
- 端到端测试 / 消息播种：`mock-test/`
- 详细功能状态与设计说明：`docs/`

## 项目组成

### Android 客户端（`app/`）

- 包名：`com.buyansong.im`
- 技术栈：Kotlin、Coroutines、Flow、Jetpack Compose、手写 `SQLiteOpenHelper`（**不**使用 Room，也**不**接入任何第三方 IM SDK）
- 主要能力：登录/注册、access/refresh token 与登录态恢复、单聊、会话列表与未读、本地历史分页、消息 ACK / 重试 / 去重 / `serverSeq` 排序、消息撤回、已读回执、群聊基础能力、图片消息（相册多选 + OSS 上传）、应用内消息 toast 弹窗
- 客户端通过 HTTP（`/login` `/register` 等）与 WebSocket（`/ws`）和后端通信

### 后端（`mock-server/`）

- 技术栈：Java 21、Netty 4、Maven
- 入口：`com.buyansong.imserver.MockImServer`，默认监听 `127.0.0.1:8080`
- 数据落盘：`mock-server/data/` 下的多个 SQLite 文件（用户、好友、群、消息、`serverSeq` 等），重启后状态不丢
- 鉴权：本地签发的 JWT access token（15 分钟）+ refresh token（7 天，SHA-256 哈希落库），HTTP 与 WebSocket `AUTH` 都校验 access token
- 媒体：内嵌一个轻量 `OssUploadService`，按阿里云 OSS 的规范为客户端签发头像 / 聊天图片的 **PUT 上传 URL**（客户端拿到后直传 OSS，不经过本服务），所有 OSS 配置都从环境变量读取

## 启动方式

下面分别说明 App 端和后端端的启动流程，**先启后端，再启 App**。

### 1. 启动后端（mock-server）

后端的启动脚本会**自动读取 OSS 配置文件**（见下文），所以本节和“后端要注意读取 OSS 配置文件的脚本”是一回事。

```bash
cd mock-server

# 第一次：拷贝 OSS 配置模板，并填入真实 AccessKey
cp .env.local.example.sh .env.local.sh
# 编辑 .env.local.sh，填入 ALIYUN_OSS_ACCESS_KEY_ID / ALIYUN_OSS_ACCESS_KEY_SECRET

# 启动后端（脚本内部会 source .env.local.sh，再执行 mvn compile exec:java）
./start-mock-server.sh
```

Windows PowerShell 环境下对应：

```powershell
cd mock-server
Copy-Item .env.local.example.ps1 .env.local.ps1
# 编辑 .env.local.ps1
.\start-mock-server.ps1
```

> 说明：`.env.local.sh` / `.env.local.ps1` 已在 `.gitignore` 中（不会被提交），里面保存的是真实的 AccessKey；提交仓库时**只**保留 `.env.local.example.*` 模板。脚本如果找不到 `.env.local.*` 会打印 `Warning: OSS env file not found` 警告，但仍然会启动——此时 `OssUploadService` 会因为没有 Key 而拒绝签发上传 URL，相关功能会返回 `OSS upload is not configured`。

可选：种子数据脚本也会读取同一份 OSS 配置文件，用于把演示账号预置成互为好友（确保它们的 AccessKey 已配置好）：

```bash
./seed-mock-friends.sh        # 或 PowerShell 下 .\seed-mock-friends.ps1
```

后端起来后默认监听：

- HTTP：`http://127.0.0.1:8080`
- WebSocket：`ws://127.0.0.1:8080/ws`

### 2. 配置 App 端的服务地址

App 的服务地址**不**写死在代码里，而是读取打包进 assets 的配置文件：

```
app/src/main/assets/mock-server.properties
```

文件本身是自文档化的——用注释把几种调试环境分成了三段，每段说明适用场景、操作步骤以及对应的 `host=` 写法。**只需要保留**你当前调试环境对应的那一行 `host=`，其余用 `#` 注释掉即可，`port=8080` 几乎不需要动。

当前文件内容如下（已按结构整理）：

```properties
# Server port
port=8080

# ------------------------------------------------------------
# Android real device over USB
# ------------------------------------------------------------
# Recommended for USB debugging.
#
# Step 1: Connect the phone with a USB cable.
# Step 2: Run this command on your computer:
#
#   adb reverse tcp:8080 tcp:8080
#
# Step 3: Use 127.0.0.1 as host.
#
# With adb reverse, requests from the phone to 127.0.0.1:8080
# will be forwarded to the computer's 127.0.0.1:8080.
#
# host=127.0.0.1

# ------------------------------------------------------------
# Android emulator
# ------------------------------------------------------------
# Use this when running the app in Android Emulator.
# 10.0.2.2 means "the host computer" from the emulator.
#
host=10.0.2.2

# ------------------------------------------------------------
# Android real device over Wi-Fi / wireless debugging
# ------------------------------------------------------------
# Use this when the phone and computer are on the same network.
# Replace the IP below with your computer's LAN IP.
#
#host=192.168.137.1
```

也就是说，三种场景对应三行 `host=`，**只留一行不注释**就行：

| 调试环境 | 把这一行打开（去掉行首 `#`） | 额外步骤 |
| --- | --- | --- |
| USB 真机（推荐） | `host=127.0.0.1` | 在电脑执行 `adb reverse tcp:8080 tcp:8080`，把手机的 `127.0.0.1:8080` 转发到电脑的 `127.0.0.1:8080` |
| Android 模拟器（AVD） | `host=10.0.2.2` | 无；`10.0.2.2` 在模拟器里就是宿主机 |
| 局域网真机 / 无线调试 | `host=<你电脑的局域网 IP>` | 确保手机和电脑在同一网段，且后端监听 `0.0.0.0` 或当前网卡；把 `192.168.137.1` 占位 IP 改成实际值 |

> 当前文件默认开启的是**模拟器**那一行（`host=10.0.2.2`）。换机器 / 换调试方式时务必把对应 `host=` 切换过来并重新构建 APK，否则 App 启动后会卡在连接 / 鉴权阶段。

### 3. 构建并安装 App

仓库是单模块 Android 项目（`app/`），使用 Gradle Wrapper：

```bash
# 单元测试
./gradlew :app:testDebugUnitTest --console=plain

# 打包 Debug APK
./gradlew :app:assembleDebug --console=plain
```

构建产物：

```
app/build/outputs/apk/debug/app-debug.apk
```

使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装到设备即可。

## 演示账号与登录约定

后端**不内置**任何账号——必须先注册再登录。账号规则是大陆手机号 + 密码（PBKDF2-SHA256 哈希落库，不存明文）。

下面这四个本地演示账号在 Android 客户端里互为联系人，方便多账号联调：

- `15000000000 / 123456`
- `15000000001 / 123456`
- `15000000002 / 123456`
- `15000000003 / 123456`

App 端的登录态由 access token（15 分钟）+ refresh token（7 天）维持：access token 有效就直接续用；只过期就在前台静默刷新；refresh token 也过期或被吊销就回到登录页。

## 后端 OSS 配置文件速查

后端读取的 OSS 环境变量名（来自 `mock-server/src/main/java/com/buyansong/imserver/oss/OssUploadService.java`）：

| 变量 | 含义 | 模板默认值 |
| --- | --- | --- |
| `ALIYUN_OSS_ACCESS_KEY_ID` | 阿里云 OSS AccessKey ID | 空（必填） |
| `ALIYUN_OSS_ACCESS_KEY_SECRET` | 阿里云 OSS AccessKey Secret | 空（必填） |
| `ALIYUN_OSS_ENDPOINT` | OSS endpoint | `oss-cn-shenzhen.aliyuncs.com` |
| `ALIYUN_OSS_BUCKET` | Bucket 名 | `im-byte` |
| `ALIYUN_OSS_PUBLIC_BASE_URL` | 公网可访问的 base URL | `https://im-byte.oss-cn-shenzhen.aliyuncs.com` |

被脚本加载的“读取 OSS 配置的脚本”有两个：

- `mock-server/start-mock-server.sh`（PowerShell 同名 `.ps1`）—— 启动主服务
- `mock-server/seed-mock-friends.sh`（PowerShell 同名 `.ps1`）—— 播种演示好友

两个脚本都在执行 Maven 之前 `source` 同一份 `.env.local.*`，请保持两个文件（bash / PowerShell）和实际环境一致。

## 端到端冒烟测试

`mock-test/` 目录下有一个 Python 脚本可以往后端播种本地消息，做端到端的联调冒烟：

```bash
python3 mock-test/seed_local_messages.py
```

## 文档导航

- 项目目标与背景：[docs/bg/ProjectTarget.md](docs/bg/ProjectTarget.md)、[docs/bg/ProjectBg.md](docs/bg/ProjectBg.md)
- 开发约束：[docs/bg/DEVELOPMENT-CONSTRAINTS.md](docs/bg/DEVELOPMENT-CONSTRAINTS.md)
- 各功能模块完成度：[docs/status/](docs/status/)
- 关键设计说明（如协议、消息可靠性、本地持久化）：[docs/feature-notes/](docs/feature-notes/)
- 产品需求原文：[prd/IM_PRODUCT_REQUIREMENTS_CN_EN.md](prd/IM_PRODUCT_REQUIREMENTS_CN_EN.md)
