# Bug: Failed Image Message Ordering

## Status

- Status: Reopened
- Reopened on: 2026-05-30
- Branch: current working branch

## Problem

When OSS is intentionally misconfigured, outgoing image upload fails and the
image message is saved locally as a failed message without `serverSeq`.

Observed behavior:

- The failed image bubble remains visible with the failure indicator.
- The failed image can jump to the top of the chat page, which represents the
  oldest visual position in the current chat UI.
- Later successfully sent messages can appear newer than the failed image only
  in some cases. The behavior is inconsistent because failed local messages do
  not have a server sequence.

Expected behavior:

- `UPLOADING` and `SENDING` messages may temporarily appear as the newest local
  message while waiting for upload or `MESSAGE_ACK`.
- Once a message reaches a terminal failed state, it should no longer use that
  temporary newest-message priority.
- Failed image messages without `serverSeq` should be placed by `createdAt`, so
  they stay near the time they were created instead of jumping to the oldest
  position.

## Analysis

Message ordering currently mixes two concepts:

1. Server-authoritative ordering through `serverSeq`.
2. Local optimistic ordering for in-progress outgoing messages.

The first fix removed terminal failed states from the optimistic local bucket:

- `UPLOAD_FAILED`
- `FAILED`

That solved one symptom: a failed upload no longer stayed permanently treated as
the newest message.

However, the remaining bug is caused by the fallback category used after that
change. Failed messages have no `serverSeq`, so they can fall into a lower
ordering group than all server-confirmed messages. In practice, that means a
newer failed image can still be sorted after older messages that have
`serverSeq`, which makes it render at the top/oldest side of the chat page.

There is also a second risk: if the in-memory ordering policy and the Android
SQLite query use separate ordering expressions, one path can be fixed while the
other still behaves incorrectly on device.

The ordering policy must therefore be one consistent display order:

- Active local messages without `serverSeq` and with status `UPLOADING` or
  `SENDING` are temporarily newest.
- Server-confirmed sent messages and received messages with `serverSeq` keep
  server-authoritative order by `serverSeq DESC`.
- Failed local messages without `serverSeq` are inserted into that timeline by
  `createdAt DESC`.
- `clientSeq` and `messageId` remain tie-breakers for stable ordering when
  timestamps or sequence values are equal.

This avoids treating terminal failed messages as either permanently newest or
permanently oldest.

## Solution

Update the message ordering policy as follows:

1. Keep temporary newest priority only for active local states:
   - `UPLOADING`
   - `SENDING`

2. Remove temporary newest priority from terminal failed states:
   - `UPLOAD_FAILED`
   - `FAILED`

3. Keep successful sent/received messages with `serverSeq` ordered by
   `serverSeq DESC`.

4. Insert terminal failed local messages without `serverSeq` by `createdAt DESC`
   instead of placing them before or after all server-confirmed messages as a
   separate bucket.

5. Use one shared ordering policy for both:
   - in-memory DAO / ViewModel merge paths
   - Android SQLite-backed DAO query results

6. Add regression tests for both directions:
   - older failed upload should not stay above a newer sent message
   - newer failed upload should not be pushed below an older sent message
   - server-confirmed messages keep `serverSeq DESC` while failed local messages
     are inserted by `createdAt`

## Affected Files

- `app/src/main/java/com/codex/im/storage/MessageOrderingPolicy.kt`
- `app/src/main/java/com/codex/im/storage/AndroidMessageDao.kt`
- `app/src/test/java/com/codex/im/storage/MessageDaoContractTest.kt`
- `app/src/androidTest/java/com/codex/im/storage/AndroidDaoInstrumentedTest.kt`

## Verification Plan

Run targeted unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.storage.MessageDaoContractTest --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests com.codex.im.chat.ChatViewModelTest --console=plain
```

Run Android DAO instrumentation when a device/emulator is available:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --tests com.codex.im.storage.AndroidDaoInstrumentedTest
```
