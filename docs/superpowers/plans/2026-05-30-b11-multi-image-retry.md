# B11 Multi Image Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support multi-image selection and one-tap retry for failed image messages while preserving distinct upload-failure and send-failure states internally.

**Architecture:** Keep one image per IM message. Multi-select expands into multiple existing `IMAGE` messages; each message owns its upload/send state. The same UI failure affordance calls one ViewModel retry API, which branches by persisted status.

**Tech Stack:** Android Kotlin, Jetpack Compose, Activity Result APIs, SQLite DAO layer, existing WebSocket pending outbox.

---

### Task 1: Retry Failed Image Messages

**Files:**
- Modify: `app/src/test/java/com/codex/im/message/MessageRepositoryTest.kt`
- Modify: `app/src/test/java/com/codex/im/chat/ChatViewModelTest.kt`
- Modify: `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- Modify: `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/codex/im/message/ImageUploadModels.kt`

- [ ] Write failing repository tests for requeueing a failed image message with existing OSS URLs.
- [ ] Write failing ViewModel tests for `UPLOAD_FAILED` retry using local cached files and for `FAILED` retry without OSS upload.
- [ ] Implement a local-file image retry resolver.
- [ ] Implement repository status transition and send requeue helpers.
- [ ] Implement `ChatViewModel.retryImageMessage(messageId)`.
- [ ] Run focused tests until green.

### Task 2: Failure Icon Click

**Files:**
- Modify: `app/src/main/java/com/codex/im/chat/ChatScreen.kt`

- [ ] Wire the existing red failure indicator to call `retryImageMessage(messageId)` for `UPLOAD_FAILED` and `FAILED`.
- [ ] Keep the visual label as the same red exclamation mark.

### Task 3: Multi-Image Selection

**Files:**
- Modify: `app/src/test/java/com/codex/im/chat/ChatViewModelTest.kt`
- Modify: `app/src/main/java/com/codex/im/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/codex/im/chat/ChatScreen.kt`
- Modify: `docs/status/B11-image-message-design-status.md`

- [ ] Write failing ViewModel test that `sendImages` continues after one image fails.
- [ ] Add `sendImages` with a maximum of 9 images.
- [ ] Switch the picker to multi-select and prepare/send each selected image.
- [ ] Update B11 status notes.
- [ ] Run focused tests and compile.
