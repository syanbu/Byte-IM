package com.codex.im.chat

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.ChatThumbnailCache
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.ImageUploadApi
import com.codex.im.message.ImageUploadTargets
import com.codex.im.message.ImageUploadTargetsResult
import com.codex.im.message.SelectedChatImage
import com.codex.im.message.MessageRepository
import com.codex.im.message.NoopChatThumbnailCache
import com.codex.im.message.SeqGenerator
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.profile.AvatarPutResult
import com.codex.im.profile.AvatarUploadTarget
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.group.GroupCreateResult
import com.codex.im.group.GroupListResult
import com.codex.im.group.GroupRepository
import com.codex.im.group.GroupResult
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.Conversation
import com.codex.im.storage.ConversationType
import com.codex.im.storage.GroupInfo
import com.codex.im.storage.GroupMember
import com.codex.im.storage.GroupMemberRole
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryGroupDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatViewModelTest {
    @Test
    fun startConnectsWebSocketWithSessionToken() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()

        assertEquals("mock-token-13800113800", fixture.connection.connectedToken)
    }

    @Test
    fun defaultChatStateDoesNotHardcodeDemoPeer() = runTest {
        val fixture = Fixture(this, initialPeerId = null)

        assertEquals("", fixture.viewModel.state.value.peerId)
    }

    @Test
    fun sendTextRefreshesVisibleMessages() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendText("hello", now = 1_000L)

        assertEquals(listOf("hello"), fixture.viewModel.state.value.messages.map { it.content })
        assertEquals("13900113900", fixture.viewModel.state.value.peerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recallMessageSendFailureShowsRetryToastMessageWithoutChangingMessageState() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.sendText("secret", now = 1_000L)
        val message = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20).single()
        fixture.messageDao.markAcked(message.messageId, serverSeq = 8L, updatedAt = 1_100L)
        fixture.connection.sendSucceeds = false

        fixture.viewModel.recallMessage(message.messageId, now = 2_000L)
        runCurrent()

        assertEquals("撤回失败，请重试", fixture.viewModel.state.value.errorMessage)
        assertEquals(false, fixture.messageDao.findByMessageId(message.messageId)?.isRecalled)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun serverRecallFailureEventShowsRetryToastMessage() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()
        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECALL_ACK.value,
                body = """{"messageId":"missing","conversationId":"single:13800113800:13900113900","success":false,"reason":"EXPIRED"}""".toByteArray()
            )
        )
        runCurrent()

        assertEquals("撤回失败，请重试", fixture.viewModel.state.value.errorMessage)
    }

    @Test
    fun clearErrorMessageDismissesTransientChatToast() = runTest {
        val fixture = Fixture(this)
        fixture.connection.sendSucceeds = false

        fixture.viewModel.recallMessage("missing", now = 2_000L)
        fixture.viewModel.clearErrorMessage()

        assertEquals(null, fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startExposesPeerNicknameAndAvatarFromProfileCache() = runTest {
        val fixture = Fixture(this)
        fixture.profileDao.upsert(
            UserProfile(
                userId = "13900113900",
                phone = "13900113900",
                nickname = "Megumi",
                avatarUrl = "https://example.com/megumi.jpg",
                avatarUpdatedAt = 2_000L,
                updatedAt = 2_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals("Megumi", fixture.viewModel.state.value.peerName)
        assertEquals("https://example.com/megumi.jpg", fixture.viewModel.state.value.peerAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startExposesGroupTitleFromConversation() = runTest {
        val fixture = Fixture(this, initialPeerId = "group:g_1001")
        fixture.conversationDao.upsertConversation(
            Conversation(
                conversationId = "group:g_1001",
                peerId = "group:g_1001",
                peerName = "群聊(2)",
                type = ConversationType.GROUP,
                title = "群聊(2)",
                lastMessageId = null,
                lastMessagePreview = "已创建群聊",
                lastMessageTime = 1_000L,
                unreadCount = 0,
                mentionUnreadCount = 0,
                updatedAt = 1_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals("群聊(2)", fixture.viewModel.state.value.peerName)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryUpdateRefreshesIncomingGroupSenderProfiles() = runTest {
        val fixture = Fixture(
            this,
            initialPeerId = "group:g_1001",
            profileApi = FakeProfileApi(
                profiles = listOf(
                    UserProfile(
                        userId = "13900113900",
                        phone = "13900113900",
                        nickname = "Alice",
                        avatarUrl = "https://example.com/alice.jpg",
                        avatarUpdatedAt = 2_000L,
                        updatedAt = 2_000L
                    )
                )
            )
        )
        fixture.viewModel.start()
        runCurrent()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"group-remote-profile-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"hello from Alice",
                      "mentionedUserIds":[],
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val senderProfile = fixture.viewModel.state.value.senderProfiles["13900113900"]
        assertEquals("Alice", senderProfile?.nickname)
        assertEquals("https://example.com/alice.jpg", senderProfile?.avatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoadsLatestTwentyLocalMessages() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(25)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(20, fixture.viewModel.state.value.messages.size)
        assertEquals((25 downTo 6).map { "local-$it" }, fixture.viewModel.state.value.messages.map { it.messageId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMoreHistoryUsesOldestMessageTimeAndMergesEarlierPage() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(45)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.loadMoreHistory()

        assertEquals(40, fixture.viewModel.state.value.messages.size)
        assertEquals((45 downTo 6).map { "local-$it" }, fixture.viewModel.state.value.messages.map { it.messageId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repeatedLoadMoreHistoryDoesNotDuplicateMessagesAndStopsAtLocalEnd() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(25)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.loadMoreHistory()
        fixture.viewModel.loadMoreHistory()

        val messageIds = fixture.viewModel.state.value.messages.map { it.messageId }
        assertEquals((25 downTo 1).map { "local-$it" }, messageIds)
        assertEquals(messageIds.distinct(), messageIds)
        assertFalse(fixture.viewModel.state.value.hasMoreLocal)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMoreHistoryStopsAtMemoryLimitWithoutDroppingLoadedMessages() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(2_025)
        fixture.viewModel.start()
        runCurrent()

        repeat(100) {
            fixture.viewModel.loadMoreHistory()
        }

        val state = fixture.viewModel.state.value
        assertEquals(2_000, state.messages.size)
        assertEquals("local-2025", state.messages.first().messageId)
        assertEquals("local-26", state.messages.last().messageId)
        assertEquals(state.messages.map { it.messageId }.distinct(), state.messages.map { it.messageId })
        assertEquals(true, state.isHistoryMemoryLimitReached)
        assertEquals(true, state.hasMoreLocal)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryUpdateRefreshesVisibleMessages() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.start()
        runCurrent()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-1",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"hi 13800113800",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(listOf("hi 13800113800"), fixture.viewModel.state.value.messages.map { it.content })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupChatSendsGroupMessageWithoutCreatingSingleConversation() = runTest {
        val fixture = Fixture(this, initialPeerId = "group:g_1001")

        fixture.viewModel.sendText("hello group", now = 1_000L)
        runCurrent()

        val stored = fixture.messageDao.queryPage("group:g_1001", null, 20).single()
        assertEquals(ConversationType.GROUP, stored.conversationType)
        assertEquals("g_1001", stored.groupId)
        assertEquals("g_1001", stored.receiverId)
        assertEquals(listOf("group:g_1001"), fixture.conversationDao.listConversations(limit = 20).map { it.conversationId })
        val packetBody = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(packetBody.contains(""""conversationType":"GROUP""""))
        assertTrue(packetBody.contains(""""groupId":"g_1001""""))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupChatSendsImageUnderGroupConversation() = runTest {
        val fixture = Fixture(this, initialPeerId = "group:g_1001")

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/group-original.jpg",
                localThumbnailPath = "cache/group-thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()

        val stored = fixture.messageDao.queryPage("group:g_1001", null, 20).single()
        assertEquals(ConversationType.GROUP, stored.conversationType)
        assertEquals(MessageType.IMAGE, stored.type)
        assertEquals("g_1001", stored.groupId)
        assertEquals(listOf("group:g_1001"), fixture.conversationDao.listConversations(limit = 20).map { it.conversationId })
        val packetBody = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(packetBody.contains(""""conversationType":"GROUP""""))
        assertTrue(packetBody.contains(""""groupId":"g_1001""""))
        assertTrue(packetBody.contains(""""type":"IMAGE""""))
        assertTrue(packetBody.contains(""""imageUrl":"https://oss.example.com/origin.jpg""""))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupChatLoadsMentionMembersAndSendsMentionMetadata() = runTest {
        val fixture = Fixture(
            this,
            initialPeerId = "group:g_1001",
            includeGroupRepository = true,
            profileApi = FakeProfileApi(
                profiles = listOf(
                    UserProfile(
                        userId = "13900113900",
                        phone = "13900113900",
                        nickname = "ByteDance2",
                        avatarUrl = "https://example.com/b.jpg",
                        avatarUpdatedAt = 1_000L,
                        updatedAt = 1_000L
                    )
                )
            )
        )
        fixture.seedGroupMembers()

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("13800113800", "ByteDance2", "ZhangSan"),
            fixture.viewModel.state.value.mentionMembers.map { it.displayName }
        )

        fixture.viewModel.sendText("hello @ByteDance2", mentionedUserIds = listOf("13900113900"), now = 1_000L)
        runCurrent()

        val stored = fixture.messageDao.queryPage("group:g_1001", null, 20).single()
        assertEquals(listOf("13900113900"), stored.mentionedUserIds)
        val packetBody = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(packetBody.contains(""""mentionedUserIds":["13900113900"]"""))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupChatMentionMembersIncludeCurrentUserForIncomingMentionRendering() = runTest {
        val fixture = Fixture(
            this,
            initialPeerId = "group:g_1001",
            includeGroupRepository = true,
            profileApi = FakeProfileApi(
                profiles = listOf(
                    UserProfile(
                        userId = "13800113800",
                        phone = "13800113800",
                        nickname = "userB",
                        avatarUrl = null,
                        avatarUpdatedAt = 1_000L,
                        updatedAt = 1_000L
                    )
                )
            )
        )
        fixture.seedGroupMembers()
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"group-mention-me-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"@13800113800 11",
                      "mentionedUserIds":["13800113800"],
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("userB"),
            fixture.viewModel.state.value.mentionMembers
                .filter { it.userId == "13800113800" }
                .map { it.displayName }
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupChatStartLoadsMessagesFromGroupConversation() = runTest {
        val fixture = Fixture(this, initialPeerId = "group:g_1001")
        fixture.messageDao.insertOrIgnore(
            ChatMessage(
                messageId = "group-local-1",
                conversationId = "group:g_1001",
                senderId = "13800113800",
                receiverId = "g_1001",
                clientSeq = 1,
                serverSeq = 10,
                content = "group history",
                status = MessageStatus.SENT,
                direction = MessageDirection.OUTGOING,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                conversationType = ConversationType.GROUP,
                groupId = "g_1001"
            )
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals(listOf("group history"), fixture.viewModel.state.value.messages.map { it.content })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun incomingPacketsAreDisplayedByServerSeqAfterOutOfOrderArrival() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.start()
        runCurrent()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-2",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"server second",
                      "timestamp":1000
                    }
                """.trimIndent().toByteArray()
            )
        )
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-1",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":1,
                      "content":"server first",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(listOf(2L, 1L), fixture.viewModel.state.value.messages.map { it.serverSeq })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun incomingPacketKeepsPreviouslyLoadedHistory() = runTest {
        val fixture = Fixture(this)
        fixture.seedMessages(25)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.loadMoreHistory()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-latest",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"newest incoming",
                      "timestamp":30000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val messageIds = fixture.viewModel.state.value.messages.map { it.messageId }
        assertEquals("remote-latest", messageIds.first())
        assertEquals("local-1", messageIds.last())
        assertEquals(26, messageIds.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun connectionStateChangesDoNotChangeChatUiState() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()
        val before = fixture.viewModel.state.value

        fixture.connection.state.value = ConnectionState.Authenticated
        runCurrent()

        assertEquals(before, fixture.viewModel.state.value)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryUpdateRefreshesVisibleMessageStatus() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.start()
        fixture.viewModel.sendText("will fail", now = 1_000L)
        runCurrent()
        val pending = fixture.pendingDao.dueMessages(now = 6_000L, limit = 10)
            .single()
            .copy(retryCount = 5, nextRetryAt = 6_000L)
        fixture.pendingDao.upsert(pending)

        fixture.repository.retryDuePendingMessages(now = 6_000L)
        runCurrent()

        assertEquals(MessageStatus.FAILED, fixture.viewModel.state.value.messages.single().status)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopCancelsRepositoryUpdateCollection() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.stop()
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-after-stop",
                      "conversationId":"single:13800113800:13900113900",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"after stop",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(emptyList<String>(), fixture.viewModel.state.value.messages.map { it.content })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImageCreatesUploadingMessageThenQueuesSendAfterUploadSuccess() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/original.jpg",
                localThumbnailPath = "cache/thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()

        val stored = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20).single()
        assertEquals(MessageStatus.SENDING, stored.status)
        assertEquals(MessageType.IMAGE, stored.type)
        assertEquals("https://oss.example.com/thumb.jpg", stored.thumbnailUrl)
        assertEquals(1, fixture.connection.sentPackets.size)
        assertEquals(1, fixture.uploadApi.requests.size)
        assertEquals(2, fixture.uploadApi.uploadCalls.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImageMarksUploadFailedWhenOssUploadFails() = runTest {
        val fixture = Fixture(
            this,
            uploadApi = FakeImageUploadApi(
                uploadResult = AvatarPutResult.Failure("HTTP 500")
            )
        )
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/original.jpg",
                localThumbnailPath = "cache/thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()

        val stored = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20).single()
        assertEquals(MessageStatus.UPLOAD_FAILED, stored.status)
        assertEquals("Image upload failed: HTTP 500", fixture.viewModel.state.value.errorMessage)
        assertTrue(fixture.connection.sentPackets.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImageShowsTargetRequestFailureMessage() = runTest {
        val fixture = Fixture(
            this,
            uploadApi = FakeImageUploadApi(
                targetResult = ImageUploadTargetsResult.Failure("HTTP 404: not found")
            )
        )
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/original.jpg",
                localThumbnailPath = "cache/thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()

        val stored = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20).single()
        assertEquals(MessageStatus.UPLOAD_FAILED, stored.status)
        assertEquals("Image upload target request failed: HTTP 404: not found", fixture.viewModel.state.value.errorMessage)
        assertTrue(fixture.connection.sentPackets.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImageUsesFreshAccessTokenFromProvider() = runTest {
        val fixture = Fixture(
            this,
            validSessionProvider = {
                AuthSession(
                    accessToken = "fresh-token",
                    refreshToken = "fresh-refresh",
                    userId = "13800113800",
                    username = "13800113800",
                    phone = "13800113800",
                    nickname = "13800113800",
                    accessExpiresAtMillis = 5_000L,
                    refreshExpiresAtMillis = 10_000L
                )
            }
        )
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/original.jpg",
                localThumbnailPath = "cache/thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()

        assertEquals(listOf("fresh-token"), fixture.uploadApi.requestedAccessTokens)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImageFailsWhenValidSessionCannotBeResolved() = runTest {
        val fixture = Fixture(
            this,
            validSessionProvider = { null }
        )
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/original.jpg",
                localThumbnailPath = "cache/thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()

        val stored = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20).single()
        assertEquals(MessageStatus.UPLOAD_FAILED, stored.status)
        assertEquals("Image upload target request failed: Session expired", fixture.viewModel.state.value.errorMessage)
        assertTrue(fixture.uploadApi.requests.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryUploadFailedImageUploadsLocalFilesAndQueuesSend() = runTest {
        val fixture = Fixture(
            this,
            uploadApi = FakeImageUploadApi(
                uploadResults = mutableListOf(AvatarPutResult.Failure("HTTP 500"))
            )
        )
        fixture.viewModel.selectPeer("13900113900")
        val originalFile = writeTempImageBytes(byteArrayOf(1, 2, 3))
        val thumbnailFile = writeTempImageBytes(byteArrayOf(4, 5))

        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5),
                localOriginalPath = originalFile.absolutePath,
                localThumbnailPath = thumbnailFile.absolutePath,
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()
        assertEquals(MessageStatus.UPLOAD_FAILED, fixture.viewModel.state.value.messages.single().status)

        fixture.uploadApi.uploadResults += AvatarPutResult.Success
        fixture.uploadApi.uploadResults += AvatarPutResult.Success
        fixture.viewModel.retryImageMessage(fixture.viewModel.state.value.messages.single().messageId)
        runCurrent()

        val stored = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20).single()
        assertEquals(MessageStatus.SENDING, stored.status)
        assertEquals(2, fixture.uploadApi.requests.size)
        assertEquals(3, fixture.uploadApi.uploadCalls.size)
        assertEquals(1, fixture.connection.sentPackets.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryFailedImageMessageRequeuesSendWithoutUploadingAgain() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.sendImage(
            SelectedChatImage(
                originalBytes = byteArrayOf(1, 2, 3),
                thumbnailBytes = byteArrayOf(4, 5, 6),
                localOriginalPath = "cache/original.jpg",
                localThumbnailPath = "cache/thumb.jpg",
                width = 1440,
                height = 960,
                mimeType = "image/jpeg"
            ),
            now = 1_000L
        )
        runCurrent()
        val messageId = fixture.viewModel.state.value.messages.single().messageId
        fixture.messageDao.markStatus(messageId, MessageStatus.FAILED, updatedAt = 6_000L)
        runCurrent()
        fixture.connection.sentPackets.clear()
        fixture.uploadApi.requests.clear()
        fixture.uploadApi.uploadCalls.clear()

        fixture.viewModel.retryImageMessage(messageId)
        runCurrent()

        val stored = fixture.messageDao.findByMessageId(messageId)
        assertEquals(MessageStatus.SENDING, stored?.status)
        assertEquals(emptyList<String>(), fixture.uploadApi.requests)
        assertEquals(emptyList<String>(), fixture.uploadApi.uploadCalls)
        assertEquals(1, fixture.connection.sentPackets.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImagesContinuesAfterOneUploadFailure() = runTest {
        val fixture = Fixture(
            this,
            uploadApi = FakeImageUploadApi(
                uploadResults = mutableListOf(
                    AvatarPutResult.Success,
                    AvatarPutResult.Success,
                    AvatarPutResult.Failure("HTTP 500"),
                    AvatarPutResult.Success,
                    AvatarPutResult.Success
                )
            )
        )
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImages(
            listOf(
                selectedImage(originalSize = 3, thumbnailSize = 2),
                selectedImage(originalSize = 4, thumbnailSize = 2),
                selectedImage(originalSize = 5, thumbnailSize = 2)
            ),
            now = 1_000L
        )
        runCurrent()

        val messages = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20)
        assertEquals(3, messages.size)
        assertEquals(
            listOf(MessageStatus.SENDING, MessageStatus.UPLOAD_FAILED, MessageStatus.SENDING),
            messages.sortedBy { it.createdAt }.map { it.status }
        )
        assertEquals(3, fixture.uploadApi.requests.size)
        assertEquals(5, fixture.uploadApi.uploadCalls.size)
        assertEquals(2, fixture.connection.sentPackets.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImagesOrdersBySelectionOrderNotInputOrder() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImages(
            listOf(
                selectedImage(label = "D", selectionOrder = 3),
                selectedImage(label = "A", selectionOrder = 0),
                selectedImage(label = "C", selectionOrder = 2),
                selectedImage(label = "B", selectionOrder = 1)
            ),
            now = 1_000L
        )
        runCurrent()

        val sentLabels = fixture.messageDao
            .queryPage("single:13800113800:13900113900", null, 20)
            .sortedBy { it.createdAt }
            .map { it.localOriginalPath?.substringAfterLast('-')?.substringBefore('.') }
        assertEquals(listOf("A", "B", "C", "D"), sentLabels)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ackedImageSentAfterFailedUploadStaysNewestInChatState() = runTest {
        val fixture = Fixture(
            this,
            uploadApi = FakeImageUploadApi(
                uploadResults = mutableListOf(
                    AvatarPutResult.Success,
                    AvatarPutResult.Failure("HTTP 500"),
                    AvatarPutResult.Success,
                    AvatarPutResult.Success
                )
            )
        )
        fixture.viewModel.selectPeer("13900113900")
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.sendImages(
            listOf(
                selectedImage(originalSize = 3, thumbnailSize = 2),
                selectedImage(originalSize = 4, thumbnailSize = 2)
            ),
            now = 1_000L
        )
        runCurrent()

        val sentMessage = fixture.messageDao
            .queryPage("single:13800113800:13900113900", null, 20)
            .single { it.status == MessageStatus.SENDING }
        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.MESSAGE_ACK.value,
                body = """{"messageId":"${sentMessage.messageId}","serverSeq":8,"serverTime":2000}""".toByteArray()
            )
        )
        runCurrent()

        val messages = fixture.viewModel.state.value.messages
        assertEquals(MessageStatus.SENT, messages.first().status)
        assertEquals(sentMessage.messageId, messages.first().messageId)
        assertEquals(MessageStatus.UPLOAD_FAILED, messages[1].status)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sendImagesLimitsBatchToNineImages() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.selectPeer("13900113900")

        fixture.viewModel.sendImages(
            List(10) { selectedImage(originalSize = it + 1, thumbnailSize = 1) },
            now = 1_000L
        )
        runCurrent()

        val messages = fixture.messageDao.queryPage("single:13800113800:13900113900", null, 20)
        assertEquals(9, messages.size)
        assertEquals(9, fixture.uploadApi.requests.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startRetriesMissingIncomingImageThumbnailImmediately() = runTest {
        val thumbnailCache = FakeThumbnailCache(localPaths = mutableListOf(null, "cache/thumb-after-start.jpg"))
        val fixture = Fixture(this, thumbnailCache = thumbnailCache)
        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-start"))

        fixture.viewModel.start()
        runCurrent()

        assertEquals("cache/thumb-after-start.jpg", fixture.messageDao.findByMessageId("remote-image-start")?.localThumbnailPath)
        assertEquals(listOf("remote-image-start"), fixture.viewModel.state.value.messages.map { it.messageId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryUpdateSchedulesDelayedThumbnailRetriesWithBackoff() = runTest {
        val thumbnailCache = FakeThumbnailCache(localPaths = mutableListOf(null, null, null, "cache/thumb-after-delay.jpg"))
        val fixture = Fixture(this, thumbnailCache = thumbnailCache)
        fixture.viewModel.start()
        runCurrent()

        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-delayed"))
        runCurrent()
        assertEquals(emptyList<String>(), fixture.viewModel.state.value.messages.map { it.messageId })

        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.viewModel.state.value.messages.map { it.messageId })

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.viewModel.state.value.messages.map { it.messageId })

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(emptyList<String>(), fixture.viewModel.state.value.messages.map { it.messageId })

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals("cache/thumb-after-delay.jpg", fixture.messageDao.findByMessageId("remote-image-delayed")?.localThumbnailPath)
        assertEquals(listOf("remote-image-delayed"), fixture.viewModel.state.value.messages.map { it.messageId })
    }

    private class Fixture(
        scope: TestScope,
        initialPeerId: String? = "13900113900",
        profileApi: ProfileApi = FakeProfileApi(),
        val uploadApi: FakeImageUploadApi = FakeImageUploadApi(),
        thumbnailCache: ChatThumbnailCache = NoopChatThumbnailCache,
        includeGroupRepository: Boolean = false,
        val validSessionProvider: suspend () -> AuthSession? = {
            AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L)
        }
    ) {
        val connection = FakeConnection()
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val pendingDao = InMemoryPendingMessageDao()
        val profileDao = InMemoryUserProfileDao()
        val groupDao = InMemoryGroupDao()
        private val profileRepository = ProfileRepository(profileDao, profileApi)
        private val groupRepository = FakeGroupRepository(groupDao).takeIf { includeGroupRepository }
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = pendingDao,
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator(),
            thumbnailCache = thumbnailCache
        )
        val viewModel = if (initialPeerId == null) {
            ChatViewModel(
                session = AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L),
                repository = repository,
                connection = connection,
                profileRepository = profileRepository,
                groupRepository = groupRepository,
                imageUploadApi = uploadApi,
                validSessionProvider = validSessionProvider,
                scope = scope.backgroundScope,
                dispatcher = StandardTestDispatcher(scope.testScheduler)
            )
        } else {
            ChatViewModel(
                session = AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L),
                repository = repository,
                connection = connection,
                profileRepository = profileRepository,
                groupRepository = groupRepository,
                initialPeerId = initialPeerId,
                imageUploadApi = uploadApi,
                validSessionProvider = validSessionProvider,
                scope = scope.backgroundScope,
                dispatcher = StandardTestDispatcher(scope.testScheduler)
            )
        }

        fun seedMessages(count: Int) {
            repeat(count) { index ->
                val number = index + 1
                val createdAt = number * 1_000L
                messageDao.insertOrIgnore(
                    ChatMessage(
                        messageId = "local-$number",
                        conversationId = "single:13800113800:13900113900",
                        senderId = "13800113800",
                        receiverId = "13900113900",
                        clientSeq = number.toLong(),
                        serverSeq = null,
                        content = "message $number",
                        status = MessageStatus.SENT,
                        direction = MessageDirection.OUTGOING,
                        createdAt = createdAt,
                        updatedAt = createdAt
                    )
                )
            }
        }

        fun seedGroupMembers() {
            groupDao.upsertGroup(GroupInfo("g_1001", "群聊(3)", null, "13800113800", 1_000L, 1_000L))
            groupDao.replaceMembers(
                "g_1001",
                listOf(
                    GroupMember("g_1001", "13800113800", "我", null, GroupMemberRole.OWNER, 1_000L, 1_000L),
                    GroupMember("g_1001", "13900113900", "ByteDance2", null, GroupMemberRole.MEMBER, 1_000L, 1_000L),
                    GroupMember("g_1001", "17724734511", "ZhangSan", null, GroupMemberRole.MEMBER, 1_000L, 1_000L)
                )
            )
        }
    }

    private class FakeGroupRepository(
        private val groupDao: InMemoryGroupDao
    ) : GroupRepository {
        override suspend fun createGroup(
            accessToken: String,
            ownerId: String,
            name: String,
            memberUserIds: List<String>,
            now: Long
        ): GroupCreateResult = GroupCreateResult.Failure("unused")

        override suspend fun renameGroup(accessToken: String, groupId: String, name: String): GroupResult {
            return GroupResult.Failure("unused")
        }

        override suspend fun syncGroups(accessToken: String): List<GroupInfo> = emptyList()

        override suspend fun syncMembers(accessToken: String, groupId: String): List<GroupMember> = groupDao.members(groupId)

        override fun localMembers(groupId: String): List<GroupMember> = groupDao.members(groupId)

        override fun localGroup(groupId: String): GroupInfo? = groupDao.findGroup(groupId)

        override fun joinedGroups(userId: String): List<GroupInfo> = emptyList()
    }

    private class FakeConnection : ImConnection {
        var connectedToken: String? = null
        var sendSucceeds: Boolean = true
        val incoming = MutableSharedFlow<ImPacket>()
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val sentPackets = mutableListOf<ImPacket>()
        override val states: StateFlow<ConnectionState> = state
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) {
            connectedToken = token
        }

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean {
            sentPackets += packet
            return sendSucceeds
        }
    }

    private class FakeImageUploadApi(
        private val targetResult: ImageUploadTargetsResult = ImageUploadTargetsResult.Success(
            ImageUploadTargets(
                messageId = "unused",
                thumbnail = AvatarUploadTarget(
                    objectKey = "chat-images/u/m-1/thumb.jpg",
                    uploadUrl = "https://signed/thumb",
                    publicUrl = "https://oss.example.com/thumb.jpg",
                    expiresAt = 3_000L
                ),
                original = AvatarUploadTarget(
                    objectKey = "chat-images/u/m-1/origin.jpg",
                    uploadUrl = "https://signed/origin",
                    publicUrl = "https://oss.example.com/origin.jpg",
                    expiresAt = 3_000L
                ),
                expiresAt = 3_000L
            )
        ),
        private val uploadResult: AvatarPutResult = AvatarPutResult.Success,
        val uploadResults: MutableList<AvatarPutResult> = mutableListOf()
    ) : ImageUploadApi {
        val requests = mutableListOf<String>()
        val requestedAccessTokens = mutableListOf<String>()
        val uploadCalls = mutableListOf<String>()

        override suspend fun requestUploadTargets(
            accessToken: String,
            messageId: String,
            contentType: String
        ): ImageUploadTargetsResult {
            requestedAccessTokens += accessToken
            requests += "$messageId|$contentType"
            return when (targetResult) {
                is ImageUploadTargetsResult.Success -> ImageUploadTargetsResult.Success(
                    targetResult.targets.copy(messageId = messageId)
                )
                is ImageUploadTargetsResult.Failure -> targetResult
            }
        }

        override suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult {
            uploadCalls += "$uploadUrl|$contentType|${bytes.size}"
            return if (uploadResults.isNotEmpty()) uploadResults.removeAt(0) else uploadResult
        }
    }

    private class FakeThumbnailCache(
        private val localPaths: MutableList<String?>
    ) : ChatThumbnailCache {
        val requests = mutableListOf<Pair<String, String>>()

        override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? {
            requests += messageId to thumbnailUrl
            return if (localPaths.size > 1) localPaths.removeAt(0) else localPaths.firstOrNull()
        }
    }

    private fun incomingImagePacket(messageId: String): ImPacket {
        return ImPacket(
            cmd = 12,
            body = """
                {
                  "messageId":"$messageId",
                  "conversationId":"single:13900113900:13800113800",
                  "senderId":"13900113900",
                  "receiverId":"13800113800",
                  "clientSeq":10,
                  "serverSeq":93,
                  "type":"IMAGE",
                  "content":"[图片]",
                  "image":{
                    "imageUrl":"https://oss.example.com/origin.jpg",
                    "thumbnailUrl":"https://oss.example.com/thumb.jpg",
                    "width":900,
                    "height":600,
                    "mimeType":"image/jpeg",
                    "sizeBytes":456789
                  },
                  "timestamp":1800
                }
            """.trimIndent().toByteArray()
        )
    }

    private fun writeTempImageBytes(bytes: ByteArray): File {
        return File.createTempFile("chat-image-test", ".jpg").also { file ->
            file.writeBytes(bytes)
            file.deleteOnExit()
        }
    }

    private fun selectedImage(originalSize: Int, thumbnailSize: Int): SelectedChatImage {
        return SelectedChatImage(
            originalBytes = ByteArray(originalSize) { 1 },
            thumbnailBytes = ByteArray(thumbnailSize) { 2 },
            localOriginalPath = "cache/original-$originalSize.jpg",
            localThumbnailPath = "cache/thumb-$originalSize.jpg",
            width = 1440,
            height = 960,
            mimeType = "image/jpeg"
        )
    }

    private fun selectedImage(label: String, selectionOrder: Int): SelectedChatImage {
        return SelectedChatImage(
            originalBytes = ByteArray(1) { label.first().code.toByte() },
            thumbnailBytes = ByteArray(1) { 2 },
            localOriginalPath = "cache/original-$label.jpg",
            localThumbnailPath = "cache/thumb-$label.jpg",
            width = 1440,
            height = 960,
            mimeType = "image/jpeg",
            selectionOrder = selectionOrder
        )
    }

    private class FakeProfileApi(
        profiles: List<UserProfile> = emptyList()
    ) : ProfileApi {
        private val profilesById = profiles.associateBy { it.userId }

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(userIds.mapNotNull(profilesById::get))
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.codex.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}
