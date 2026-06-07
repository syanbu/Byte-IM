# B11 Image Message Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add single-image chat messaging with OSS upload, upload-vs-send failure separation, bubble thumbnail display, and full-image preview.

**Architecture:** Keep image-message delivery as a two-phase pipeline: local message creation plus OSS upload, then reuse the existing IM outbox only after upload success. Extend the shared message model so text and image messages persist through the same SQLite, repository, ACK, and conversation-summary paths while UI renders image-specific states from the stored rows.

**Tech Stack:** Kotlin, SQLiteOpenHelper, Jetpack Compose, OkHttp, Gson, Coroutines, Coil Compose, Java mock-server, JUnit4

---

### Task 1: Expand Storage Model For Image Messages

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/storage/StorageModels.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/MessageDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/AndroidMessageDao.kt`
- Test: `app/src/test/java/com/buyansong/im/storage/MessageDaoContractTest.kt`

- [ ] **Step 1: Write the failing DAO tests**

```kotlin
@Test
fun insertAndQueryImageMessagePersistsImageFields() {
    val message = ChatMessage(
        messageId = "m-image-1",
        conversationId = "single:a:b",
        senderId = "a",
        receiverId = "b",
        clientSeq = 1L,
        serverSeq = null,
        type = MessageType.IMAGE,
        content = "[图片]",
        imageUrl = "https://oss.example.com/origin.jpg",
        thumbnailUrl = "https://oss.example.com/thumb.jpg",
        imageWidth = 1080,
        imageHeight = 720,
        mimeType = "image/jpeg",
        fileSizeBytes = 123_456L,
        localOriginalPath = "cache/origin.jpg",
        localThumbnailPath = "cache/thumb.jpg",
        status = MessageStatus.UPLOADING,
        direction = MessageDirection.OUTGOING,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    assertTrue(dao.insertOrIgnore(message))

    val loaded = dao.queryPage("single:a:b", null, 20).single()
    assertEquals(MessageType.IMAGE, loaded.type)
    assertEquals("https://oss.example.com/origin.jpg", loaded.imageUrl)
    assertEquals("https://oss.example.com/thumb.jpg", loaded.thumbnailUrl)
    assertEquals("cache/thumb.jpg", loaded.localThumbnailPath)
    assertEquals(MessageStatus.UPLOADING, loaded.status)
}

@Test
fun updateImageUploadResultTransitionsToSendingWithoutCreatingNewRow() {
    val original = imageMessage(status = MessageStatus.UPLOADING)
    dao.insertOrIgnore(original)

    assertTrue(
        dao.updateImageUploadResult(
            messageId = original.messageId,
            imageUrl = "https://oss.example.com/origin.jpg",
            thumbnailUrl = "https://oss.example.com/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            fileSizeBytes = 345_678L,
            status = MessageStatus.SENDING,
            updatedAt = 2000L
        )
    )

    val updated = dao.findByMessageId(original.messageId)!!
    assertEquals(MessageStatus.SENDING, updated.status)
    assertEquals("https://oss.example.com/origin.jpg", updated.imageUrl)
    assertEquals(345_678L, updated.fileSizeBytes)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.MessageDaoContractTest --console=plain`

Expected: FAIL because `ChatMessage` has no image fields and `MessageDao` lacks image update/query APIs.

- [ ] **Step 3: Write minimal implementation**

```kotlin
enum class MessageType {
    TEXT,
    IMAGE
}

data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val clientSeq: Long,
    val serverSeq: Long?,
    val type: MessageType,
    val content: String,
    val imageUrl: String?,
    val thumbnailUrl: String?,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val localOriginalPath: String?,
    val localThumbnailPath: String?,
    val status: MessageStatus,
    val direction: MessageDirection,
    val createdAt: Long,
    val updatedAt: Long
)

interface MessageDao {
    fun insertOrIgnore(message: ChatMessage): Boolean
    fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage>
    fun findByMessageId(messageId: String): ChatMessage?
    fun updateImageUploadResult(
        messageId: String,
        imageUrl: String,
        thumbnailUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        mimeType: String,
        fileSizeBytes: Long,
        status: MessageStatus,
        updatedAt: Long
    ): Boolean
    fun markStatus(messageId: String, status: MessageStatus, updatedAt: Long): Boolean
    fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean
    fun markFailed(messageId: String, updatedAt: Long): Boolean
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.MessageDaoContractTest --console=plain`

Expected: PASS with image field persistence and update coverage green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/buyansong/im/storage/StorageModels.kt app/src/main/java/com/buyansong/im/storage/ImDatabaseHelper.kt app/src/main/java/com/buyansong/im/storage/MessageDao.kt app/src/main/java/com/buyansong/im/storage/AndroidMessageDao.kt app/src/test/java/com/buyansong/im/storage/MessageDaoContractTest.kt
git commit -m "feat: expand storage for image messages"
```

### Task 2: Add Chat Image Upload Target API

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/profile/AvatarUploadApi.kt`
- Create: `app/src/main/java/com/buyansong/im/message/ImageUploadModels.kt`
- Create: `app/src/main/java/com/buyansong/im/message/ImageUploadJsonParser.kt`
- Create: `app/src/main/java/com/buyansong/im/message/OkHttpImageUploadApi.kt`
- Test: `app/src/test/java/com/buyansong/im/message/ImageUploadJsonParserTest.kt`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/oss/OssUploadService.java`
- Test: `mock-server/src/test/java/com/buyansong/imserver/oss/OssUploadServiceTest.java`

- [ ] **Step 1: Write failing parser and server tests**

```kotlin
@Test
fun parseMessageImageUploadTargets() {
    val json = """
        {"code":0,"message":"ok","data":{
          "messageId":"m-1",
          "thumbnail":{"objectKey":"chat-images/u/m-1/thumb.jpg","uploadUrl":"https://signed/thumb","publicUrl":"https://public/thumb.jpg"},
          "original":{"objectKey":"chat-images/u/m-1/origin.jpg","uploadUrl":"https://signed/origin","publicUrl":"https://public/origin.jpg"},
          "expiresAt":3000
        }}
    """.trimIndent()

    val result = ImageUploadJsonParser.parseTargets(json)
    assertTrue(result is ImageUploadTargetsResult.Success)
}
```

```java
@Test
public void messageImageUploadTargetsIncludeThumbnailAndOriginal() {
    OssUploadService service = new OssUploadService(config());

    String json = service.messageImageUploadTargets("13800138000", "m-1", "image/jpeg");

    JsonObject data = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data");
    assertEquals("m-1", data.get("messageId").getAsString());
    assertTrue(data.getAsJsonObject("thumbnail").get("publicUrl").getAsString().contains("/thumb.jpg"));
    assertTrue(data.getAsJsonObject("original").get("publicUrl").getAsString().contains("/origin.jpg"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.ImageUploadJsonParserTest --console=plain`

Run: `mvn -q test -Dtest=OssUploadServiceTest` in `mock-server`

Expected: FAIL because chat-image upload-target types and server API do not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
data class ImageUploadTargets(
    val messageId: String,
    val thumbnail: AvatarUploadTarget,
    val original: AvatarUploadTarget,
    val expiresAt: Long
)

interface ImageUploadApi {
    suspend fun requestUploadTargets(
        accessToken: String,
        messageId: String,
        contentType: String
    ): ImageUploadTargetsResult

    suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult
}
```

```java
public String messageImageUploadTargets(String userId, String messageId, String contentType) {
    String basePath = "chat-images/" + safePathSegment(userId) + "/" + safePathSegment(messageId);
    JsonObject data = new JsonObject();
    data.addProperty("messageId", messageId);
    data.add("thumbnail", signedTarget(basePath + "/thumb.jpg", contentType));
    data.add("original", signedTarget(basePath + "/origin.jpg", contentType));
    data.addProperty("expiresAt", expiresAtMillis);
    return success(data);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.ImageUploadJsonParserTest --console=plain`

Run: `mvn -q test -Dtest=OssUploadServiceTest` in `mock-server`

Expected: PASS with both Android parser and backend target generation green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/ImageUploadModels.kt app/src/main/java/com/buyansong/im/message/ImageUploadJsonParser.kt app/src/main/java/com/buyansong/im/message/OkHttpImageUploadApi.kt app/src/test/java/com/buyansong/im/message/ImageUploadJsonParserTest.kt mock-server/src/main/java/com/buyansong/imserver/netty/HttpAuthHandler.java mock-server/src/main/java/com/buyansong/imserver/oss/OssUploadService.java mock-server/src/test/java/com/buyansong/imserver/oss/OssUploadServiceTest.java
git commit -m "feat: add chat image upload target API"
```

### Task 3: Split Upload Failure From Send Failure In Repository

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/ConversationDao.kt`
- Modify: `app/src/main/java/com/buyansong/im/storage/AndroidConversationDao.kt`
- Test: `app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt`

- [ ] **Step 1: Write the failing repository tests**

```kotlin
@Test
fun createLocalImageMessageStoresUploadingRowWithoutPendingEntry() {
    val message = repository.createLocalImageMessage(
        senderId = "13800138000",
        receiverId = "13900139000",
        localOriginalPath = "cache/origin.jpg",
        localThumbnailPath = "cache/thumb.jpg",
        mimeType = "image/jpeg",
        now = 1000L
    )

    assertEquals(MessageStatus.UPLOADING, message.status)
    assertNull(pendingDao.findByMessageId(message.messageId))
    assertEquals("[图片]", conversationDao.listConversations(10).single().lastMessagePreview)
}

@Test
fun completeImageUploadCreatesPendingAndSendsPacket() {
    val message = repository.createLocalImageMessage(...)

    repository.completeImageUploadAndQueueSend(
        messageId = message.messageId,
        imageUrl = "https://oss.example.com/origin.jpg",
        thumbnailUrl = "https://oss.example.com/thumb.jpg",
        imageWidth = 1080,
        imageHeight = 720,
        mimeType = "image/jpeg",
        fileSizeBytes = 111_222L,
        now = 2000L
    )

    assertEquals(MessageStatus.SENDING, messageDao.findByMessageId(message.messageId)?.status)
    assertNotNull(pendingDao.findByMessageId(message.messageId))
    assertEquals(ImCommand.SEND_MESSAGE.value, connection.sentPackets.single().cmd)
}

@Test
fun markImageUploadFailedDoesNotCreatePendingEntry() {
    val message = repository.createLocalImageMessage(...)

    repository.markImageUploadFailed(message.messageId, 3000L)

    assertEquals(MessageStatus.UPLOAD_FAILED, messageDao.findByMessageId(message.messageId)?.status)
    assertNull(pendingDao.findByMessageId(message.messageId))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --console=plain`

Expected: FAIL because image-message repository APIs and conversation preview branching do not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
fun createLocalImageMessage(...): ChatMessage {
    val message = ChatMessage(
        messageId = messageIdGenerator.next(senderId, now),
        conversationId = conversationIdFor(senderId, receiverId),
        senderId = senderId,
        receiverId = receiverId,
        clientSeq = seqGenerator.next(conversationIdFor(senderId, receiverId)),
        serverSeq = null,
        type = MessageType.IMAGE,
        content = IMAGE_PLACEHOLDER_CONTENT,
        imageUrl = null,
        thumbnailUrl = null,
        imageWidth = null,
        imageHeight = null,
        mimeType = mimeType,
        fileSizeBytes = null,
        localOriginalPath = localOriginalPath,
        localThumbnailPath = localThumbnailPath,
        status = MessageStatus.UPLOADING,
        direction = MessageDirection.OUTGOING,
        createdAt = now,
        updatedAt = now
    )
    transactionRunner.runInTransaction {
        messageDao.insertOrIgnore(message)
        conversationDao.upsertFromMessage(message, incrementUnread = false)
    }
    notifyConversationChanged()
    return message
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --console=plain`

Expected: PASS with upload-vs-send failure split verified.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/MessageRepository.kt app/src/main/java/com/buyansong/im/storage/ConversationDao.kt app/src/main/java/com/buyansong/im/storage/AndroidConversationDao.kt app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt
git commit -m "feat: separate image upload and send reliability"
```

### Task 4: Extend Packet JSON And Incoming Persistence For Image Messages

**Files:**
- Modify: `app/src/main/java/com/buyansong/im/message/MessageRepository.kt`
- Modify: `mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java`
- Test: `app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt`
- Test: `mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterTest.java`

- [ ] **Step 1: Write failing tests for outgoing JSON and incoming persistence**

```kotlin
@Test
fun incomingImageMessagePersistsTypeAndImageFields() {
    repository.handlePacket(
        imageReceivePacket(
            messageId = "m-in-1",
            imageUrl = "https://oss.example.com/origin.jpg",
            thumbnailUrl = "https://oss.example.com/thumb.jpg"
        )
    )

    val stored = messageDao.findByMessageId("m-in-1")!!
    assertEquals(MessageType.IMAGE, stored.type)
    assertEquals("https://oss.example.com/thumb.jpg", stored.thumbnailUrl)
    assertEquals(MessageStatus.RECEIVED, stored.status)
}
```

```java
@Test
public void duplicateImageMessageIdReturnsOriginalAckOnly() {
    router.handleSendMessage("13800113800", imageSendPacket("m-image-1"));
    router.handleSendMessage("13800113800", imageSendPacket("m-image-1"));

    assertEquals(1, receiverClient.sentPackets().size());
    assertEquals(2, senderClient.sentPackets().size());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --console=plain`

Run: `mvn -q -Dtest=MessageRouterTest test` in `mock-server`

Expected: FAIL because image payload JSON is not built or parsed.

- [ ] **Step 3: Write minimal implementation**

```kotlin
private fun ChatMessage.toSendBody(): String {
    return if (type == MessageType.IMAGE) {
        """
        {
          "messageId":"${messageId.escapeJson()}",
          "conversationId":"${conversationId.escapeJson()}",
          "senderId":"${senderId.escapeJson()}",
          "receiverId":"${receiverId.escapeJson()}",
          "clientSeq":$clientSeq,
          "type":"IMAGE",
          "content":"${content.escapeJson()}",
          "image":{
            "imageUrl":"${imageUrl!!.escapeJson()}",
            "thumbnailUrl":"${thumbnailUrl!!.escapeJson()}",
            "width":$imageWidth,
            "height":$imageHeight,
            "mimeType":"${mimeType!!.escapeJson()}",
            "sizeBytes":$fileSizeBytes
          },
          "timestamp":$createdAt
        }
        """.trimIndent()
    } else {
        existingTextBody()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.message.MessageRepositoryTest --console=plain`

Run: `mvn -q -Dtest=MessageRouterTest test` in `mock-server`

Expected: PASS with image send/receive JSON and idempotent retry behavior preserved.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/MessageRepository.kt app/src/test/java/com/buyansong/im/message/MessageRepositoryTest.kt mock-server/src/main/java/com/buyansong/imserver/session/MessageRouter.java mock-server/src/test/java/com/buyansong/imserver/session/MessageRouterTest.java
git commit -m "feat: persist image payloads through IM packets"
```

### Task 5: Orchestrate Image Pick, Upload, And Retry In ChatViewModel

**Files:**
- Create: `app/src/main/java/com/buyansong/im/message/ChatImageCompressor.kt`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt`

- [ ] **Step 1: Write the failing view-model tests**

```kotlin
@Test
fun sendImageCreatesUploadingMessageThenQueuesSendAfterUploadSuccess() = runTest {
    val fixture = imageFixture(this)

    fixture.viewModel.sendImage(fakeSelectedImage())

    val message = fixture.messageDao.queryPage("single:13800113800:13900139000", null, 20).single()
    assertEquals(MessageStatus.SENDING, message.status)
    assertEquals("https://oss.example.com/thumb.jpg", message.thumbnailUrl)
    assertEquals(1, fixture.connection.sentPackets.size)
}

@Test
fun sendImageMarksUploadFailedWhenOssUploadFails() = runTest {
    val fixture = imageFixture(this, uploadApi = failingUploadApi())

    fixture.viewModel.sendImage(fakeSelectedImage())

    val message = fixture.messageDao.queryPage("single:13800113800:13900139000", null, 20).single()
    assertEquals(MessageStatus.UPLOAD_FAILED, message.status)
    assertTrue(fixture.connection.sentPackets.isEmpty())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest --console=plain`

Expected: FAIL because `sendImage` orchestration and image upload dependencies do not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
suspend fun sendImage(selected: SelectedChatImage, now: Long = System.currentTimeMillis()) {
    withContext(dispatcher) {
        val local = repository.createLocalImageMessage(
            senderId = session.userId,
            receiverId = mutableState.value.peerId,
            localOriginalPath = selected.localOriginalPath,
            localThumbnailPath = selected.localThumbnailPath,
            mimeType = selected.mimeType,
            now = now
        )
        val targets = imageUploadApi.requestUploadTargets(session.accessToken, local.messageId, selected.mimeType)
        if (targets !is ImageUploadTargetsResult.Success) {
            repository.markImageUploadFailed(local.messageId, now)
            refreshKeepingHistory()
            return@withContext
        }
        val thumbPut = imageUploadApi.upload(targets.targets.thumbnail.uploadUrl, selected.mimeType, selected.thumbnailBytes)
        val originalPut = imageUploadApi.upload(targets.targets.original.uploadUrl, selected.mimeType, selected.originalBytes)
        if (thumbPut is AvatarPutResult.Success && originalPut is AvatarPutResult.Success) {
            repository.completeImageUploadAndQueueSend(
                messageId = local.messageId,
                imageUrl = targets.targets.original.publicUrl,
                thumbnailUrl = targets.targets.thumbnail.publicUrl,
                imageWidth = selected.width,
                imageHeight = selected.height,
                mimeType = selected.mimeType,
                fileSizeBytes = selected.originalBytes.size.toLong(),
                now = now
            )
        } else {
            repository.markImageUploadFailed(local.messageId, now)
        }
        refreshKeepingHistory()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatViewModelTest --console=plain`

Expected: PASS with upload success and upload failure flows covered.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/buyansong/im/message/ChatImageCompressor.kt app/src/main/java/com/buyansong/im/chat/ChatViewModel.kt app/src/test/java/com/buyansong/im/chat/ChatViewModelTest.kt
git commit -m "feat: orchestrate image upload from chat view model"
```

### Task 6: Render Image Bubbles And Preview With Coil

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/java/com/buyansong/im/chat/ChatScreen.kt`
- Create: `app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt`
- Create: `app/src/main/java/com/buyansong/im/chat/ChatImagePreviewScreen.kt`
- Test: `app/src/test/java/com/buyansong/im/chat/ChatDisplayPolicyTest.kt`

- [ ] **Step 1: Write the failing display-policy test**

```kotlin
@Test
fun imageMessagePreviewUsesPlaceholderCopy() {
    val preview = ChatDisplayPolicy.previewText(
        ChatMessage(
            messageId = "m-1",
            conversationId = "single:a:b",
            senderId = "a",
            receiverId = "b",
            clientSeq = 1L,
            serverSeq = null,
            type = MessageType.IMAGE,
            content = "[图片]",
            imageUrl = "https://oss.example.com/origin.jpg",
            thumbnailUrl = "https://oss.example.com/thumb.jpg",
            imageWidth = 500,
            imageHeight = 300,
            mimeType = "image/jpeg",
            fileSizeBytes = 100L,
            localOriginalPath = null,
            localThumbnailPath = null,
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING,
            createdAt = 1L,
            updatedAt = 1L
        ),
        isOutgoing = true
    )

    assertEquals("You: [图片]", preview)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatDisplayPolicyTest --console=plain`

Expected: FAIL because image preview behavior and image bubble support are not wired.

- [ ] **Step 3: Write minimal implementation**

```groovy
implementation "io.coil-kt:coil-compose:2.7.0"
```

```kotlin
@Composable
fun ChatImageBubble(
    message: ChatMessage,
    onOpenPreview: (ChatMessage) -> Unit
) {
    val model = message.localThumbnailPath ?: message.thumbnailUrl ?: message.localOriginalPath ?: message.imageUrl
    AsyncImage(
        model = model,
        contentDescription = message.content,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = message.imageUrl != null || message.localOriginalPath != null) {
                onOpenPreview(message)
            }
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.chat.ChatDisplayPolicyTest --console=plain`

Expected: PASS and project compiles with Coil dependency added.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/java/com/buyansong/im/chat/ChatScreen.kt app/src/main/java/com/buyansong/im/chat/ChatImageBubble.kt app/src/main/java/com/buyansong/im/chat/ChatImagePreviewScreen.kt app/src/test/java/com/buyansong/im/chat/ChatDisplayPolicyTest.kt
git commit -m "feat: render chat images with coil"
```

### Task 7: Final Verification And Status Docs

**Files:**
- Modify: `docs/status/B11-image-message-design-status.md`
- Modify: `docs/DEVELOPMENT_STATUS.md`

- [ ] **Step 1: Run focused Android tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.buyansong.im.storage.MessageDaoContractTest --tests com.buyansong.im.message.MessageRepositoryTest --tests com.buyansong.im.chat.ChatViewModelTest --tests com.buyansong.im.chat.ChatDisplayPolicyTest --console=plain`

Expected: PASS with image-message storage, repository, and chat-flow coverage green.

- [ ] **Step 2: Run focused mock-server tests**

Run: `mvn -q test -Dtest=OssUploadServiceTest,MessageRouterTest` in `mock-server`

Expected: PASS with upload-target generation and image-message router behavior green.

- [ ] **Step 3: Run Android regression build**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain`

Run: `.\gradlew.bat :app:assembleDebug --console=plain`

Expected: PASS with no regression in existing text-message flows.

- [ ] **Step 4: Update status docs**

```markdown
- B11 image-message design moved from approved design to implementation in progress or complete.
- Reliability split is documented: `UPLOAD_FAILED` stays outside outbox, `FAILED` reuses pending send retry.
- `coil-compose` is now used for chat-image loading.
```

- [ ] **Step 5: Commit**

```bash
git add docs/status/B11-image-message-design-status.md docs/DEVELOPMENT_STATUS.md
git commit -m "docs: record b11 image message implementation status"
```

## Self-Review

- Spec coverage: storage model, OSS upload target, reliability split, image packet shape, bubble thumbnail, preview screen, and deferred items are all covered by Tasks 1-7.
- Placeholder scan: removed generic TODO wording; every task names exact files, commands, and expected behavior.
- Type consistency: the plan uses `MessageType.IMAGE`, `MessageStatus.UPLOADING`, `markImageUploadFailed`, and `completeImageUploadAndQueueSend` consistently across tasks.

Plan complete and saved to `docs/superpowers/plans/2026-05-29-b11-image-message.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
