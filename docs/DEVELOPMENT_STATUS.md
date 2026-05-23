# IM Client Development Status

This file is the top-level progress dashboard for Project C in `docs/2026-engine.pdf`.
Detailed notes are split by feature under `docs/status/`.

Design roadmap: `docs/superpowers/plans/2026-05-21-im-client-roadmap.md`.

## Current Progress

| Feature | Requirement | Status | Detail |
|---|---|---|---|
| Foundation | Android Kotlin project skeleton and local build setup | Done | [00-project-foundation.md](status/00-project-foundation.md) |
| B1 | Login/register, HTTP API, JWT token | Done | [B1-auth.md](status/B1-auth.md) |
| B2 | Single-chat text messages over WebSocket | Done | [B2-single-chat.md](status/B2-single-chat.md) |
| B3 | Conversation list: recent chats, unread count, last-message preview | Done | [B3-conversation-list.md](status/B3-conversation-list.md) |
| B4 | History message pagination, pull/load more | Not started | [B4-history-pagination.md](status/B4-history-pagination.md) |
| B5 | Message persistence with SQLite, no Room | Done | [B5-local-persistence.md](status/B5-local-persistence.md) |
| B6 | Custom binary protocol with header, body, CRC | Done | [B6-binary-protocol.md](status/B6-binary-protocol.md) |
| B7 | Heartbeat and reconnect | Not started | [B7-heartbeat-reconnect.md](status/B7-heartbeat-reconnect.md) |
| B8 | Message ordering with client seq / server ACK | Partial | [B8-message-ordering.md](status/B8-message-ordering.md) |
| B9 | Reliability: ACK, retry, deduplication | Partial | [B9-message-reliability.md](status/B9-message-reliability.md) |
| Mock server | Local Netty server for auth and WebSocket tests | Done for current B1/B2 path | [mock-server.md](status/mock-server.md) |
| B10-B13 | Group chat, image messages, recall/read receipts, push | Deferred | Later optional scope |

## Next Step

Implement B4 history message pagination as the next user-visible feature.

B3 is now complete for the current local single-chat scope:

- `ConversationListViewModel` and `ConversationListScreen` exist.
- Login/session restore routes to the conversation list before chat.
- Conversation rows show peer id/name, preview, last time, and unread count.
- Tapping a conversation opens `ChatScreen`.
- Entering a conversation clears unread.
- Incoming messages for the currently open conversation refresh the preview without incrementing unread.
- A fixed mock contact entry remains available for the two local demo accounts.

## Completed

- B1 auth and token management are complete against the local mock server.
- B2 single-chat real-time send/receive is complete for the local two-client demo path.
- B5 SQLite persistence foundation is complete.
- B6 binary protocol codec is complete and documented in `docs/WEBSOCKET_PROTOCOL_AND_STATES.md`.
- Local Java/Netty mock server supports the current auth and single-chat WebSocket path.

## In Progress

- B4 history pagination is the next active feature.

## Not Started

- B4 history pagination.
- B7 client heartbeat and reconnect manager.
- B8 full receive-side ordering/reorder behavior.
- B9 retry loop and failure-state handling.
- Phase 10 performance, packet capture, and stability evidence.
