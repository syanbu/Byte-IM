# docs 目录说明

本文是 `docs/` 目录的总索引，用于说明各子目录和文档类型的职责。当前最终状态应优先参考 `bg/DEVELOPMENT_STATUS.md` 和 `status/`；`superpowers/plans/`、`superpowers/specs/` 和部分 bug 文档记录的是历史设计、计划或修复过程。

## `bg/`

项目背景、原始目标和总进度索引。

- [`bg/PROJECT_TARGET.md`](bg/PROJECT_TARGET.md)：项目原始目标和 B1-B13 功能需求。
- [`bg/PROJECT_BACKGROUND.md`](bg/PROJECT_BACKGROUND.md)：当前项目背景、已实现能力概览和仍需验证的边界。
- [`bg/DEVELOPMENT_STATUS.md`](bg/DEVELOPMENT_STATUS.md)：功能模块到 `status/` 文档的总索引，也是当前开发状态的首要入口。

说明：当前 `bg/` 下没有独立的 `DEVELOPMENT-CONSTRAINTS.md` 文件。项目约束以 `PROJECT_TARGET.md`、`PROJECT_BACKGROUND.md`、`status/` 和 `feature-notes/` 中的具体说明为准。

## `status/`

功能模块的实现状态记录。

这里的文档主要回答：

- 这个功能当前做到什么程度。
- 哪些范围已经实现。
- 哪些范围尚未接入、仍需完善或需要人工验证。
- 相关实现边界、风险和验证记录是什么。

典型文档：

- [`status/B1-auth.md`](status/B1-auth.md)
- [`status/B10-group-chat-and-mention.md`](status/B10-group-chat-and-mention.md)
- [`status/B12-message-recall-and-read-receipts.md`](status/B12-message-recall-and-read-receipts.md)
- [`status/B13-mock-push.md`](status/B13-mock-push.md)
- [`status/message-toast-popup.md`](status/message-toast-popup.md)
- [`status/mock-server.md`](status/mock-server.md)

其中若文件名或标题标记为 `Superseded`，表示该文档只保留历史上下文，不代表当前最终状态。

## `feature-notes/`

功能说明和设计说明。

这里的文档主要回答：

- 某个功能为什么这样设计。
- 数据结构、协议、状态流和 UI 行为如何协作。
- 当前实现背后的关键语义是什么。

当前重点文档包括：

- [`feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)：WebSocket 协议命令与 Android 本地连接状态说明。
- [`feature-notes/messages-conversation-summary-and-unread.md`](feature-notes/messages-conversation-summary-and-unread.md)：会话摘要、未读数和 Messages tab 角标设计。
- [`feature-notes/B4-history-pagination-design-notes.md`](feature-notes/B4-history-pagination-design-notes.md)：本地历史分页设计。
- [`feature-notes/B5.5-mock-server-message-persistence.md`](feature-notes/B5.5-mock-server-message-persistence.md)：mock-server accepted message 持久化和恢复。
- [`feature-notes/B9.5-server-delivery-state-structures.md`](feature-notes/B9.5-server-delivery-state-structures.md)：服务端投递状态结构。
- [`feature-notes/B12-message-recall-and-read-receipts-design.md`](feature-notes/B12-message-recall-and-read-receipts-design.md)：撤回与已读回执设计。
- [`feature-notes/B13-mock-push.md`](feature-notes/B13-mock-push.md)：mock push 实现与手测说明。
- [`feature-notes/coil-image-preload-mechanism.md`](feature-notes/coil-image-preload-mechanism.md)：聊天图片预加载机制。
- [`feature-notes/user-profile-version-cache.md`](feature-notes/user-profile-version-cache.md)：用户资料版本与缓存刷新机制。

## `bug/`

问题修复记录。

这里的文档通常记录问题现象、根因、修复方案和验证结果。文件名已统一为：

```text
Fix-<EnglishPascalCase>.md
```

示例：

- [`bug/Fix-RefreshTokenRotation.md`](bug/Fix-RefreshTokenRotation.md)
- [`bug/Fix-ChatInitialBlankScreenOptimization.md`](bug/Fix-ChatInitialBlankScreenOptimization.md)
- [`bug/Fix-ChatTimeDisplayInconsistency.md`](bug/Fix-ChatTimeDisplayInconsistency.md)
- [`bug/Fix-RecallNotifyNotDurablyRedelivered.md`](bug/Fix-RecallNotifyNotDurablyRedelivered.md)
- [`bug/Fix-WebSocketAuthFailureAndUnauthenticatedMessageHandling.md`](bug/Fix-WebSocketAuthFailureAndUnauthenticatedMessageHandling.md)

## `superpowers/specs/`

较新的功能规格文档。

通常用于记录某个具体体验或功能切片的目标、交互、验收标准和边界。它们是设计过程产物，适合追溯“当时为什么这样设计”，但当前状态仍应以 `bg/DEVELOPMENT_STATUS.md` 和 `status/` 为准。

## `superpowers/plans/`

可执行开发计划。

通常用于记录按任务拆分的实现步骤、测试步骤和提交步骤。它们是历史开发计划，不一定代表当前最终状态；当前状态以 `status/`、`feature-notes/` 和 `bg/DEVELOPMENT_STATUS.md` 为准。

## 阅读建议

如果只是了解项目，建议按下面顺序阅读：

1. [`bg/PROJECT_TARGET.md`](bg/PROJECT_TARGET.md)
2. [`bg/PROJECT_BACKGROUND.md`](bg/PROJECT_BACKGROUND.md)
3. [`bg/DEVELOPMENT_STATUS.md`](bg/DEVELOPMENT_STATUS.md)
4. [`feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)
5. 需要了解具体模块时，再进入 `status/`、`feature-notes/` 或 `bug/`
