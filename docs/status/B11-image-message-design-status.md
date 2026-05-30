# B11 Image Message Design Status

## Requirement

Support single-chat image messages with:

- gallery pick only
- multi-image gallery pick, expanded into one image message per selected image
- OSS upload before message send
- placeholder, loading, failure, and progressive display in chat

## Status

Implemented for gallery image send with multi-select expansion into independent image messages.

## Agreed Scope

- Single chat only
- Static images only
- Gallery picker only
- Up to 9 images per picker action, stored/sent as independent image messages
- No camera capture
- No GIF or video
- No OSS orphan-file cleanup in this pass
- Use reliable state machine plus compensation instead of strict cross-system atomicity
- Distinguish OSS upload failure from IM message send failure
- Use `coil-compose` for chat image loading

## Current Foundation Reused From Existing Work

- Android already supports backend-issued OSS signed upload targets and direct client `PUT` upload for avatars.
- Mock-server already exposes OSS upload-target generation through `OssUploadService`.
- Android already has local SQLite message persistence, conversation summary persistence, and pending outbox persistence.
- Android already has sender-side reliability for `SEND_MESSAGE -> MESSAGE_ACK`, backed by `pending_messages`.
- Android chat UI already shows outgoing send states for text messages.
- Mock-server already persists accepted messages and keeps `messageId` idempotency semantics for retry.

## Implemented In This Pass

- Android local message storage now supports image-message fields, local preview paths, and upload-phase statuses.
- Image-message send flow is split into:
  - OSS upload failure -> local `UPLOAD_FAILED`
  - IM send failure after upload success -> existing `pending_messages` retry flow
- Android repository now creates local image messages before upload, then queues `SEND_MESSAGE` only after upload success.
- Incoming image messages now persist `imageUrl`, `thumbnailUrl`, dimensions, MIME type, and file size.
- Mock-server now exposes `POST /oss/message-image/upload-targets` and returns signed targets for thumbnail and original uploads.
- Mock-server continues to preserve duplicate `messageId` idempotency for image messages.
- Chat screen now supports:
  - gallery multi-image pick
  - local thumbnail bubble preview
  - image bubble loading state
  - upload/send failure text state
  - one-tap retry via the same red failure indicator for both `UPLOAD_FAILED` and `FAILED`
  - image preview overlay for the original image
  - stable image bubble sizing from metadata before upload completion
  - mutually exclusive composer actions: image pick when the draft is empty, send when the draft has text
  - multi-image send that continues processing later images when one image upload fails
  - strict receiver-side thumbnail display: incoming image messages are hidden from chat until `localThumbnailPath` is available
  - receiver-side thumbnail compensation retry on chat open and active conversation updates
  - Coil preloading for the target chat's recent local thumbnails before navigation from the conversation list
- Chat screen keyboard handling has been stabilized across tested Android devices:
  - `MainActivity` calls `WindowCompat.setDecorFitsSystemWindows(window, false)`
  - the app root applies `systemBarsPadding()`
  - the chat screen root keeps `Modifier.imePadding()`
  - `AndroidManifest.xml` keeps `android:windowSoftInputMode="adjustNothing"`
  - this lets Compose own system bar and IME inset handling instead of relying on device/ROM-specific Activity resize behavior
- `coil-compose` is now used for chat-image loading.

## Resolved Device Compatibility Notes

- Keyboard / IME overlap issue: completed on 2026-05-30.
- Affected behavior: on some Android versions or ROMs, especially when IME insets were not delivered consistently, tapping the chat input could leave the keyboard covering the Activity or leave excessive blank space above the composer.
- Final strategy: system does not resize the Activity; Compose receives and applies insets explicitly with `setDecorFitsSystemWindows(false)`, app-level `systemBarsPadding()`, chat-level `imePadding()`, and manifest `adjustNothing`.
- Regression guard: `ChatKeyboardInsetsPolicyTest` asserts the chosen inset strategy.
- Detailed bug note: `docs/bug/Fix-HonorKeyboardImeOverlay.md`.

## Core Design

Image message delivery is split into two phases:

1. Asset phase: upload thumbnail and original image to OSS.
2. Message phase: after upload success, send a normal IM message whose body contains image URLs and metadata.

This is not a strict transaction across OSS and WebSocket. The first pass uses a reliable local state machine:

- Upload failure stays in the local message row and does not enter `pending_messages`.
- Message-send failure after upload success reuses the existing `pending_messages` retry path.
- OSS orphan files are accepted in this learning-demo scope.

## Progressive Display Meaning In This Project

Progressive display in this project means staged UI display, not byte-level progressive JPEG decode:

- show placeholder immediately
- show local thumbnail or remote `thumbnailUrl` as early preview
- replace preview with full `imageUrl` when needed
- keep failure state visible if thumbnail or original load fails

The same message carries two image resources:

- `thumbnailUrl`: small image for the chat bubble
- `imageUrl`: full image for image preview

Sending-side local preview also uses cached local files before OSS upload completes.

## Chat Bubble Display and Cache Clarification

For image messages, the chat list/bubble must display the thumbnail resource, not the original image:

- outgoing messages prefer `localThumbnailPath`, then `thumbnailUrl`
- incoming messages prefer `localThumbnailPath` if a local thumbnail cache exists, then `thumbnailUrl`
- `imageUrl` is reserved for full-screen preview or another explicit original-image action

The bubble size is now stable before the thumbnail finishes loading. The sender stores `imageWidth` and `imageHeight` when the local `UPLOADING` row is first created, so the client can compute a bounded display size and apply that same size to the placeholder, loading state, loaded thumbnail, and failure state. This prevents the previous visual jump where the bubble used a fallback size during upload and then expanded after upload metadata was written.

Receiver-side thumbnail caching is now strict for chat display:

- on receive, the message row first persists URLs and metadata
- the repository attempts to download the thumbnail into app cache and update `localThumbnailPath`
- chat history display filters out incoming image messages whose `localThumbnailPath` is still missing
- after thumbnail caching succeeds and `localThumbnailPath` is written, the repository emits an update and the message appears in chat
- if thumbnail caching fails, the message remains persisted but temporarily hidden from chat while retry attempts continue

Receiver-side thumbnail retry strategy:

- opening a chat triggers an immediate compensation scan for `INCOMING + IMAGE + localThumbnailPath IS NULL`
- active conversation updates also schedule lightweight retries for newly hidden incoming image messages
- each message retries thumbnail caching at most three times in the current chat ViewModel lifecycle
- retry delays are `2s -> 10s -> 30s`
- retries only download/cache the thumbnail; original image loading remains on demand
- success writes `localThumbnailPath` and refreshes chat display
- final failure keeps the message hidden but persisted for a future chat-open compensation attempt

The original image should remain on-demand for receivers. It should be loaded only when the user opens image preview.

Sender-side original storage is a cache, not the authoritative copy after upload:

- before upload succeeds, `localOriginalPath` is required for preview and upload retry
- after upload succeeds, `imageUrl` is the durable remote copy and `localOriginalPath` is only a local convenience cache
- if the local original cache is later cleared, preview must fall back to `imageUrl`
- this pass does not require a local original cleanup policy

Conversation-list-to-chat image preloading:

- when the user taps a conversation row, the conversation list ViewModel exposes the navigation target first and starts local thumbnail preloading in a fire-and-forget background task
- the preload scope is intentionally narrow: the most recent 5 image messages that already have `localThumbnailPath`
- the preloader only enqueues local thumbnail files into Coil; it does not download remote `thumbnailUrl` resources
- this reduces the first visible decode/loading spinner after app restart while keeping navigation lightweight and avoiding broad app-start cache scans

## Data Model Changes

### `messages`

The current text-only message model must expand into a text-or-image model.

Add or expand these fields:

- `message_type TEXT NOT NULL`
- `content TEXT NOT NULL`
- `image_url TEXT`
- `thumbnail_url TEXT`
- `image_width INTEGER`
- `image_height INTEGER`
- `mime_type TEXT`
- `file_size_bytes INTEGER`
- `local_original_path TEXT`
- `local_thumbnail_path TEXT`

Field rules:

- Text message:
  - `message_type = TEXT`
  - `content = actual text`
- Image message:
  - `message_type = IMAGE`
  - `content = [图片]`
  - image-related fields filled as available

### `pending_messages`

No image bytes should be stored here.

This table stays focused on IM send retry:

- after upload success, persist the final `SEND_MESSAGE` JSON into `packet_body`
- retry continues to reuse the original packet body

Upload failure must not create a `pending_messages` row.

### `conversations`

No schema expansion is required in the first pass.

Conversation preview behavior:

- text message preview stays unchanged
- image message preview writes `"[图片]"`

## Message Status Design

The current send-state model should expand to distinguish upload phase from message-send phase.

Recommended statuses:

- `UPLOADING`
- `UPLOAD_FAILED`
- `SENDING`
- `SENT`
- `FAILED`
- `RECEIVED`

Status meaning:

- `UPLOADING`: local image message exists, OSS upload not finished
- `UPLOAD_FAILED`: OSS upload failed; user may retry upload
- `SENDING`: upload finished; IM message packet is waiting for `MESSAGE_ACK`
- `FAILED`: IM send retries exhausted after upload success
- `SENT`: sender received `MESSAGE_ACK`
- `RECEIVED`: receiver persisted the image message

## Protocol Design

No new WebSocket command is needed in the first pass. Reuse:

- `SEND_MESSAGE`
- `MESSAGE_ACK`
- `RECEIVE_MESSAGE`
- `DELIVERY_ACK`

Extend the message JSON body to support image payloads.

Suggested shape:

```json
{
  "messageId": "m_...",
  "conversationId": "single:13800113800:13900113900",
  "senderId": "13800113800",
  "receiverId": "13900113900",
  "clientSeq": 12,
  "type": "IMAGE",
  "content": "[图片]",
  "image": {
    "imageUrl": "https://.../origin.jpg",
    "thumbnailUrl": "https://.../thumb.jpg",
    "width": 1280,
    "height": 960,
    "mimeType": "image/jpeg",
    "sizeBytes": 326412
  },
  "timestamp": 1716970000000
}
```

Text messages may either:

- keep `type = TEXT`, or
- omit `type` and default to `TEXT`

The important rule is that image messages still preserve `content = "[图片]"` for summary compatibility.

## Upload API Design

Reuse the avatar upload pattern, but add a dedicated image-message upload-target endpoint.

Recommended endpoint:

- `POST /oss/message-image/upload-targets`

Recommended response returns two signed targets in one call:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "messageId": "m_...",
    "thumbnail": {
      "objectKey": "chat-images/13800113800/m_.../thumb.jpg",
      "uploadUrl": "https://...",
      "publicUrl": "https://..."
    },
    "original": {
      "objectKey": "chat-images/13800113800/m_.../origin.jpg",
      "uploadUrl": "https://...",
      "publicUrl": "https://..."
    },
    "expiresAt": 1716970900000
  }
}
```

Object key recommendation:

- include `userId`
- include `messageId`
- separate thumbnail and original objects

This keeps retry and traceability simple.

## Local Persistence and Reset Assumption

This project is a learning demo, not a production app.

For this B11 pass:

- old text-message rows do not need migration protection
- a database version bump may rebuild local message storage if that is simpler during implementation
- no backward-compatibility strategy is required for old local rows

The implementation should still keep the new schema internally consistent, but it does not need a production-safe migration path.

## Detailed Send Flow

### Normal Success Path

1. User picks one or more images from gallery.
2. Android expands the selection into independent image-message send flows, capped at 9 images.
3. Each selected image follows the single-image flow below and owns its own message status.

### Per-Image Normal Success Path

1. Android prepares one selected image.
2. Android creates local cached files:
   - one original file for preview and upload
   - one thumbnail file for bubble preview and upload
3. Repository inserts a local image message row:
   - `message_type = IMAGE`
   - `content = [图片]`
   - `status = UPLOADING`
   - local file paths saved
   - image width and height saved immediately for stable bubble sizing
4. Chat UI immediately shows the local thumbnail bubble.
5. Android requests OSS upload targets from backend.
6. Android uploads thumbnail to OSS.
7. Android uploads original image to OSS.
8. After both uploads succeed, repository updates the local row:
   - fill `thumbnail_url`
   - fill `image_url`
   - fill width, height, mime type, and file size
   - switch status to `SENDING`
9. Repository creates the final `SEND_MESSAGE` packet body and writes one `pending_messages` row.
10. Repository sends the packet through the existing connection.
11. Server returns `MESSAGE_ACK`.
12. Repository marks the message `SENT` and deletes the pending row.

### Upload Failure Path

1. Local image message row already exists as `UPLOADING`.
2. Upload target request or OSS upload fails.
3. Repository marks the local row `UPLOAD_FAILED`.
4. No `pending_messages` row is created.
5. User can tap the red failure indicator.
6. Retry repeats only the OSS upload phase from the local cached original/thumbnail files and uses the same local message row.
7. After upload succeeds, retry enters the normal message phase and queues the `SEND_MESSAGE` packet.

### Message Send Failure Path

1. Both OSS uploads already succeeded.
2. Local row already contains final remote URLs.
3. Repository already created `pending_messages` and attempted `connection.send()`.
4. If `MESSAGE_ACK` does not arrive, the existing outbox worker retries the original packet body.
5. Upload is not repeated.
6. If retries are exhausted, repository marks the row `FAILED`.
7. User can tap the same red failure indicator.
8. Retry rebuilds the lightweight `SEND_MESSAGE` packet from the persisted OSS URLs and image metadata, re-enters `pending_messages`, and does not upload OSS files again.

### App Death During Upload

First-pass behavior:

- on app restart, unfinished image messages in `UPLOADING` may be converted to `UPLOAD_FAILED`
- user manually retries the upload

This pass does not require resumable upload.

### App Death During Message Send

This already matches the text-message outbox model:

- `pending_messages` survives
- after app restart and authenticated session restore, outbox worker retries send
- upload is not repeated

## Repository Changes

Current [MessageRepository](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\message\MessageRepository.kt:1) is text-message centric and must expand.

### Existing Behavior To Keep

- local message row, conversation preview, and pending row are still written before network send in the message phase
- `MESSAGE_ACK` still transitions sender state and removes pending
- incoming messages still use `insertOrIgnore` plus `DELIVERY_ACK`

### New Responsibilities

- create local image messages in `UPLOADING`
- update image-message metadata after upload success
- separate upload failure from send failure
- create pending rows only after upload success
- parse incoming image payloads in `handleIncoming`

### Suggested Repository API Additions

- `createLocalImageMessage(...)`
- `markImageUploadFailed(...)`
- `completeImageUploadAndQueueSend(...)`
- `retryImageUpload(...)`
- `retryFailedImageSend(...)`
- `findMessageById(...)`

### Required DAO Support

Current [MessageDao](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\storage\MessageDao.kt:1) and [AndroidMessageDao](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\storage\AndroidMessageDao.kt:1) need more than insert and ACK/FAILED updates.

Add support for:

- update image upload result fields
- update generic message status
- find one message by `messageId`
- query image messages needing startup recovery handling

### Conversation Summary Behavior

Current [ConversationDao](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\storage\ConversationDao.kt:1) and [AndroidConversationDao](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\storage\AndroidConversationDao.kt:1) can keep their schema.

They must change preview-generation behavior:

- if `message_type = IMAGE`, write `"[图片]"` into `last_message_preview`
- unread logic stays unchanged

## ChatViewModel Changes

Current [ChatViewModel](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\chat\ChatViewModel.kt:1) only orchestrates text sending.

### New Responsibilities

- launch gallery pick send flow
- create local image preview state through repository
- request OSS upload targets
- upload thumbnail and original image
- transition upload result into send phase
- surface upload failure separately from send failure

### Suggested ViewModel API Additions

- `sendImage(...)`
- `sendImages(...)`
- `retryImageMessage(messageId: String)`

### UI State Considerations

The per-message phase should come from SQLite-backed message rows, not from duplicated transient UI-only state.

Small screen-level additions are still useful:

- `imageSendErrorMessage: String?`
- optional one-shot event for opening preview or showing toast/snackbar

## Chat UI Changes

Current [ChatScreen](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\chat\ChatScreen.kt:1) is text-only.

Required expansions:

- gallery-pick entry in composer bar
- image bubble composable
- visual states for:
  - local thumbnail while uploading
  - upload spinner
  - upload failed
  - send failed
  - remote thumbnail load
  - remote image open-preview action
- single-image preview screen that loads `imageUrl`

## Image Loading Decision

Current avatar rendering uses a custom lightweight cache path in [AvatarImage.kt](D:\Desktop\engine\IM\app\src\main\java\com\codex\im\ui\AvatarImage.kt:1).

That approach is not enough for chat image messages because chat images are larger and need:

- placeholder support
- local-file loading
- remote thumbnail loading
- remote original loading
- failure visuals
- memory and disk caching

Therefore this pass should add `coil-compose` for chat images.

## Mock-Server Changes

### HTTP Side

Current [HttpAuthHandler.java](D:\Desktop\engine\IM\mock-server\src\main\java\com\codex\imserver\netty\HttpAuthHandler.java:1) already handles avatar upload targets.

Add:

- dedicated image-message upload-target endpoint
- request parsing for image content type and `messageId` if needed
- response JSON for both thumbnail and original upload targets

### OSS Service Side

Current [OssUploadService.java](D:\Desktop\engine\IM\mock-server\src\main\java\com\codex\imserver\oss\OssUploadService.java:1) currently exposes avatar-target generation only.

Expand it to:

- generate two upload targets for image messages
- build object keys under a chat-image prefix
- preserve the current signed-URL style

### Message Router Side

Current [MessageRouter.java](D:\Desktop\engine\IM\mock-server\src\main\java\com\codex\imserver\session\MessageRouter.java:1) currently logs and persists messages with text-oriented assumptions.

Required expansion:

- accept image payload JSON in `SEND_MESSAGE`
- keep sender ACK and receiver forward semantics unchanged
- preserve `messageId` idempotency unchanged
- keep `content = [图片]` for summary compatibility
- store full image payload in persisted `message_json`

## Existing Code Areas Expected To Change

Android:

- `app/src/main/java/com/codex/im/storage/ImDatabaseHelper.kt`
- `app/src/main/java/com/codex/im/storage/StorageModels.kt`
- `app/src/main/java/com/codex/im/storage/MessageDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidMessageDao.kt`
- `app/src/main/java/com/codex/im/storage/ConversationDao.kt`
- `app/src/main/java/com/codex/im/storage/AndroidConversationDao.kt`
- `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- `app/build.gradle`

Likely new Android files:

- image-message model helpers
- gallery/image compression helper for chat images
- `coil-compose`-based image bubble composable
- image preview screen
- image upload API types and parser for chat-message targets

Mock-server:

- `mock-server/src/main/java/com/codex/imserver/netty/HttpAuthHandler.java`
- `mock-server/src/main/java/com/codex/imserver/oss/OssUploadService.java`
- `mock-server/src/main/java/com/codex/imserver/session/MessageRouter.java`

Likely new mock-server tests:

- chat image upload-target response tests
- image-message router acceptance tests
- image-message duplicate `messageId` retry tests

## Explicitly Deferred

- Multi-image send
- Camera capture
- GIF and video
- Resumable upload
- Automatic orphan-file cleanup
- Production-safe database migration
- Backend-side image moderation or validation beyond basic content-type handling

## Recommended Next Implementation Order

1. Expand local storage model and schema for image messages.
2. Add image-message upload-target API on mock-server.
3. Add Android chat-image upload API and parsers.
4. Extend repository and DAO state transitions for `UPLOADING` and `UPLOAD_FAILED`.
5. Extend message JSON build/parse for image payloads.
6. Add `coil-compose` and image bubble rendering.
7. Add image preview screen.
8. Add tests for upload failure, send failure, retry split, and incoming image persistence.

## Current Risks

- The main new complexity is not OSS signing itself; it is the split between upload-phase reliability and send-phase reliability.
- Local file cleanup is not addressed in this pass.
- Without careful status handling, image messages can incorrectly enter `pending_messages` before upload is complete.
- Chat bubble and preview rendering should avoid loading original images directly in the message list.
- Manual emulator verification is still recommended for:
  - real gallery pick on device/emulator
  - real OSS upload against the configured bucket
  - bubble rendering for very large images
  - preview dismissal behavior
- Manual retry is implemented through the red failure indicator:
  - `UPLOAD_FAILED` retries OSS upload first, then sends the message.
  - `FAILED` retries the lightweight IM message send only and reuses existing OSS URLs.
  - The UI intentionally keeps one visual affordance while the repository/ViewModel preserve distinct internal states.
