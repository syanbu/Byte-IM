# Message Toast Popup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a top floating in-app notification when a non-active conversation receives a new incoming TEXT or IMAGE message, with click-to-open-chat, close, and 4-second auto-dismiss behavior.

**Architecture:** `MessageRepository` freezes notification data at incoming-message time and emits `IncomingMessageAlert` through a `SharedFlow`. `MainActivity` collects the flow into a remembered `MessageAlertController`, which owns the single-alert replacement policy and auto-dismiss timer. `MessageAlertHost` renders a Compose overlay above the existing `NavHost`, reusing `AvatarImage`, existing ByteIM colors/dimensions, and `SelfHostedImRoute.Chat.createRoute(...)` navigation.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Kotlin Coroutines `SharedFlow` / `StateFlow`, JVM unit tests with JUnit 4 and `kotlinx.coroutines.test`.

---

## Reference

- Spec: `docs/superpowers/specs/2026-06-03-message-toast-popup-design.md`
- Working directory: `/home/buyansong/IM`

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/codex/im/alert/IncomingMessageAlert.kt` | New frozen alert data model emitted by the repository and rendered by the UI. |
| `app/src/main/java/com/codex/im/alert/MessageAlertPolicy.kt` | Pure functions for text/image previews, group previews, and `HH:mm` time formatting. |
| `app/src/main/java/com/codex/im/alert/MessageAlertController.kt` | Pure Kotlin controller for current alert state, single-alert replacement, auto-dismiss, dismiss, and click-open behavior. |
| `app/src/main/java/com/codex/im/alert/MessageToastPopup.kt` | Compose overlay host and popup card UI. |
| `app/src/main/java/com/codex/im/message/MessageRepository.kt` | Add `profileRepository` dependency, `messageAlerts` flow, and alert construction in `handleIncoming`. |
| `app/src/main/java/com/codex/im/MainActivity.kt` | Pass `profileRepository` into `MessageRepository`; collect alerts; render `MessageAlertHost` above `NavHost`; dismiss stale popup on background. |
| `app/src/test/java/com/codex/im/alert/MessageAlertPolicyTest.kt` | Unit tests for preview and time-format policy. |
| `app/src/test/java/com/codex/im/alert/MessageAlertControllerTest.kt` | Unit tests for state replacement, auto-dismiss, dismiss, and open behavior. |
| `app/src/test/java/com/codex/im/message/MessageRepositoryIncomingAlertTest.kt` | Repository tests for emit / no-emit branches and alert fields. |
| Existing tests that construct `MessageRepository` | Update fixtures to pass a `ProfileRepository`. |

No Android instrumented tests and no new dependencies are required.

## Task 1: Add Alert Model and Policy

**Files:**
- Create: `app/src/main/java/com/codex/im/alert/IncomingMessageAlert.kt`
- Create: `app/src/main/java/com/codex/im/alert/MessageAlertPolicy.kt`
- Create: `app/src/test/java/com/codex/im/alert/MessageAlertPolicyTest.kt`

- [ ] **Step 1: Create the failing policy tests**

Create `app/src/test/java/com/codex/im/alert/MessageAlertPolicyTest.kt` with:

```kotlin
package com.codex.im.alert

import com.codex.im.storage.MessageType
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAlertPolicyTest {
    @Test
    fun textPreviewKeepsEmptyText() {
        assertEquals("", MessageAlertPolicy.previewForText(""))
    }

    @Test
    fun textPreviewKeepsShortText() {
        assertEquals("短文本", MessageAlertPolicy.previewForText("短文本"))
    }

    @Test
    fun textPreviewKeepsTwentyCharacters() {
        val text = "12345678901234567890"

        assertEquals(text, MessageAlertPolicy.previewForText(text))
    }

    @Test
    fun textPreviewTruncatesAfterTwentyCharacters() {
        assertEquals("12345678901234567890...", MessageAlertPolicy.previewForText("123456789012345678901"))
    }

    @Test
    fun imagePreviewIsStable() {
        assertEquals("[图片]", MessageAlertPolicy.previewForImage())
    }

    @Test
    fun groupTextPreviewPrefixesSenderName() {
        assertEquals(
            "李四: 在吗？",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = "李四",
                senderId = "u4",
                content = "在吗？",
                type = MessageType.TEXT
            )
        )
    }

    @Test
    fun groupTextPreviewFallsBackToSenderId() {
        assertEquals(
            "u4: 在吗？",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = null,
                senderId = "u4",
                content = "在吗？",
                type = MessageType.TEXT
            )
        )
    }

    @Test
    fun groupImagePreviewPrefixesImageLabel() {
        assertEquals(
            "李四: [图片]",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = "李四",
                senderId = "u4",
                content = "ignored",
                type = MessageType.IMAGE
            )
        )
    }

    @Test
    fun groupPreviewTruncatesAfterPrefixAsOneString() {
        assertEquals(
            "李四: 1234567890123456...",
            MessageAlertPolicy.groupPreview(
                senderDisplayName = "李四",
                senderId = "u4",
                content = "1234567890123456789012345",
                type = MessageType.TEXT
            )
        )
    }

    @Test
    fun formatTimeUsesHourAndMinute() {
        assertEquals(
            "12:31",
            MessageAlertPolicy.formatTime(
                timestampMillis = 45_060_000L,
                timeZone = TimeZone.getTimeZone("UTC")
            )
        )
    }
}
```

- [ ] **Step 2: Run the policy tests to verify they fail**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.alert.MessageAlertPolicyTest --console=plain
```

Expected: FAIL with compile errors for unresolved `MessageAlertPolicy`.

- [ ] **Step 3: Create the alert data model**

Create `app/src/main/java/com/codex/im/alert/IncomingMessageAlert.kt` with:

```kotlin
package com.codex.im.alert

data class IncomingMessageAlert(
    val conversationId: String,
    val isGroup: Boolean,
    val title: String,
    val avatarUrl: String?,
    val preview: String,
    val rawTimestamp: Long
)
```

- [ ] **Step 4: Create the policy implementation**

Create `app/src/main/java/com/codex/im/alert/MessageAlertPolicy.kt` with:

```kotlin
package com.codex.im.alert

import com.codex.im.storage.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MessageAlertPolicy {
    private const val MAX_PREVIEW_CHARS = 20
    private const val ELLIPSIS = "..."
    private const val IMAGE_LABEL = "[图片]"

    fun previewForText(content: String): String = truncate(content)

    fun previewForImage(): String = IMAGE_LABEL

    fun groupPreview(
        senderDisplayName: String?,
        senderId: String,
        content: String,
        type: MessageType
    ): String {
        val senderLabel = senderDisplayName?.takeIf { it.isNotBlank() } ?: senderId
        val messagePreview = when (type) {
            MessageType.IMAGE -> previewForImage()
            else -> content
        }
        return truncate("$senderLabel: $messagePreview")
    }

    fun formatTime(
        timestampMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }.format(Date(timestampMillis))
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_PREVIEW_CHARS) {
            return text
        }
        return text.take(MAX_PREVIEW_CHARS) + ELLIPSIS
    }
}
```

- [ ] **Step 5: Run the policy tests to verify they pass**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.alert.MessageAlertPolicyTest --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/codex/im/alert/IncomingMessageAlert.kt app/src/main/java/com/codex/im/alert/MessageAlertPolicy.kt app/src/test/java/com/codex/im/alert/MessageAlertPolicyTest.kt
git commit -m "feat(alert): add incoming message alert policy"
```

## Task 2: Emit Incoming Alerts from `MessageRepository`

**Files:**
- Modify: `app/src/main/java/com/codex/im/message/MessageRepository.kt`
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt`
- Modify: existing test fixtures that call `MessageRepository(...)`
- Create: `app/src/test/java/com/codex/im/message/MessageRepositoryIncomingAlertTest.kt`

- [ ] **Step 1: Create the failing repository alert tests**

Create `app/src/test/java/com/codex/im/message/MessageRepositoryIncomingAlertTest.kt` with:

```kotlin
package com.codex.im.message

import com.codex.im.connection.ConnectionEvent
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.Conversation
import com.codex.im.storage.ConversationType
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.TransactionRunner
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRepositoryIncomingAlertTest {
    @Test
    fun incomingSingleTextEmitsAlertWhenNoConversationIsOpen() = runTest {
        val fixture = Fixture()
        fixture.profileDao.upsert(
            UserProfile(
                userId = "u2",
                phone = "u2",
                nickname = "张三",
                avatarUrl = "https://example.com/u2.png",
                avatarUpdatedAt = 1L,
                updatedAt = 1L
            )
        )

        fixture.repository.handlePacket(singleTextPacket(messageId = "m1", content = "你好"))

        val alert = withTimeout(1_000L) { fixture.repository.messageAlerts.first() }
        assertEquals("single:u1:u2", alert.conversationId)
        assertEquals(false, alert.isGroup)
        assertEquals("张三", alert.title)
        assertEquals("https://example.com/u2.png", alert.avatarUrl)
        assertEquals("你好", alert.preview)
        assertEquals(1_000L, alert.rawTimestamp)
    }

    @Test
    fun incomingSingleTextDoesNotEmitWhenConversationIsOpen() = runTest {
        val fixture = Fixture()
        fixture.repository.openConversationById("single:u1:u2", currentUserId = "u1", peerUserId = "u2", now = 1_000L)

        fixture.repository.handlePacket(singleTextPacket(messageId = "m1", content = "你好"))

        assertNull(fixture.repository.messageAlerts.replayCache.firstOrNull())
    }

    @Test
    fun duplicateIncomingMessageDoesNotEmitAlert() = runTest {
        val fixture = Fixture()
        val packet = singleTextPacket(messageId = "m1", content = "你好")

        fixture.repository.handlePacket(packet)
        withTimeout(1_000L) { fixture.repository.messageAlerts.first() }
        fixture.repository.handlePacket(packet)

        assertEquals(0, fixture.repository.messageAlerts.replayCache.size)
    }

    @Test
    fun incomingSingleTextFallsBackToSenderIdWhenProfileIsMissing() = runTest {
        val fixture = Fixture()

        fixture.repository.handlePacket(singleTextPacket(messageId = "m1", content = "你好"))

        val alert = withTimeout(1_000L) { fixture.repository.messageAlerts.first() }
        assertEquals("u2", alert.title)
        assertNull(alert.avatarUrl)
    }

    @Test
    fun incomingGroupTextUsesConversationAndSenderProfile() = runTest {
        val fixture = Fixture()
        fixture.profileDao.upsert(
            UserProfile(
                userId = "u2",
                phone = "u2",
                nickname = "李四",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L
            )
        )
        fixture.conversationDao.upsertConversation(
            Conversation(
                conversationId = "group:g1",
                peerId = "group:g1",
                peerName = "项目群",
                type = ConversationType.GROUP,
                title = "项目群",
                avatarUrl = "https://example.com/group.png",
                lastMessageId = "old",
                lastMessagePreview = "old",
                lastMessageTime = 1L,
                unreadCount = 0,
                updatedAt = 1L
            )
        )

        fixture.repository.handlePacket(groupTextPacket(messageId = "gm1", content = "收到"))

        val alert = withTimeout(1_000L) { fixture.repository.messageAlerts.first() }
        assertEquals("group:g1", alert.conversationId)
        assertEquals(true, alert.isGroup)
        assertEquals("项目群", alert.title)
        assertEquals("https://example.com/group.png", alert.avatarUrl)
        assertEquals("李四: 收到", alert.preview)
    }

    @Test
    fun incomingGroupTextFallsBackWhenConversationIsMissing() = runTest {
        val fixture = Fixture()

        fixture.repository.handlePacket(groupTextPacket(messageId = "gm1", content = "收到", groupName = null))

        val alert = withTimeout(1_000L) { fixture.repository.messageAlerts.first() }
        assertEquals("group:g1", alert.title)
        assertNull(alert.avatarUrl)
        assertEquals("u2: 收到", alert.preview)
    }

    @Test
    fun incomingImageUsesImagePreview() = runTest {
        val fixture = Fixture()

        fixture.repository.handlePacket(singleImagePacket(messageId = "img1"))

        val alert = withTimeout(1_000L) { fixture.repository.messageAlerts.first() }
        assertEquals("[图片]", alert.preview)
    }

    @Test
    fun incomingRecallNotificationDoesNotEmitAlert() = runTest {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECALL_NOTIFY.value,
                body = """{"messageId":"m1","recalledBy":"u2","recalledAt":1000}""".toByteArray()
            )
        )

        assertNull(fixture.repository.messageAlerts.replayCache.firstOrNull())
    }

    private class Fixture {
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val pendingDao = InMemoryPendingMessageDao()
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, FakeProfileApi())
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = pendingDao,
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator(),
            transactionRunner = TransactionRunner.immediate(),
            profileRepository = profileRepository
        )
    }

    private class FakeConnection : ImConnection {
        override val events = kotlinx.coroutines.flow.emptyFlow<ConnectionEvent>()
        override val state = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket) = Unit
    }

    private class FakeProfileApi : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure
        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure
        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult = ProfileBatchResult.Failure
        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.codex.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure
    }

    private fun singleTextPacket(messageId: String, content: String): ImPacket = ImPacket(
        cmd = ImCommand.RECEIVE_MESSAGE.value,
        body = """
            {
              "messageId":"$messageId",
              "senderId":"u2",
              "receiverId":"u1",
              "serverSeq":1,
              "content":"$content",
              "timestamp":1000,
              "type":"TEXT"
            }
        """.trimIndent().replace(Regex("\\s+"), "").toByteArray()
    )

    private fun singleImagePacket(messageId: String): ImPacket = ImPacket(
        cmd = ImCommand.RECEIVE_MESSAGE.value,
        body = """
            {
              "messageId":"$messageId",
              "senderId":"u2",
              "receiverId":"u1",
              "serverSeq":1,
              "content":"[图片]",
              "timestamp":1000,
              "type":"IMAGE",
              "image":{"imageUrl":"https://example.com/full.jpg","thumbnailUrl":"https://example.com/thumb.jpg"}
            }
        """.trimIndent().replace(Regex("\\s+"), "").toByteArray()
    )

    private fun groupTextPacket(
        messageId: String,
        content: String,
        groupName: String? = "项目群"
    ): ImPacket {
        val groupNameField = groupName?.let { ""","groupName":"$it"""" }.orEmpty()
        return ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """
                {
                  "messageId":"$messageId",
                  "senderId":"u2",
                  "receiverId":"u1",
                  "serverSeq":1,
                  "content":"$content",
                  "timestamp":1000,
                  "type":"TEXT",
                  "conversationType":"GROUP",
                  "conversationId":"group:g1",
                  "groupId":"g1"$groupNameField
                }
            """.trimIndent().replace(Regex("\\s+"), "").toByteArray()
        )
    }
}
```

- [ ] **Step 2: Run the repository alert tests to verify they fail**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.message.MessageRepositoryIncomingAlertTest --console=plain
```

Expected: FAIL with compile errors for missing `profileRepository` constructor parameter and missing `messageAlerts`.

- [ ] **Step 3: Add alert flow and `ProfileRepository` dependency**

In `app/src/main/java/com/codex/im/message/MessageRepository.kt`, add imports:

```kotlin
import com.codex.im.alert.IncomingMessageAlert
import com.codex.im.alert.MessageAlertPolicy
import com.codex.im.profile.ProfileRepository
```

Change the constructor by adding `profileRepository` after `transactionRunner`:

```kotlin
private val transactionRunner: TransactionRunner = TransactionRunner.immediate(),
private val profileRepository: ProfileRepository,
thumbnailCache: ChatThumbnailCache = NoopChatThumbnailCache,
```

Add the flow fields below `conversationUpdates`:

```kotlin
private val mutableMessageAlerts = MutableSharedFlow<IncomingMessageAlert>(extraBufferCapacity = 64)
val messageAlerts: SharedFlow<IncomingMessageAlert> = mutableMessageAlerts.asSharedFlow()
```

- [ ] **Step 4: Add alert construction helper**

In `MessageRepository.kt`, add this private helper near `handleIncoming`:

```kotlin
private fun buildIncomingMessageAlert(message: ChatMessage): IncomingMessageAlert? {
    if (message.type != MessageType.TEXT && message.type != MessageType.IMAGE) {
        return null
    }
    val isGroup = message.conversationType == ConversationType.GROUP
    val senderProfile = profileRepository.localProfile(message.senderId)
    val conversation = conversationDao.findConversation(message.conversationId)
    val title = if (isGroup) {
        conversation?.peerName?.takeIf { it.isNotBlank() }
            ?: message.groupName?.takeIf { it.isNotBlank() }
            ?: message.groupId?.takeIf { it.isNotBlank() }
            ?: message.conversationId
    } else {
        senderProfile?.nickname?.takeIf { it.isNotBlank() } ?: message.senderId
    }
    val avatarUrl = if (isGroup) {
        conversation?.avatarUrl
    } else {
        senderProfile?.avatarUrl
    }
    val preview = if (isGroup) {
        MessageAlertPolicy.groupPreview(
            senderDisplayName = senderProfile?.nickname,
            senderId = message.senderId,
            content = message.content,
            type = message.type
        )
    } else {
        when (message.type) {
            MessageType.IMAGE -> MessageAlertPolicy.previewForImage()
            else -> MessageAlertPolicy.previewForText(message.content)
        }
    }
    return IncomingMessageAlert(
        conversationId = message.conversationId,
        isGroup = isGroup,
        title = title,
        avatarUrl = avatarUrl,
        preview = preview,
        rawTimestamp = message.createdAt
    )
}
```

- [ ] **Step 5: Emit alerts from `handleIncoming`**

Inside the existing `if (inserted) { ... }` block in `handleIncoming`, after `notifyConversationChanged()`, add:

```kotlin
if (incrementUnread) {
    buildIncomingMessageAlert(message)?.let(mutableMessageAlerts::tryEmit)
}
```

This preserves the spec's trigger: `inserted == true && message.conversationId != activeConversationId`. Repeated packets have `inserted == false`, and messages in the currently open conversation have `incrementUnread == false`.

- [ ] **Step 6: Update production `MessageRepository` construction**

In `app/src/main/java/com/codex/im/MainActivity.kt`, update the existing `MessageRepository(...)` call to include:

```kotlin
profileRepository = profileRepository,
```

Place it with the other repository dependencies, before `thumbnailCache = thumbnailCache`.

- [ ] **Step 7: Update test fixtures that construct `MessageRepository`**

Search:

```bash
rg -n "MessageRepository\\(" app/src/test/java app/src/main/java
```

For each test fixture, create or reuse an `InMemoryUserProfileDao` and `ProfileRepository`, then pass:

```kotlin
profileRepository = profileRepository,
```

If a test already has a `profileRepository` field, pass that field. If it does not, add:

```kotlin
private val profileRepository = ProfileRepository(InMemoryUserProfileDao(), FakeProfileApi())
```

Use the local test file's existing `FakeProfileApi` if present. If none exists, add the smallest fake that implements `ProfileApi` and returns failures.

- [ ] **Step 8: Run the repository alert tests to verify they pass**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.message.MessageRepositoryIncomingAlertTest --console=plain
```

Expected: PASS.

- [ ] **Step 9: Run message repository tests for constructor fallout**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests "com.codex.im.message.*" --console=plain
```

Expected: PASS. If compile errors remain, update remaining `MessageRepository(...)` fixtures with `profileRepository`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/codex/im/message/MessageRepository.kt app/src/main/java/com/codex/im/MainActivity.kt app/src/test/java
git commit -m "feat(alert): emit incoming message alerts from repository"
```

## Task 3: Add `MessageAlertController`

**Files:**
- Create: `app/src/main/java/com/codex/im/alert/MessageAlertController.kt`
- Create: `app/src/test/java/com/codex/im/alert/MessageAlertControllerTest.kt`

- [ ] **Step 1: Create the failing controller tests**

Create `app/src/test/java/com/codex/im/alert/MessageAlertControllerTest.kt` with:

```kotlin
package com.codex.im.alert

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageAlertControllerTest {
    @Test
    fun showMakesAlertCurrent() = runTest {
        val controller = controller()
        val alert = alert("a")

        controller.show(alert)
        runCurrent()

        assertEquals(alert, controller.currentAlert.value)
    }

    @Test
    fun alertAutoDismissesAfterFourSeconds() = runTest {
        val controller = controller()

        controller.show(alert("a"))
        advanceTimeBy(3_999L)
        runCurrent()
        assertEquals("a", controller.currentAlert.value?.conversationId)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(controller.currentAlert.value)
    }

    @Test
    fun newAlertReplacesOldAlertAndResetsTimer() = runTest {
        val controller = controller()

        controller.show(alert("a"))
        advanceTimeBy(2_000L)
        controller.show(alert("b"))
        runCurrent()
        assertEquals("b", controller.currentAlert.value?.conversationId)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals("b", controller.currentAlert.value?.conversationId)

        advanceTimeBy(2_000L)
        runCurrent()
        assertNull(controller.currentAlert.value)
    }

    @Test
    fun dismissClearsCurrentAlert() = runTest {
        val controller = controller()
        controller.show(alert("a"))
        runCurrent()

        controller.dismiss()

        assertNull(controller.currentAlert.value)
    }

    @Test
    fun openCurrentDismissesAndCallsCallback() = runTest {
        val controller = controller()
        val opened = mutableListOf<String>()
        controller.show(alert("a"))
        runCurrent()

        controller.openCurrent { opened += it }

        assertEquals(listOf("a"), opened)
        assertNull(controller.currentAlert.value)
    }

    @Test
    fun openCurrentDoesNothingWhenThereIsNoAlert() = runTest {
        val controller = controller()
        val opened = mutableListOf<String>()

        controller.openCurrent { opened += it }

        assertEquals(emptyList<String>(), opened)
        assertNull(controller.currentAlert.value)
    }

    private fun TestScope.controller(): MessageAlertController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessageAlertController(
            scope = TestScope(dispatcher),
            autoDismissMillis = 4_000L
        )
    }

    private fun alert(conversationId: String): IncomingMessageAlert = IncomingMessageAlert(
        conversationId = conversationId,
        isGroup = false,
        title = "张三",
        avatarUrl = null,
        preview = "你好",
        rawTimestamp = 1_000L
    )
}
```

- [ ] **Step 2: Run controller tests to verify they fail**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.alert.MessageAlertControllerTest --console=plain
```

Expected: FAIL with unresolved `MessageAlertController`.

- [ ] **Step 3: Create the controller implementation**

Create `app/src/main/java/com/codex/im/alert/MessageAlertController.kt` with:

```kotlin
package com.codex.im.alert

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MessageAlertController(
    private val scope: CoroutineScope,
    private val autoDismissMillis: Long = 4_000L
) {
    private val mutableCurrentAlert = MutableStateFlow<IncomingMessageAlert?>(null)
    val currentAlert: StateFlow<IncomingMessageAlert?> = mutableCurrentAlert.asStateFlow()

    private var autoDismissJob: Job? = null

    fun show(alert: IncomingMessageAlert) {
        autoDismissJob?.cancel()
        mutableCurrentAlert.value = alert
        autoDismissJob = scope.launch {
            delay(autoDismissMillis)
            mutableCurrentAlert.value = null
        }
    }

    fun dismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        mutableCurrentAlert.value = null
    }

    fun openCurrent(onOpenConversation: (String) -> Unit) {
        val alert = mutableCurrentAlert.value ?: return
        dismiss()
        onOpenConversation(alert.conversationId)
    }
}
```

- [ ] **Step 4: Run controller tests to verify they pass**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.alert.MessageAlertControllerTest --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/alert/MessageAlertController.kt app/src/test/java/com/codex/im/alert/MessageAlertControllerTest.kt
git commit -m "feat(alert): add message alert controller"
```

## Task 4: Add Compose Message Toast Popup UI

**Files:**
- Create: `app/src/main/java/com/codex/im/alert/MessageToastPopup.kt`

- [ ] **Step 1: Create the popup UI file**

Create `app/src/main/java/com/codex/im/alert/MessageToastPopup.kt` with:

```kotlin
package com.codex.im.alert

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors

@Composable
fun MessageAlertHost(
    controller: MessageAlertController,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val alert by controller.currentAlert.collectAsState()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = alert != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            alert?.let { currentAlert ->
                MessageToastPopup(
                    alert = currentAlert,
                    onClick = { controller.openCurrent(onOpenConversation) },
                    onDismiss = controller::dismiss
                )
            }
        }
    }
}

@Composable
private fun MessageToastPopup(
    alert: IncomingMessageAlert,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = ByteImColors.Surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = 56.dp)
            .fillMaxWidth(0.85f)
            .widthIn(min = 280.dp, max = 360.dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .background(ByteImColors.Surface)
                .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                avatarUrl = alert.avatarUrl,
                displayName = alert.title,
                isGroup = alert.isGroup,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ByteImColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = MessageAlertPolicy.formatTime(alert.rawTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ByteImColors.TextSecondary,
                        maxLines = 1
                    )
                }
                Text(
                    text = alert.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = ByteImColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.titleMedium,
                    color = ByteImColors.TextSecondary
                )
            }
        }
    }
}
```

- [ ] **Step 2: Compile Kotlin to verify the UI file**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/codex/im/alert/MessageToastPopup.kt
git commit -m "feat(alert): add message toast popup ui"
```

## Task 5: Integrate Popup Host in `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/codex/im/MainActivity.kt`

- [ ] **Step 1: Add imports**

In `MainActivity.kt`, add:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codex.im.alert.MessageAlertController
import com.codex.im.alert.MessageAlertHost
```

If any lifecycle imports are already present, keep one copy only.

- [ ] **Step 2: Add the controller and alert collection**

Inside `AuthenticatedImNavHost`, after `val activity = LocalContext.current.findActivity()`, add:

```kotlin
val lifecycleOwner = LocalLifecycleOwner.current
val alertScope = rememberCoroutineScope()
val messageAlertController = remember(session.userId) {
    MessageAlertController(scope = alertScope)
}
```

After the existing `DisposableEffect(messagePacketProcessor, messageOutboxWorker, unreadBadgeController) { ... }`, add:

```kotlin
LaunchedEffect(messageRepository, messageAlertController) {
    messageRepository.messageAlerts.collect(messageAlertController::show)
}

DisposableEffect(lifecycleOwner, messageAlertController) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            messageAlertController.dismiss()
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
    }
}
```

The `ON_STOP` dismissal prevents a stale in-app popup from reappearing when the user returns from background.

- [ ] **Step 3: Wrap `NavHost` with an overlay `Box`**

Inside the existing:

```kotlin
Scaffold(
    containerColor = ByteImColors.AppBackground
) { innerPadding ->
    NavHost(
        ...
    ) {
        ...
    }
}
```

change the content lambda to this shape:

```kotlin
Scaffold(
    containerColor = ByteImColors.AppBackground
) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = SelfHostedImRoute.Conversations.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            // Keep all existing composable(...) destinations here unchanged.
        }
        MessageAlertHost(
            controller = messageAlertController,
            onOpenConversation = { conversationId ->
                SelfHostedImRoute.Chat.createRoute(conversationId)
                    ?.let(navController::navigateToChat)
            },
            modifier = Modifier.padding(innerPadding)
        )
    }
}
```

Do not change any existing destination bodies while wrapping the `NavHost`.

- [ ] **Step 4: Compile Kotlin**

Run:

```bash
bash ./gradlew :app:compileDebugKotlin --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/im/MainActivity.kt
git commit -m "feat(alert): show incoming message popup in nav host"
```

## Task 6: Final Verification and Manual Checklist

**Files:**
- No required file changes.

- [ ] **Step 1: Run the alert unit tests**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests "com.codex.im.alert.*" --console=plain
```

Expected: PASS.

- [ ] **Step 2: Run message repository alert tests**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --tests com.codex.im.message.MessageRepositoryIncomingAlertTest --console=plain
```

Expected: PASS.

- [ ] **Step 3: Run the full JVM test suite**

Run:

```bash
bash ./gradlew :app:testDebugUnitTest --console=plain
```

Expected: PASS.

- [ ] **Step 4: Run a debug build**

Run:

```bash
bash ./gradlew :app:assembleDebug --console=plain
```

Expected: PASS.

- [ ] **Step 5: Manual verification checklist**

Use two logged-in users or the existing mock-server workflow:

```markdown
- [ ] On Messages tab, user B sends user A a single-chat TEXT message: popup appears at the top with B's avatar/name/content/time.
- [ ] Tap the popup: app navigates to that chat and the conversation unread count is cleared by the existing chat-open path.
- [ ] On Messages tab, user B sends an IMAGE message: popup preview is `[图片]`.
- [ ] On Contacts tab, a new message arrives: popup appears above the current screen.
- [ ] On Me tab, a new message arrives: popup appears above the current screen.
- [ ] While user A is already viewing B's chat, B sends a message in that same conversation: no popup appears.
- [ ] While user A is viewing B's chat, user C sends a message in another conversation: popup appears for C.
- [ ] Group message arrives: popup title is the group name, preview is `sender: content`.
- [ ] Tap the close button: popup disappears and unread count remains incremented.
- [ ] Do nothing for 4 seconds: popup disappears automatically.
- [ ] User B sends multiple messages quickly: only the latest message is shown; each new message replaces the old popup and resets the 4-second timer.
- [ ] Put the app in background while popup is visible, then return: the old popup is gone.
```

- [ ] **Step 6: Commit any verification-only doc update if one was added**

No verification doc is required by this plan. If the implementer chooses to add one under `docs/status/`, commit it separately:

```bash
git add docs/status
git commit -m "docs(alert): record message popup manual verification"
```

## Implementation Notes

- Use `ProfileRepository.localProfile(userId)` rather than `cachedProfile`; `cachedProfile` does not exist in the current codebase.
- Keep the alert data frozen when `MessageRepository.handleIncoming` runs. The UI should not asynchronously refresh title/avatar/preview after the popup is visible.
- The alert policy is intentionally single-item replacement: a new alert replaces the old alert immediately and restarts the 4-second timer. This includes multiple rapid messages from the same sender.
- The close button only dismisses the popup. It must not clear unread counts.
- Click-to-open should reuse `SelfHostedImRoute.Chat.createRoute(conversationId)?.let(navController::navigateToChat)` rather than string-concatenating a route.
- Do not add system notifications, stacked notifications, swipe gestures, or long-press actions in this slice.

