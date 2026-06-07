# Fix: Login After Reconnect Backoff Delayed WebSocket Auth

## Status

- Status: Completed
- Completed on: 2026-06-06
- Branch: current working branch

## Problem Summary

新虚拟机打开 app 后，用户还没有完成登录时，客户端连接状态可能已经进入了退避重连流程。登录成功进入消息页后，UI 仍显示：

```text
连接状态：30 秒后重连
```

服务端也不是立刻收到当前用户的 WebSocket `AUTH`，而是等到退避倒计时结束后才收到认证，例如：

```text
2026-06-06 10:47:48.022 [IM] STATUS AUTHENTICATED userId=15000000001 authAck=sent
2026-06-06 10:47:48.023 [IM] RECEIVE_MESSAGE delivered queued receiver=15000000001 messageId=15000000000-1780713939487-000001 serverSeq=1780713939581
```

直观上看，像是“还在登录页时就触发了 30 秒退避重连”。更准确地说，是启动/登录前阶段留下的 `ConnectionState.Reconnecting(...)` 没有在登录成功后被当前 session 主动刷新。

## Root Cause

`ConversationListViewModel.start()` 会调用 `connectIfNeeded()` 来建立 WebSocket。

旧逻辑只在这些状态下主动连接：

- `Disconnected`
- `Failed`

但是当连接状态已经是 `Reconnecting(30_000, "token unavailable")` 时，旧逻辑选择不做任何事：

```kotlin
is ConnectionState.Reconnecting -> Unit
```

这导致登录成功后的消息页没有立即用新登录 session 发起 `connection.connect(session.token)`。客户端继续等待旧的退避倒计时，服务端只能在 30 秒后下一次重连尝试时才收到 `AUTH`。

## Implemented Fix Summary

`ConversationListViewModel.connectIfNeeded()` 现在把 `Reconnecting` 也视为需要立即连接的状态：

```kotlin
ConnectionState.Disconnected,
is ConnectionState.Failed,
is ConnectionState.Reconnecting -> connection.connect(session.token)
```

`ConnectionLifecycleManager.connect(...)` 会取消已有的 `reconnectJob`，重置退避策略，并立刻走当前 access token 的 WebSocket 认证流程。

## Result

登录成功进入消息页后，如果之前已经处在 30 秒退避重连状态，客户端会立即打断旧 backoff 并发送当前用户的 `AUTH`。

预期结果：

- 不再在刚登录后继续显示旧的 `30 秒后重连`。
- 服务端应立即收到当前用户的 WebSocket `AUTH`。
- 离线消息可以在 `AUTH_ACK` 后马上下发。

## Verification Result

Verified on 2026-06-06:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.conversation.ConversationListViewModelTest --tests com.buyansong.im.connection.ConnectionLifecycleManagerTest --console=plain
```

Result:

- `BUILD SUCCESSFUL`
- Added coverage: `ConversationListViewModelTest.startReconnectsImmediatelyWhenPreviousBackoffIsActive`
- Existing connection lifecycle reconnect tests passed.

## Related Files

- `app/src/main/java/com/buyansong/im/conversation/ConversationListViewModel.kt`
- `app/src/test/java/com/buyansong/im/conversation/ConversationListViewModelTest.kt`
- `docs/bug/Fix-StartupServerOfflineTokenUnavailableReconnect.md`
