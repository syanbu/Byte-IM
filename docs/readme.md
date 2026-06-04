# docs 目录说明

本文是 `docs/` 目录的总索引，用于说明各子目录和文档类型的职责。

## `bg/`

项目背景、目标、开发约束和总进度索引。

- [`bg/ProjectTarget.md`](bg/ProjectTarget.md)：项目原始目标和功能需求。
- [`bg/ProjectBg.md`](bg/ProjectBg.md)：当前项目背景和已实现能力概览。
- [`bg/DEVELOPMENT_STATUS.md`](bg/DEVELOPMENT_STATUS.md)：功能模块到 status 文档的总索引。
- [`bg/DEVELOPMENT-CONSTRAINTS.md`](bg/DEVELOPMENT-CONSTRAINTS.md)：开发约束和架构语义约束。

## `status/`

功能模块的实现状态记录。

这里的文档主要回答：

- 这个功能当前做到什么程度。
- 哪些范围已经实现。
- 哪些范围仍然 deferred 或有风险。
- 相关测试和验证记录是什么。

典型文档：

- [`status/B1-auth.md`](status/B1-auth.md)
- [`status/B10-group-chat-and-mention.md`](status/B10-group-chat-and-mention.md)
- [`status/B12-message-recall-and-read-receipts.md`](status/B12-message-recall-and-read-receipts.md)
- [`status/message-toast-popup.md`](status/message-toast-popup.md)

## `feature-notes/`

功能说明和设计说明。

这里的文档主要回答：

- 某个功能为什么这样设计。
- 数据结构、协议、状态流和 UI 行为如何协作。
- 当前实现背后的关键语义是什么。

当前包含：

- [`feature-notes/B4-history-pagination-design-notes.md`](feature-notes/B4-history-pagination-design-notes.md)
- [`feature-notes/B5.5-mock-server-message-persistence.md`](feature-notes/B5.5-mock-server-message-persistence.md)
- [`feature-notes/B9.5-server-delivery-state-structures.md`](feature-notes/B9.5-server-delivery-state-structures.md)
- [`feature-notes/B12-message-recall-and-read-receipts-design.md`](feature-notes/B12-message-recall-and-read-receipts-design.md)
- [`feature-notes/messages-conversation-summary-and-unread.md`](feature-notes/messages-conversation-summary-and-unread.md)
- [`feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md`](feature-notes/WEBSOCKET_PROTOCOL_AND_STATES.md)

## `superpowers/specs/`

较新的功能规格文档。

通常用于记录某个具体体验或功能切片的目标、交互、验收标准和边界。

## `superpowers/plans/`

可执行开发计划。

通常用于记录按任务拆分的实现步骤、测试步骤和提交步骤。它们是历史开发计划，不一定代表当前最终状态；当前状态以 `status/` 和 `bg/DEVELOPMENT_STATUS.md` 为准。

## `bug/`

问题修复记录。

通常记录问题现象、根因、修复方案和验证结果。

