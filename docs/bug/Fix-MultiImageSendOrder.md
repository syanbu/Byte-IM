# Bug: Multi-Image Send Order Reversed

## Status

- Status: Durable fix implemented
- Workaround applied on: 2026-06-04
- Durable fix implemented on: 2026-06-05
- Branch: current working branch
- Tracking doc: `docs/status/B11-image-message-design-status.md` (Bug Fix Log + Current Risks)
- Long-term fix: implemented a self-built album selection flow that records an explicit `selectionOrder` on each `SelectedChatImage` and sends directly from the album page. Tap-to-preview and drag reorder are intentionally left as future UX optimizations. The self-built picker removes the dependence on the system Photo Picker's tap-order behavior, which is the root cause described below.

## Problem

Selecting N photos in the album in tap order A, B, C, ... and then tapping Send sent the messages in the opposite order: the last tapped photo went first, the first tapped photo went last.

Observed behavior on the tested device:

- 4 photos selected in order A, B, C, D were sent in the order D, C, B, A.
- The first photo the user tapped (A) ended up as the newest message at the bottom of the chat surface.
- The chat list ordering itself was correct (newest at the bottom via `ORDER BY createdAt DESC` + `LazyColumn(reverseLayout = true)`); only the `createdAt` assignment was wrong.

Expected behavior:

- The first photo the user tapped (A) should be sent first and end up as the oldest message in the just-sent multi-image group (i.e. the top of the new message group, since "newest = bottom" in this UI).
- Equivalently: the chat bubble group should display the photos in the same order the user tapped them in the album.

## Root Cause

The chat composer uses `ActivityResultContracts.PickMultipleVisualMedia(9)` to open the system Photo Picker, then compresses and sends the URIs in the order they come back. The downstream pipeline preserves that order end-to-end:

- `ChatScreen.kt` callback: `uris.mapNotNull { ChatImageCompressor.prepareSelectedImage(...) }`
- `ChatViewModel.sendImages(...)`: `forEachIndexed { index, img -> sendImage(img, now + index) }`
- `MessageRepository.createLocalImageMessage(...)`: writes the row with `createdAt = now + index`
- `MessageOrderingPolicy.localNewestFirst`: `createdAt DESC` is the first sort key

Because every step is order-preserving, the URI list returned by the Photo Picker becomes the message order, with `createdAt` strictly increasing by 1ms per image.

AndroidX's `ActivityResultContracts.PickMultipleVisualMedia` contract comments say the URI list follows the user's tap order, but the actual Photo Picker build on this device returns the URIs in reverse selection order (last tapped at index 0). Reported behavior of `Intent.EXTRA_STREAM` / `ClipData` in the picker result is inconsistent across Android versions and Photo Picker builds (AOSP Issue Tracker has multiple reports of similar order surprises), so the documented contract is not a reliable guarantee.

The 1ms step of `now + index` is also too coarse to absorb any platform reordering; even on devices where the picker is consistent, two consecutive compress-and-insert cycles can land on the same `System.currentTimeMillis()` tick, and the `createdAt` tie-break then falls through to `clientSeq` and `messageId`, which is not necessarily tap order either.

## Short-Term Fix (Workaround)

Add `.reversed()` to the URI list inside the `PickMultipleVisualMedia` callback in `ChatScreen.kt` before compression, so the tap order is restored for this specific Photo Picker build. Record the rationale in a comment next to the change so the next reader does not delete it as a no-op.

Code change in `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`:

```kotlin
// AndroidX `PickMultipleVisualMedia` does not guarantee that the returned URI
// list follows the user's tap order. Empirically, on this device's Photo Picker
// build the URIs come back in reverse selection order (last tapped at index 0),
// which makes the first selected photo end up as the newest message after
// `forEachIndexed` + `now + index` apply ascending `createdAt` values. Reverse
// the list here so the user's tap order is preserved end-to-end. If we ever
// switch to a self-built album UI (see status doc), this `.reversed()` should be
// removed because the custom picker can guarantee tap order directly.
val orderedUris = uris.reversed()
val preparedImages = orderedUris.mapNotNull { uri ->
    ChatImageCompressor.prepareSelectedImage(context, context.contentResolver, uri)
}
```

## Affected Files

- `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt` (picker callback; added `.reversed()` plus a rationale comment)

## Alternative Considered

Switch the chat composer to a self-built album UI:

- Build the album page from `ContentResolver.query(MediaStore.Images)` and a Coil-backed thumbnail grid.
- Maintain the selection as a `LinkedHashSet<Uri>` so the click order is explicit in the data model, not inferred from a system callback.
- Add an explicit `selectionOrder: Int` field to `SelectedChatImage` and have `ChatViewModel.sendImages` sort the list by `selectionOrder` before applying `now + index` for `createdAt`.

Why it was not done in this pass:

- The full expanded UX work is a multi-day UI task if it includes tap-to-preview, drag reorder, delete-before-send, and original-image toggles; the immediate reported bug ("first selected photo ends up newest") only requires app-owned selection order.
- The `.reversed()` workaround unblocks the user today at the cost of one extra line in the picker callback. It is fragile across Android versions and Photo Picker builds, which is why this fix is documented as "workaround" rather than "completed".

The self-built album pass has now replaced this workaround, and the `.reversed()` callback code was removed in the same change.

## Durable Fix

Implemented on 2026-06-05:

- Added `selectionOrder` to `SelectedChatImage`.
- Updated `ChatViewModel.sendImages(...)` to sort by `selectionOrder` before assigning `createdAt = now + index`.
- Replaced the chat composer image entry with a self-built album flow backed by `MediaStore.Images`.
- Added an app-owned album selection state model that records tap order, compacts order after deselect/delete, and caps one batch at 9 images.
- The album page primary action sends directly; it does not force a preview confirmation step.
- Removed the system Photo Picker callback and its `.reversed()` workaround from `ChatScreen.kt`.

Tap-to-preview, delete-before-send UI, and drag reorder are not included in this pass. They can be added later while keeping `selectionOrder` as the send-order contract.

## Verification Result

Manual verification on the tested device after the workaround:

- Selecting 4 photos in order A, B, C, D and tapping Send now produces 4 image messages in the order A, B, C, D.
- In the chat, A is the oldest in the new message group (top of the group), D is the newest (bottom of the group). The user's tap order is preserved end-to-end.

No automated test was added for this workaround:

- The behavior is in the Android system Photo Picker, which is not mockable inside a JVM unit test.
- A JVM test that reverses the URI list would be testing the test rather than the production code.
- The durable fix (self-built album + explicit `selectionOrder`) is the right place to spend the test investment.

## Verification Plan For The Long-Term Fix

When the self-built album enhancements are implemented, add the following tests:

- `AlbumPickerViewModelTest`:
  - tap order A, B, C, D produces selection list `[A, B, C, D]`
  - tap B again to deselect, then tap E, produces `[A, C, D, E]` (LinkedHashSet semantics)
  - tapping the same image twice does not create a duplicate
- Future preview/reorder tests:
  - tapping an album image opens preview without changing selected send order
  - drag-reorder swaps `selectionOrder` of the two involved entries
  - delete a middle entry compacts `selectionOrder` so it stays a `[0, size)` bijection
- `ChatViewModelTest.sendImagesOrdersBySelectionOrderNotByInputOrder`:
  - pass a list in `[D, A, C, B]` order with `selectionOrder = [0, 1, 2, 3]` on the corresponding entries
  - assert the resulting 4 messages have `createdAt` strictly increasing as `A < B < C < D`

Run the full Android JVM test suite plus the debug build to confirm no regression:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Then manually verify on at least one Pixel-class device and one non-Pixel device (or one with a vendor-customized Photo Picker) that A → B → C → D selection in the custom album produces A, B, C, D in the chat.
