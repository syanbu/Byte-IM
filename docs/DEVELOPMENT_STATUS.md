# IM Client Development Status

This file is the top-level progress dashboard for Project C in `docs/2026-engine.pdf`.
Detailed notes are split by feature under `docs/status/`.

Design roadmap: `docs/superpowers/plans/2026-05-21-im-client-roadmap.md`.

Development constraints: [`docs/DEVELOPMENT-CONSTRAINTS.md`](DEVELOPMENT-CONSTRAINTS.md).

## Current Progress

| Feature | Requirement | Status | Detail |
|---|---|---|---|
| Foundation | Android Kotlin project skeleton and local build setup | Done | [00-project-foundation.md](status/00-project-foundation.md) |
| B1 | Login/register, HTTP API, JWT token | Done | [B1-auth.md](status/B1-auth.md) |
| B2 | Single-chat text messages over WebSocket | Done | [B2-single-chat.md](status/B2-single-chat.md) |
| B3 | Conversation list: recent chats, unread count, last-message preview | Done | [B3-conversation-list.md](status/B3-conversation-list.md) |
| B4 | History message pagination, pull/load more | Partial | [B4-history-pagination.md](status/B4-history-pagination.md) |
| B5 | Message persistence with SQLite, no Room | Done | [B5-local-persistence.md](status/B5-local-persistence.md) |
| B6 | Custom binary protocol with header, body, CRC | Done | [B6-binary-protocol.md](status/B6-binary-protocol.md) |
| B7 | Heartbeat and reconnect | Not started | [B7-heartbeat-reconnect.md](status/B7-heartbeat-reconnect.md) |
| B8 | Message ordering with client seq / server ACK | Partial | [B8-message-ordering.md](status/B8-message-ordering.md) |
| B9 | Reliability: ACK, retry, deduplication | Partial | [B9-message-reliability.md](status/B9-message-reliability.md) |
| Mock server | Local Netty server for auth and WebSocket tests | Done for current B1/B2 path | [mock-server.md](status/mock-server.md) |
| B10-B13 | Group chat, image messages, recall/read receipts, push | Deferred | Later optional scope |

## Recent Self-Design Work

These items are recent product/UI improvements that sit outside the original B-prefixed roadmap.

| Feature | Scope | Status | Detail |
|---|---|---|---|
| Chat back navigation | Navigation Compose route stack for `Conversations -> Chat -> Conversations`; chat top Back, Android system Back, and gesture Back return to the conversation list. | Implemented | [self-design-chat-back-navigation-status.md](status/self-design-chat-back-navigation-status.md), [self-design-chat-back-navigation-development-plan.md](status/self-design-chat-back-navigation-development-plan.md) |
| Chat UI interaction cleanup | Removed noisy chat row/status text and history-end text from the chat UI interaction pass. | Implemented | [self-design-chat-ui-interaction-status.md](status/self-design-chat-ui-interaction-status.md) |
| Profile edit and avatar upload | Edit nickname/avatar from `Me`, compress selected avatar image, request OSS upload target, upload avatar bytes, and persist profile updates. | Implemented; emulator/real OSS verification still recommended | [self-desgin-profile-edit-development-status.md](status/self-desgin-profile-edit-development-status.md) |
| Profile-aware chat UI | Added Messages/Me tabs, local profile cache, peer nicknames/avatars in conversations and chat, vector tab icons, avatar caching, chat composer polish, WeChat-style profile detail flow, and unified profile Back semantics. | Implemented; manual UI checklist still recommended | [self-desgin-profile-chat-ui-development-status.md](status/self-desgin-profile-chat-ui-development-status.md) |

## Next Step

Continue B4 only if server-backed history is required; the local chat history pagination path is implemented.

For the recent self-design work, the next step is manual emulator verification of profile/avatar flows, chat visual behavior, vector tab icons, and Back semantics against the checklist in [self-desgin-profile-chat-ui-development-status.md](status/self-desgin-profile-chat-ui-development-status.md).

B3 is now complete for the current local single-chat scope:

- `ConversationListViewModel` and `ConversationListScreen` exist.
- Login/session restore routes to the conversation list before chat.
- Conversation rows show peer id/name, preview, last time, and unread count.
- Tapping a conversation opens `ChatScreen`.
- Entering a conversation clears unread.
- Incoming messages for the currently open conversation refresh the preview without incrementing unread.
- Conversation rows refresh from Repository conversation update events, including messages processed by the chat screen.
- A fixed mock contact entry remains available for the two local demo accounts.
- Connection/auth status and logout are only shown on the conversation list; the chat screen keeps only the back navigation.

B4 local history pagination is implemented for the current SQLite-backed chat path:

- `ChatViewModel.start()` initially loads the latest 20 local messages.
- `ChatViewModel.loadMoreHistory()` uses the earliest visible `createdAt` as a `beforeTime` cursor and loads 20 earlier local messages.
- Loaded pages are merged by `messageId`, preserving the existing newest-first list used by the current `reverseLayout` chat UI.
- Realtime incoming messages refresh the visible list without dropping already loaded history.
- `ChatUiState` exposes `isLoadingMore`, `hasMoreLocal`, and `errorMessage`.
- `ChatScreen` automatically loads earlier local history when the user scrolls within 6 items of the older-message end of the list.
- The chat ViewModel keeps a grow-only in-memory history cache for the current chat session, capped at 2,000 messages as a safety guard.

## Completed

- B1 auth and token management are complete against the local mock server.
- B2 single-chat real-time send/receive is complete for the local two-client demo path.
- B4 local history pagination is complete for SQLite-backed offline history.
- B5 SQLite persistence foundation is complete.
- B6 binary protocol codec is complete and documented in `docs/WEBSOCKET_PROTOCOL_AND_STATES.md`.
- Local Java/Netty mock server supports the current auth and single-chat WebSocket path.
- Self-design chat/profile UI work is implemented, including Messages/Me tabs, profile display/edit, avatar upload plumbing, peer nickname/avatar display, vector tab icons, avatar caching, chat composer polish, and corrected Back semantics.
- Project-level development constraints are documented in `docs/DEVELOPMENT-CONSTRAINTS.md`.

## In Progress

- B4 server-backed history is not wired yet. The protocol commands `HISTORY_QUERY` and `HISTORY_RESULT` remain the intended integration path if remote history fetch is added.
- Manual emulator verification remains for the latest self-design profile/chat UI slice.

## Not Started

- B7 client heartbeat and reconnect manager.
- B8 full receive-side ordering/reorder behavior.
- B9 retry loop and failure-state handling.
- Phase 10 performance, packet capture, and stability evidence.
