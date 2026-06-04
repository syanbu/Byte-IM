# Fix Account Switch Data Stream Isolation

## Problem

When the user logs out of account A and then logs into account B, local IM data is not isolated by account.

Observed risk:

- B can see A's local conversation rows and unread counts.
- B's authenticated WebSocket session can scan and retry A's pending outbox records.
- Incoming packet persistence does not have a clear current-account storage boundary.

## Evidence

- `MainActivity` creates one Activity-level SQLite database and one Activity-level `MessageRepository`.
- Logout closes the active conversation, disconnects WebSocket, and clears auth tokens, but it does not clear or switch the local message database.
- `messages`, `conversations`, and `pending_messages` do not include an owner account column.
- `ConversationDao.listConversations()` and `ConversationDao.totalUnreadCount()` read all local conversations.
- `PendingMessageDao.dueMessages()` scans all pending rows, so a new account can retry rows created by a previous account.

## Root Cause

Authentication state is account-scoped, but local IM persistence is app-scoped.

The WebSocket connection is stopped on logout, so token cleanup is mostly correct. The leak comes from durable and in-memory message infrastructure continuing to point at the same SQLite database across account switches.

## Chosen Fix

Use account-scoped local IM storage.

For each authenticated `userId`, create a separate SQLite database name and construct session-scoped message, conversation, pending-message, group, and profile repositories from that database. Logging out stops the session-level packet processor/outbox worker and disconnects the WebSocket. Logging into another account creates a different local storage boundary.

This preserves A's local history for the next A login, while preventing B from reading or retrying A's local data.

## Alternative Considered

Add `owner_user_id` to each local table and require every DAO query/update to include the owner.

This is also valid and may be preferable for complex multi-account features later, but it is a wider migration and API change. The account-scoped database approach gives the same practical isolation for the current single-active-account client.

## Verification Plan

- Add a JVM test for deterministic account-scoped database naming.
- Run Android unit tests for affected app/storage/message code.
- Update B5 local persistence status to document account-scoped SQLite storage.
