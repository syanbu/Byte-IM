# Plan: Multi-Image Send — Show All UPLOADING Thumbnails in Parallel

## Context

**Bug (issue #2 in user request).** When a user picks N images in the self-built album
picker (`AlbumPickerScreen`) and taps Send, the chat surface shows only image #1's
thumbnail with a row-level spinner first. After image #1 fully uploads, image #2's
thumbnail appears and only then begins uploading. The user observes: "the first
image always sends successfully, then the subsequent thumbnails appear and start
uploading one by one" — the opposite of the expected "all N thumbnails visible
immediately, each spinner disappearing independently as its own upload finishes."

**Root cause.** `ChatViewModel.sendImages` runs a sequential `forEachIndexed` over
`suspend fun sendImage(...)`. Each call to `sendImage` does, in order:

1. `createLocalImageMessage(...)` — writes one `UPLOADING` row to SQLite.
2. `refreshKeepingHistory()` — re-queries the conversation page and emits to Compose.
3. `uploadImageAndQueueSend(...)` — the full blocking network pipeline (request
   upload targets → PUT thumbnail → PUT original → `completeImageUploadAndQueueSend`
   which marks SENDING and sends the WebSocket packet).
4. `refreshKeepingHistory()` again.

So the loop's iteration N+1 cannot even *insert* its UPLOADING row until
iteration N has finished its entire upload. The chat list literally cannot show
image #2 until image #1 has gone all the way to SENT/UPLOAD_FAILED.

**What we already have that makes this fix small.** The row-level
`CircularProgressIndicator` for `MessageStatus.UPLOADING` / `SENDING` is already
wired in `OutgoingMessageStatus` ([`ChatScreen.kt:905-943`](app/src/main/java/com/codex/im/chat/ChatScreen.kt#L905-L943))
and is policy-free. The bubble-internal overlay is intentionally locked off by
[`ChatImageBubbleLoadingPolicy`](app/src/main/java/com/codex/im/chat/ChatImageBubbleLoadingPolicy.kt)
and its unit test. The fix is therefore a server-/ViewModel-side restructuring,
not a UI redesign.

**Outcome we want.** When the user taps Send with N images:

- All N `UPLOADING` rows appear in the chat within a single refresh cycle
  (i.e., before any upload has started).
- Each row shows its own `CircularProgressIndicator` (already implemented).
- N independent upload coroutines run in parallel; each `markStatus` transition
  (UPLOADING → SENDING → SENT, or → UPLOAD_FAILED) emits its own UI update.
- The existing `MAX_IMAGES_PER_SEND = 9` cap is preserved.
- `clientSeq` stays strictly increasing per `conversationId`; `messageId` stays
  unique.

**B11 architecture is preserved.** This is a behavior fix, not a redesign. We
keep the two-phase (upload → IM send) reliability split, the row-level spinner
policy, the album picker, and the retry path.

## Approach

Restructure `ChatViewModel.sendImages` into three explicit phases inside a single
`coroutineScope { ... }` so a failure in one launch cannot cancel its siblings
(combined with the existing `SupervisorJob` on the ViewModel scope):

1. **Serialize allocation + insert (single coroutine).** Pre-allocate
   `messageId` (via `MessageIdGenerator.next`) and `clientSeq` (via
   `SeqGenerator.next`) one at a time on the calling coroutine, then build N
   `ChatMessage` instances, then run all N inserts inside **one**
   `transactionRunner.runInTransaction { ... }` block via a new
   `MessageRepository.createLocalImageMessages(...)` batch API. This avoids the
   `MessageIdGenerator` / `SeqGenerator` read-modify-write race (they are plain
   `var counter` and `mutableMap`, not thread-safe — see Risks below) and avoids
   N separate `notifyConversationChanged()` emissions.
2. **Single `refreshKeepingHistory()`.** After the transaction returns, call it
   exactly once. The chat surface now has N `UPLOADING` rows, each with its own
   row-level spinner.
3. **Parallel fan-out.** `coroutineScope { inserted.forEach { msg -> launch {
   uploadImageAndQueueSend(msg.messageId, selectedImage) } } }`. The success
   and failure paths inside `uploadImageAndQueueSend` already call
   `notifyConversationChanged()` (via `completeImageUploadAndQueueSend` line 265
   and `markImageUploadFailed` line 300 of `MessageRepository.kt`), so each
   per-row state flip produces its own independent UI update.

Capture `val batchNow = System.currentTimeMillis()` at the top of `sendImages`
and pass `batchNow + index` into each row's `createdAt`/`now` so the 9
`createdAt` values cluster and `MessageIdGenerator` produces a contiguous
counter range. This keeps `clientSeq` monotonic per `conversationId` and
preserves the existing `createdAt = now + index` contract.

`sendImage` (single-row) stays **untouched** — it is the retry-path's anchor
and the album picker does not call it.

## Files to modify

### 1. `app/src/main/java/com/codex/im/message/MessageRepository.kt`

Add a new public function `createLocalImageMessages(...)` directly after
`createLocalGroupImageMessage` (so reading order matches call order in the
ViewModel). It should:

- Accept `senderId: String`, a target identifier (single `receiverId: String?` /
  `groupId: String?` pair, or a single resolved `conversationId: String`),
  `selectedImages: List<SelectedChatImage>`, and `nowBase: Long`.
- Compute `conversationId` once; this is the same branch the ViewModel does
  today (`isGroupConversationId()`).
- Loop `0 until N`: allocate `messageId = messageIdGenerator.next(senderId,
  nowBase + i)` and `clientSeq = seqGenerator.next(conversationId)`, build a
  `ChatMessage` with the same fields as the existing single-row function
  (`status = UPLOADING`, `direction = OUTGOING`, `type = IMAGE`,
  `content = IMAGE_PLACEHOLDER_CONTENT`, `localOriginalPath`,
  `localThumbnailPath`, `imageWidth`, `imageHeight`, `mimeType`).
- Open **one** `transactionRunner.runInTransaction { ... }` and inside, for
  each message, call `messageDao.insertOrIgnore(message)` and
  `conversationDao.upsertFromMessage(message, incrementUnread = false)`.
- Call `notifyConversationChanged()` **exactly once** after the transaction
  (the 64-slot SharedFlow buffer plus the fact that the insert phase happens
  before any completion events means one emission is sufficient).
- Return `List<ChatMessage>` in the same order as `selectedImages`.

Leave `createLocalImageMessage` and `createLocalGroupImageMessage` (lines
140-218) **untouched** — they are exercised directly by tests and remain the
canonical single-row entry point used by any future single-image flow.

`completeImageUploadAndQueueSend`, `markImageUploadFailed`, and
`markImageUploading` are **untouched** — they are invoked from per-row
success/failure paths and each does its own `notifyConversationChanged()`.

### 2. `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`

Rewrite `sendImages(selectedImages, now = System.currentTimeMillis())` (lines
233-240) into the three-phase version. Specifically:

- `val ordered = selectedImages.take(MAX_IMAGES_PER_SEND).sortedBy { it.selectionOrder }`.
- `val batchNow = now`.
- `val targetId = mutableState.value.peerId`.
- Wrap the rest in a single `coroutineScope { ... }` (or simply a
  `scope.launch(dispatcher) { withContext(dispatcher) { ... } }` plus parallel
  `launch`es) so failures don't cancel siblings.
- `val inserted: List<ChatMessage> = withContext(dispatcher) { repository.createLocalImageMessages(senderId = session.userId, targetId = targetId, selectedImages = ordered, nowBase = batchNow) }`.
- `refreshKeepingHistory()`.
- `coroutineScope { ordered.forEachIndexed { i, img -> launch { runCatching { uploadImageAndQueueSend(inserted[i].messageId, img) } } } }`.
  The `runCatching` is belt-and-braces; `uploadImageAndQueueSend` already
  handles all expected failures internally and does not throw, so this purely
  keeps a single bad row from cancelling siblings if a future change
  introduces a throw.

Keep `sendImage` (single) **untouched**. Keep `uploadImageAndQueueSend`
**untouched** (lines 282-357).

### 3. `app/src/test/java/com/codex/im/chat/ChatViewModelTest.kt`

**Fixture refactor — `FakeImageUploadApi`** (lines 1170-1215).

The existing `uploadResults: MutableList<AvatarPutResult>` consumed via
`removeAt(0)` is order-deterministic only under sequential execution. With
parallel uploads the order in which `upload(...)` is first called by sibling
coroutines is not deterministic, so the existing
`sendImagesContinuesAfterOneUploadFailure` test (lines 870-906) will flake.

Refactor: keep `uploadResults` as a fallback, and add
`uploadResultsByMessageId: MutableMap<String, MutableList<AvatarPutResult>>` (a
queue of `[thumbResult, originalResult]` per `messageId`). The `upload(...)`
override looks up by `messageId` first, then falls back to `uploadResults`. The
`messageId` is available because the production path records it in
`requestUploadTargets(messageId, ...)` and the test fixture already mirrors
that on `requests`.

Wrap the underlying map in a `ConcurrentHashMap` for parity with production
concurrent access (the test dispatcher serializes by default, but the production
fix is `coroutineScope { launch { ... } }` and a real-world CI / Robolectric run
may interleave).

**Fixture — `FakeConnection`** (lines 1149-1168) needs no change.
`sentPackets += packet` is append-only and `connection.send` is non-blocking.
`sentPackets` will be appended in completion order, not `createdAt` order, but
all existing assertions are on `sentPackets.size` (or on `messageDao.queryPage`
results which are sorted by `createdAt` server-side), so order-independence is
preserved. Add a short comment near `sentPackets` noting "packet order is
completion order, not row order."

**Existing tests to update.**

- `sendImagesContinuesAfterOneUploadFailure` (lines 870-906): change setup to
  seed `uploadResultsByMessageId` keyed by the three pre-computed `messageId`s.
  The expected status list `[SENDING, UPLOAD_FAILED, SENDING]` and
  `uploadApi.uploadCalls.size == 5` stay the same.
- `sendImagesOrdersBySelectionOrderNotInputOrder` (lines 908-930): no fixture
  change needed; the assertion is on `createdAt` order, which the new path
  preserves. No change.
- `ackedImageSentAfterFailedUploadStaysNewestInChatState` (lines 932-974): no
  fixture change needed; assertions are on `viewModel.state.value.messages` by
  `status` and by `messageId`, both of which the new path preserves. Verify
  during implementation; if it does depend on upload call order, refactor to
  the new `messageId`-keyed map.
- `sendImagesLimitsBatchToNineImages` (lines 976-991): no change. Asserts
  sizes only.

**New tests to add.**

- `sendImagesAllRowsAppearBeforeAnyUploadCompletes` — uses a
  `FakeImageUploadApi` whose `upload(...)` suspends on a `CompletableDeferred`
  controlled by the test. After calling `viewModel.sendImages(listOf 3 images)`
  and advancing the test dispatcher to the point where all 3
  `requestUploadTargets` calls have been made (but uploads are blocked),
  assert that `viewModel.state.value.messages.filter { it.type == IMAGE }` has
  3 rows, all with `status == UPLOADING`, and that all 3 have distinct
  `messageId`s. This is the headline regression test for the user-facing bug.
- `sendImagesPartialFailureKeepsSuccessfulRowsSENT` — register 3 messages,
  where message #2's thumb PUT returns `Failure`. After `runCurrent`, assert
  the 3 rows are `[SENDING, UPLOAD_FAILED, SENDING]` and that 2 `sentPackets`
  are queued. (Same intent as the existing
  `sendImagesContinuesAfterOneUploadFailure`, but framed under the new
  `messageId`-keyed fixture and run as the canonical partial-failure regression.)
- `sendImagesParallelFanOutDoesNotProduceDuplicateMessageIds` — call
  `sendImages` twice (two batches of 3) and assert the union of inserted
  `messageId`s has 6 distinct strings and that `clientSeq` for the
  `conversationId` is `[1, 2, 3, 4, 5, 6]` (strictly increasing, no gaps).
  Confirms `MessageIdGenerator` and `SeqGenerator` are not racing.
- `sendImagesTriggersExactlyOneRefreshAtInsertTime` — instrument
  `viewModel.state` via a small subscription that counts emissions whose
  `messages.size` grew. After `viewModel.sendImages(listOf 3 images)` and
  `runCurrent()`, the insert-time emissions should grow `messages.size` by 3
  in a single update, not 1+1+1. Locks in the "one batch insert, one refresh"
  design.

### 4. Files NOT modified (read-only references)

- `app/src/main/java/com/codex/im/chat/ChatScreen.kt` — the album picker
  callback at lines 369-382 already calls `viewModel.sendImages(preparedImages)`;
  the signature is preserved. The `OutgoingMessageStatus` spinner at lines
  905-943 is unchanged.
- `app/src/main/java/com/codex/im/chat/AlbumPickerViewModel.kt` — `selectedInOrder()`
  and `selectionOrder` semantics stay.
- `app/src/main/java/com/codex/im/chat/ChatImageBubbleLoadingPolicy.kt` and
  its unit test — `showInlineProgress()` and `showBubbleStatusProgress()`
  stay `false`.
- `app/src/main/java/com/codex/im/message/MessageIdGenerator.kt` and
  `SeqGenerator.kt` — left as-is. The fix relies on the batch function
  serializing the allocation step; we do not promote them to `AtomicLong` /
  `ConcurrentHashMap` in this pass. (Defense-in-depth option is noted under
  Risks.)

## Reused functions and utilities

- `MessageRepository.transactionRunner.runInTransaction { ... }` — already
  exists; the batch function reuses it.
- `MessageIdGenerator.next(senderId, now)` and `SeqGenerator.next(conversationId)`
  — called in a single coroutine in the new batch function, which avoids their
  read-modify-write race.
- `MessageRepository.createLocalImageMessage` / `createLocalGroupImageMessage`
  — kept as canonical single-row entry points; their field copy and
  `conversationId` computation are mirrored in the new batch function.
- `MessageRepository.completeImageUploadAndQueueSend` and
  `markImageUploadFailed` — called from the parallel `uploadImageAndQueueSend`
  fan-out; each already calls `notifyConversationChanged()`.
- `ChatViewModel.refreshKeepingHistory` — called once after the batch insert.
- `ChatViewModel.uploadImageAndQueueSend` — called from each parallel `launch`;
  unchanged.
- `OutgoingMessageStatus` ([`ChatScreen.kt:905-943`](app/src/main/java/com/codex/im/chat/ChatScreen.kt#L905-L943))
  — already renders the `CircularProgressIndicator` for `UPLOADING` /
  `SENDING`; no UI change.

## Verification

### Focused unit tests

From the repo root `D:/Desktop/engin/Java/IM`, on PowerShell / Windows use
`gradlew.bat`, on bash / macOS use `./gradlew`:

```
gradlew :app:testDebugUnitTest --tests com.codex.im.chat.ChatViewModelTest.sendImagesAllRowsAppearBeforeAnyUploadCompletes --tests com.codex.im.chat.ChatViewModelTest.sendImagesPartialFailureKeepsSuccessfulRowsSENT --tests com.codex.im.chat.ChatViewModelTest.sendImagesParallelFanOutDoesNotProduceDuplicateMessageIds --tests com.codex.im.chat.ChatViewModelTest.sendImagesTriggersExactlyOneRefreshAtInsertTime --tests com.codex.im.chat.ChatViewModelTest.sendImagesContinuesAfterOneUploadFailure --tests com.codex.im.chat.ChatViewModelTest.sendImagesOrdersBySelectionOrderNotInputOrder --tests com.codex.im.chat.ChatViewModelTest.sendImagesLimitsBatchToNineImages --tests com.codex.im.chat.ChatViewModelTest.ackedImageSentAfterFailedUploadStaysNewestInChatState
```

Then the full module test (regression):

```
gradlew :app:testDebugUnitTest
```

### Debug APK build

```
gradlew :app:assembleDebug
```

### Manual emulator checklist

For each scenario, install the debug APK, open a 1:1 chat, tap the album icon,
select images, tap Send.

1. **3 images, all succeed.** All 3 image bubbles appear in the chat the moment
   the picker closes, each with a small `CircularProgressIndicator` overlay.
   The spinners disappear one-by-one as each upload completes, in network-
   dependent order. No "image #1 uploads entirely, then #2 appears" stagger.
2. **9 images, all succeed (the `MAX_IMAGES_PER_SEND` cap).** All 9 bubbles
   appear at once, all 9 spinners visible immediately. Spinners disappear
   individually. Total wall-clock time should be noticeably less than the
   sequential baseline.
3. **3 images, 1 fails.** 3 bubbles appear at once, 2 transition to SENDING
   then SENT (check icon), 1 transitions to a red "!" badge. The other 2 are
   not blocked by the failure. (For the manual run, the easiest path to
   force a failure is to revoke the signed OSS URL mid-flight, or point the
   app at a fake base URL via debug-build config; the unit test
   `sendImagesPartialFailureKeepsSuccessfulRowsSENT` covers the logic
   deterministically.)
4. **Send, then immediately background the app and foreground.** The in-flight
   `UPLOADING` rows still resolve correctly. Confirms the parallel
   `coroutineScope` doesn't break `SupervisorJob` cancellation semantics.
5. **Send 3 images, then send 3 more before the first batch finishes.** 6
   distinct bubbles appear; `clientSeq` for the conversation is 1..6 (no
   gaps, no duplicates). Confirms `SeqGenerator` and `MessageIdGenerator`
   are not producing collisions across overlapping batches.
6. **Send 3 images where upload completion order is C, A, B.** The chat still
   renders the final visual order as A, B, C from top to bottom. This confirms
   that "parallel upload" no longer implies "completion-order SEND_MESSAGE".

## Implementation note (2026-06-07)

After the parallel-upload restructuring landed, we found a second ordering bug
that only shows up once uploads finish at different times.

### What went wrong

The first fix correctly preserved the user's album selection order during local
message creation:

- `AlbumPickerViewModel` compacts `selectionOrder`
- `ChatViewModel.sendImages(...)` sorts by `selectionOrder`
- `MessageRepository.createLocalImageMessages(...)` assigns contiguous
  `createdAt = nowBase + index`

That guarantees the temporary local `UPLOADING` rows appear in the expected
visual order before ACK.

However, the previous "parallel fan-out" implementation also called
`completeImageUploadAndQueueSend(...)` from each upload coroutine as soon as its
own thumbnail/original PUTs finished. That means:

1. uploads run in parallel;
2. image C may finish uploading before image A;
3. image C therefore enqueues `SEND_MESSAGE` before image A;
4. the server assigns `serverSeq` in send-arrival order, not selection order;
5. once ACKs arrive, `MessageOrderingPolicy` correctly prefers `serverSeq` for
   sent messages, so the final chat order can become C, A, B instead of A, B, C.

So the root cause was not lost `selectionOrder`; it was letting upload
completion order leak into the authoritative IM send order.

### How it was changed

`ChatViewModel.sendImages(...)` now uses a two-step phase-3 flow:

1. Start all image uploads in parallel and let each coroutine finish only the
   upload preparation work (request upload target, PUT thumbnail, PUT original).
2. Collect those prepared results in `selectionOrder`, then call
   `completeImageUploadAndQueueSend(...)` sequentially in that same order.

Concretely, the previous "upload and queue send inside each sibling launch"
shape was split into:

- `prepareImageUpload(...)`
  - performs the upload-target request and both OSS PUTs
  - still marks `UPLOAD_FAILED` immediately on per-row failure
- `completePreparedImageSend(...)`
  - performs `completeImageUploadAndQueueSend(...)`
  - therefore controls when `SEND_MESSAGE` is actually emitted

This preserves both requirements:

- thumbnails appear immediately and uploads still run in parallel;
- authoritative `serverSeq` assignment now follows the user's selection order.

### Regression coverage added

- `sendImagesKeepsSelectionOrderWhenUploadsFinishOutOfOrder`
  - forces the upload completion order to differ from the selection order
  - asserts `sentPackets` are still emitted in selection order
- Existing selection-order tests remain valid:
  - `sendImagesOrdersBySelectionOrderNotInputOrder`
  - `sendImagesSixImagesPreserveSelectionOrderInVisualLayout`

## Risks and trade-offs

- **`MessageIdGenerator` and `SeqGenerator` are not thread-safe.** They do
  read-modify-write on plain `Long` / `HashMap` with no synchronization, no
  `@Volatile`, no `AtomicLong` / `ConcurrentHashMap`. With the design above,
  all allocations happen on the single coroutine in the ViewModel that runs
  the new `createLocalImageMessages` batch function, in a `for (i in 0 until
  N)` loop, *before* any `launch { }` starts. The parallel coroutines only
  call `uploadImageAndQueueSend`, which never touches the generators. So
  even though the generators are not thread-safe, no race occurs. **We
  recommend not changing the generators in this pass** to keep scope tight.
  If a follow-up hardening pass is desired, the smallest defensible change is
  `MessageIdGenerator`: `private val counter = AtomicLong(startCounter)` and
  `val n = counter.incrementAndGet()`; and `SeqGenerator`: switch the map to
  `ConcurrentHashMap<String, AtomicLong>` and use `computeIfAbsent` +
  `incrementAndGet`. But that is a separate ticket.
- **`OkHttp` per-host cap is 5.** 9 simultaneous OSS PUTs (or 18 across two
  hosts: thumb + original) will queue inside OkHttp's dispatcher. This is
  acceptable and matches typical mobile upload UX. We do not introduce a
  `Semaphore`.
- **WebSocket `connection.send` is non-blocking and has no app-level
  back-pressure.** 9 `completeImageUploadAndQueueSend` calls each enqueue one
  `SEND_MESSAGE` packet; OkHttp's WebSocket impl buffers. This is acceptable
  for this fix and matches existing single-image behavior.
- **Parallel upload and ordered send are intentionally decoupled.** This adds
  one more internal state boundary (`prepareImageUpload` vs
  `completePreparedImageSend`), but it is necessary because the product
  contract is "selection order wins", while the server's contract is
  "arrival order gets `serverSeq`". If we ever want "true completion-order
  send", that would be a product change, not an implementation cleanup.
- **`refreshKeepingHistory` cost.** The function does a full SQL page query +
  sort + state copy. We call it once after the batch insert, plus N times
  indirectly (one per completion) via the existing `notifyConversationChanged`
  consumer. That is **strictly fewer** emissions than the current
  sequential path (which calls it 2N times — once after each insert and once
  after each completion). The new path is a net improvement.
- **`notifyConversationChanged` is `MutableSharedFlow<Unit>(extraBufferCapacity
  = 64)` with no debounce.** The single batch-insert emission is well within
  buffer; per-completion emissions are 1:1 with what the user already sees
  today (each row's UPLOADING → SENDING transition still emits).
- **Test fixtures are not concurrency-safe.** We refactor
  `FakeImageUploadApi.uploadResults` to be `messageId`-keyed (with a fallback
  to the existing list for single-row tests). `FakeConnection.sentPackets`
  is append-only and order-independent, so no change.

## Explicitly out of scope

- Album picker (`AlbumPickerViewModel`, `AlbumPickerScreen`,
  `AndroidAlbumImageRepository`) — unchanged.
- Bubble overlay policy (`ChatImageBubbleLoadingPolicy.kt` and its unit
  test) — unchanged. The `OutgoingMessageStatus` `CircularProgressIndicator`
  is the spinner source.
- Retry policy (`retryImageMessage`, `markImageUploadFailed`, the
  `pending_messages` ack/retry path) — unchanged.
- Generators' thread-safety (`MessageIdGenerator`, `SeqGenerator`) —
  unchanged in this pass.
- WebSocket back-pressure / `Semaphore` / `RateLimiter` — not added.
- `notifyConversationChanged` debouncing — not added.
- `MAX_IMAGES_PER_SEND = 9` cap — unchanged.
- Persistence of `selectionOrder` beyond the immediate `sendImages` call —
  not changed.
- iOS / server / web parity — out of scope.

## Implementation order (single session, ~2-4 hours)

1. Add `createLocalImageMessages` in `MessageRepository.kt`. Run existing
   unit tests to confirm no regression in non-batch code paths.
2. Rewrite `ChatViewModel.sendImages` to the three-phase version. Compile
   and run the existing `ChatViewModelTest` suite; expect 1 fixture failure
   in `sendImagesContinuesAfterOneUploadFailure`.
3. Refactor `FakeImageUploadApi.uploadResults` to be `messageId`-keyed.
   Update `sendImagesContinuesAfterOneUploadFailure` setup.
4. Add the four new tests (`sendImagesAllRowsAppearBeforeAnyUploadCompletes`,
   `sendImagesPartialFailureKeepsSuccessfulRowsSENT`,
   `sendImagesParallelFanOutDoesNotProduceDuplicateMessageIds`,
   `sendImagesTriggersExactlyOneRefreshAtInsertTime`).
5. Run focused gradle test target, then full `:app:testDebugUnitTest`.
6. Build debug APK and run the manual emulator checklist.
