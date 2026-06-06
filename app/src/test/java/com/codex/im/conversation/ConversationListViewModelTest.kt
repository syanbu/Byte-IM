package com.codex.im.conversation

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.ChatThumbnailPreloader
import com.codex.im.message.MessageIdGenerator
import com.codex.im.message.MessageRepository
import com.codex.im.message.NoopChatThumbnailPreloader
import com.codex.im.message.SeqGenerator
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.UserProfile
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.group.GroupRepository
import com.codex.im.group.GroupCreateResult
import com.codex.im.group.GroupResult
import com.codex.im.storage.GroupInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationListViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsNoRowsWhenThereAreNoConversations() = runTest {
        val fixture = Fixture(this)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(emptyList<ConversationListItem>(), fixture.viewModel.state.value.items)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startUsesProfileNicknameAndAvatarForConversationRows() = runTest {
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
        fixture.repository.sendText("13800113800", "13900113900", "hello", now = 1_000L)

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("Megumi", item.peerName)
        assertEquals("https://example.com/megumi.jpg", item.peerAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsOnlyRecentConversations() = runTest {
        val fixture = Fixture(this)
        fixture.repository.sendText("13800113800", "13900113900", "older", now = 1_000L)
        fixture.repository.sendText("13800113800", "13700113700", "newer", now = 2_000L)

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("13700113700", "13900113900"),
            fixture.viewModel.state.value.items.map { it.peerId }
        )
        assertEquals("newer", fixture.viewModel.state.value.items.first().lastMessagePreview)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoadsOnlyFirstConversationPageWhenMoreThanFiftyExist() = runTest {
        val fixture = Fixture(this)
        val peers = (1..60).map { index -> "13900113%03d".format(index) }
        peers.forEachIndexed { index, peerId ->
            fixture.repository.sendText(
                senderId = "13800113800",
                receiverId = peerId,
                content = "seed $index",
                now = 1_000L + index
            )
        }

        fixture.viewModel.start()
        runCurrent()

        assertEquals(50, fixture.viewModel.state.value.items.size)
        assertEquals(true, fixture.viewModel.state.value.hasMoreConversations)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMoreConversationsAppendsNextPageAndMarksEnd() = runTest {
        val fixture = Fixture(this)
        val peers = (1..60).map { index -> "13900113%03d".format(index) }
        peers.forEachIndexed { index, peerId ->
            fixture.repository.sendText(
                senderId = "13800113800",
                receiverId = peerId,
                content = "seed $index",
                now = 1_000L + index
            )
        }
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.loadMoreConversations()
        runCurrent()

        assertEquals(60, fixture.viewModel.state.value.items.size)
        assertEquals(false, fixture.viewModel.state.value.isLoadingMore)
        assertEquals(false, fixture.viewModel.state.value.hasMoreConversations)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun loadMoreConversationsPrefetchesAndReusesCachedPage() = runTest {
        val countingDao = CountingConversationDao(InMemoryConversationDao())
        val fixture = Fixture(this, conversationDao = countingDao)
        val peers = (1..150).map { index -> "13900113%03d".format(index) }
        peers.forEachIndexed { index, peerId ->
            fixture.repository.sendText(
                senderId = "13800113800",
                receiverId = peerId,
                content = "seed $index",
                now = 1_000L + index
            )
        }

        fixture.viewModel.start()
        runCurrent()
        assertEquals(50, fixture.viewModel.state.value.items.size)
        // start -> refresh 内部跑了 2 次首页查询 + 1 次第二页预加载
        assertEquals(3, countingDao.pageQueryCount)

        // 第一次 loadMore: 命中启动阶段已经准备好的第二页，不应同步阻塞更新 UI。
        fixture.viewModel.loadMoreConversations()
        assertEquals(50, fixture.viewModel.state.value.items.size)
        runCurrent()
        assertEquals(100, fixture.viewModel.state.value.items.size)
        assertEquals(4, countingDao.pageQueryCount)

        // 第二次 loadMore: 缓存命中(0) + 调度下一批预加载(1) = +1
        // 同时缓存命中页的应用也应该在后台完成,而不是调用时同步卡住主线程。
        fixture.viewModel.loadMoreConversations()
        assertEquals(100, fixture.viewModel.state.value.items.size)
        runCurrent()
        assertEquals(150, fixture.viewModel.state.value.items.size)
        assertEquals(5, countingDao.pageQueryCount)

        // 第三次 loadMore: 缓存命中(空列表) + 没有下一页所以不预加载 = +0
        fixture.viewModel.loadMoreConversations()
        runCurrent()
        assertEquals(150, fixture.viewModel.state.value.items.size)
        assertEquals(false, fixture.viewModel.state.value.hasMoreConversations)
        assertEquals(5, countingDao.pageQueryCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshClearsPendingPrefetchedPage() = runTest {
        val countingDao = CountingConversationDao(InMemoryConversationDao())
        val fixture = Fixture(this, conversationDao = countingDao)
        val peers = (1..150).map { index -> "13900113%03d".format(index) }
        peers.forEachIndexed { index, peerId ->
            fixture.repository.sendText(
                senderId = "13800113800",
                receiverId = peerId,
                content = "seed $index",
                now = 1_000L + index
            )
        }
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.loadMoreConversations()
        runCurrent()
        // 此时 prefetchedPage 应该有 50 条 (next batch 100~149)
        assertEquals(4, countingDao.pageQueryCount)

        // 收到新消息触发 refresh -> 应该清掉 prefetchedPage 和 prefetchJob
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"refresh-trigger-msg",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":200,
                      "serverSeq":300,
                      "content":"hi",
                      "timestamp":9999
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()
        // refresh 内部 2 次首页查询 (firstPage + refreshedFirstPage) + 1 次第二页预热
        assertEquals(7, countingDao.pageQueryCount)

        // refresh 会先清缓存,但结束时会重新预热最新的第二页。
        // 所以下一次 loadMore 应该命中新缓存,只新增 1 次下一批预加载查询。
        fixture.viewModel.loadMoreConversations()
        runCurrent()
        assertEquals(8, countingDao.pageQueryCount)
        assertEquals(151, fixture.viewModel.state.value.items.size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryConversationUpdateRefreshesRowsWithoutDroppingLoadedOlderPage() = runTest {
        val fixture = Fixture(this)
        val peers = (1..60).map { index -> "13900113%03d".format(index) }
        peers.forEachIndexed { index, peerId ->
            fixture.repository.sendText(
                senderId = "13800113800",
                receiverId = peerId,
                content = "seed $index",
                now = 1_000L + index
            )
        }
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.loadMoreConversations()
        runCurrent()

        fixture.repository.sendText(
            senderId = "13800113800",
            receiverId = peers.first(),
            content = "bump oldest loaded row",
            now = 9_000L
        )
        runCurrent()

        assertEquals(60, fixture.viewModel.state.value.items.size)
        assertEquals(peers.first(), fixture.viewModel.state.value.items.first().peerId)
        assertEquals("bump oldest loaded row", fixture.viewModel.state.value.items.first().lastMessagePreview)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startMarksLocalGroupConversationsAsGroups() = runTest {
        val fixture = Fixture(this)
        fixture.repository.createLocalGroupConversation(
            creatorUserId = "13800113800",
            memberUserIds = listOf("13900113900", "17724734511"),
            now = 1_000L
        )

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("群聊(3)", item.peerName)
        assertEquals(true, item.isGroup)
        assertEquals(0, item.mentionUnreadCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startDoesNotAddDuplicateConversationWhenCanonicalConversationAlreadyExists() = runTest {
        val fixture = Fixture(this)
        fixture.repository.sendText("13900113900", "13800113800", "hello from other login", now = 1_000L)

        fixture.viewModel.start()
        runCurrent()

        val items = fixture.viewModel.state.value.items
        assertEquals(items.map { it.conversationId }.distinct(), items.map { it.conversationId })
        assertEquals(listOf("13900113900"), items.map { it.peerId })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repositoryConversationUpdateRefreshesVisibleList() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-latest",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":3,
                      "serverSeq":6,
                      "content":"Hello, You are my Hero",
                      "timestamp":3000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("Hello, You are my Hero", item.lastMessagePreview)
        assertEquals(1, item.unreadCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startHandlesIncomingPacketEmittedDuringConnect() = runTest {
        val fixture = Fixture(this)
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"offline-delivered-during-auth",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":3,
                      "serverSeq":6,
                      "content":"Hello while you were away",
                      "timestamp":3000
                    }
                """.trimIndent().toByteArray()
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("Hello while you were away", item.lastMessagePreview)
        assertEquals(1, item.unreadCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startReconnectsImmediatelyWhenPreviousBackoffIsActive() = runTest {
        val fixture = Fixture(this)
        fixture.connection.mutableStates.value = ConnectionState.Reconnecting(
            delayMillis = 30_000L,
            reason = "token unavailable"
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals("mock-token-13800113800", fixture.connection.connectedToken)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsLocalConversationsBeforeRemoteProfileRefreshCompletes() = runTest {
        val profileApi = BlockingBatchProfileApi()
        val fixture = Fixture(this, profileApi = profileApi)
        fixture.repository.sendText("13800113800", "13900113900", "local while server unavailable", now = 1_000L)

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("13900113900", item.peerId)
        assertEquals("local while server unavailable", item.lastMessagePreview)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openConversationClearsUnreadAndExposesNavigationTarget() = runTest {
        val fixture = Fixture(this)
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"remote-1",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"hello",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.openConversation("13900113900")
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals(0, item.unreadCount)
        assertEquals("13900113900", fixture.viewModel.state.value.navigationTargetPeerId)
        assertEquals("single:13800113800:13900113900", fixture.viewModel.state.value.navigationTargetConversationId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openGroupConversationClearsUnreadAndExposesConversationNavigationTarget() = runTest {
        val fixture = Fixture(this)
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"group-remote-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"@me hello",
                      "mentionedUserIds":["13800113800"],
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.openConversation("group:g_1001")
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals(0, item.unreadCount)
        assertEquals(0, item.mentionUnreadCount)
        assertEquals(null, fixture.viewModel.state.value.navigationTargetPeerId)
        assertEquals("group:g_1001", fixture.viewModel.state.value.navigationTargetConversationId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun deleteConversationRemovesRowAndLocalHistoryOnly() = runTest {
        val fixture = Fixture(this)
        fixture.repository.sendText("13800113800", "13900113900", "delete me", now = 1_000L)
        fixture.repository.sendText("13800113800", "13700113700", "keep me", now = 2_000L)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.deleteConversation("single:13800113800:13900113900")
        runCurrent()

        assertEquals(listOf("13700113700"), fixture.viewModel.state.value.items.map { it.peerId })
        assertEquals(
            emptyList<String>(),
            fixture.repository.messagesWith("13800113800", "13900113900").map { it.content }
        )
        assertEquals(
            listOf("keep me"),
            fixture.repository.messagesWith("13800113800", "13700113700").map { it.content }
        )
    }

    @Test
    fun mentionPreviewUsesMentionNicknameAndReminderLabel() {
        val item = ConversationListItem(
            conversationId = "group:g_1001",
            peerId = "group:g_1001",
            peerName = "群聊",
            peerAvatarUrl = null,
            lastMessagePreview = "@13800113800 what",
            lastMessageTime = 1_000L,
            unreadCount = 1,
            mentionUnreadCount = 1,
            isGroup = true,
            mentionDisplayNamesById = mapOf("13800113800" to "Syan")
        )

        assertEquals("[有人@我] @Syan what", ConversationListPreviewPolicy.previewText(item))
    }

    @Test
    fun mentionPreviewHighlightsOnlyReminderLabel() {
        val item = ConversationListItem(
            conversationId = "group:g_1001",
            peerId = "group:g_1001",
            peerName = "群聊",
            peerAvatarUrl = null,
            lastMessagePreview = "@13800113800 what",
            lastMessageTime = 1_000L,
            unreadCount = 1,
            mentionUnreadCount = 1,
            isGroup = true,
            mentionDisplayNamesById = mapOf("13800113800" to "Syan")
        )

        val annotated = ConversationListPreviewPolicy.previewAnnotatedText(item, mentionColor = Color.Red)

        assertEquals("[有人@我] @Syan what", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        val span = annotated.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals("[有人@我]".length, span.end)
        assertEquals(Color.Red, span.item.color)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startUsesProfileNicknameForMentionPreview() = runTest {
        val fixture = Fixture(
            this,
            profileApi = FakeProfileApi(
                profiles = listOf(
                    UserProfile(
                        userId = "13800113800",
                        phone = "13800113800",
                        nickname = "Syan",
                        avatarUrl = null,
                        avatarUpdatedAt = 0L,
                        updatedAt = 2_000L
                    )
                )
            )
        )
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"group-mention-preview-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"@13800113800 what",
                      "mentionedUserIds":["13800113800"],
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals(mapOf("13800113800" to "Syan"), item.mentionDisplayNamesById)
        assertEquals("[有人@我] @Syan what", ConversationListPreviewPolicy.previewText(item))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startSyncsRemoteGroupNameForGroupConversationRows() = runTest {
        val fixture = Fixture(
            this,
            groupRepository = FakeGroupRepository(
                groups = listOf(
                    GroupInfo(
                        groupId = "g_1001",
                        name = "新群名",
                        avatarUrl = "https://example.com/group.jpg",
                        ownerId = "13800113800",
                        createdAt = 1_000L,
                        updatedAt = 3_000L
                    )
                ),
                conversationDao = null
            )
        )
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"group-title-sync-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "groupName":"旧群名",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"hello",
                      "mentionedUserIds":[],
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("新群名", item.peerName)
        assertEquals("https://example.com/group.jpg", item.peerAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun groupRecallPreviewUsesRecalledSenderNickname() = runTest {
        val fixture = Fixture(
            this,
            profileApi = FakeProfileApi(
                profiles = listOf(
                    UserProfile(
                        userId = "13900113900",
                        phone = "13900113900",
                        nickname = "ByteDance2",
                        avatarUrl = null,
                        avatarUpdatedAt = 0L,
                        updatedAt = 2_000L
                    )
                )
            )
        )
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 12,
                body = """
                    {
                      "messageId":"group-recall-preview-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "groupName":"群聊(3)",
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":1,
                      "serverSeq":2,
                      "content":"secret",
                      "timestamp":2000
                    }
                """.trimIndent().toByteArray()
            )
        )
        fixture.repository.handlePacket(
            ImPacket(
                cmd = 17,
                body = """
                    {
                      "messageId":"group-recall-preview-1",
                      "conversationId":"group:g_1001",
                      "recalledBy":"13900113900",
                      "recalledAt":3000
                    }
                """.trimIndent().toByteArray()
            )
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals("ByteDance2撤回了一条消息", fixture.viewModel.state.value.items.single().lastMessagePreview)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openConversationNavigatesBeforePreloadingRecentLocalThumbnails() = runTest {
        val thumbnailPreloader = FakeThumbnailPreloader()
        val fixture = Fixture(this, thumbnailPreloader = thumbnailPreloader)
        fixture.messageDao.insertOrIgnore(
            imageMessage(
                messageId = "image-older",
                createdAt = 1_000L,
                localThumbnailPath = "cache/older.jpg"
            )
        )
        fixture.messageDao.insertOrIgnore(
            imageMessage(
                messageId = "image-newer",
                createdAt = 2_000L,
                localThumbnailPath = "cache/newer.jpg"
            )
        )
        fixture.messageDao.insertOrIgnore(
            imageMessage(
                messageId = "image-uncached",
                createdAt = 3_000L,
                localThumbnailPath = null
            )
        )

        fixture.viewModel.openConversation("13900113900")
        runCurrent()

        assertEquals("13900113900", fixture.viewModel.state.value.navigationTargetPeerId)
        assertEquals(listOf(listOf("cache/newer.jpg", "cache/older.jpg")), thumbnailPreloader.calls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeNavigationTargetClearsIt() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.openConversation("13900113900")
        runCurrent()

        fixture.viewModel.consumeNavigationTarget()

        assertEquals(null, fixture.viewModel.state.value.navigationTargetPeerId)
        assertEquals(null, fixture.viewModel.state.value.navigationTargetConversationId)
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
                      "senderId":"13900113900",
                      "receiverId":"13800113800",
                      "clientSeq":5,
                      "serverSeq":9,
                      "content":"after stop",
                      "timestamp":4000
                    }
                """.trimIndent().toByteArray()
            )
        )
        runCurrent()

        assertEquals(emptyList<ConversationListItem>(), fixture.viewModel.state.value.items)
    }

    private class Fixture(
        scope: TestScope,
        profileApi: ProfileApi = FakeProfileApi(),
        val groupRepository: FakeGroupRepository? = null,
        thumbnailPreloader: ChatThumbnailPreloader = NoopChatThumbnailPreloader,
        private val conversationDao: com.codex.im.storage.ConversationDao = InMemoryConversationDao()
    ) {
        val connection = FakeConnection()
        val messageDao = InMemoryMessageDao()
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, profileApi)
        init {
            groupRepository?.conversationDao = conversationDao
        }
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator()
        )
        val viewModel = ConversationListViewModel(
            session = AuthSession("mock-token-13800113800", "13800113800", "13800113800", expiresAtMillis = 2_000L),
            repository = repository,
            connection = connection,
            profileRepository = profileRepository,
            groupRepository = groupRepository,
            thumbnailPreloader = thumbnailPreloader,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeConnection : ImConnection {
        var connectedToken: String? = null
        val mutableStates = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val incoming = MutableSharedFlow<ImPacket>(extraBufferCapacity = 64)
        override val states: StateFlow<ConnectionState> = mutableStates
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) {
            connectedToken = token
        }

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean = true
    }

    private class FakeThumbnailPreloader : ChatThumbnailPreloader {
        val calls = mutableListOf<List<String>>()

        override fun preload(localThumbnailPaths: List<String>) {
            calls += localThumbnailPaths
        }
    }

    private fun imageMessage(
        messageId: String,
        createdAt: Long,
        localThumbnailPath: String?
    ): ChatMessage {
        return ChatMessage(
            messageId = messageId,
            conversationId = "single:13800113800:13900113900",
            senderId = "13900113900",
            receiverId = "13800113800",
            clientSeq = createdAt,
            serverSeq = createdAt,
            content = "[图片]",
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = createdAt,
            updatedAt = createdAt,
            type = MessageType.IMAGE,
            imageUrl = "https://oss.example.com/origin.jpg",
            thumbnailUrl = "https://oss.example.com/thumb.jpg",
            imageWidth = 900,
            imageHeight = 600,
            mimeType = "image/jpeg",
            fileSizeBytes = 123L,
            localThumbnailPath = localThumbnailPath
        )
    }

    private class FakeProfileApi(
        private val profiles: List<UserProfile> = emptyList()
    ) : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(profiles.filter { it.userId in userIds })
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

    private class BlockingBatchProfileApi : ProfileApi {
        private val neverCompletes = CompletableDeferred<ProfileBatchResult>()

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return neverCompletes.await()
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

    private class FakeGroupRepository(
        private val groups: List<GroupInfo>,
        var conversationDao: com.codex.im.storage.ConversationDao?
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

        override suspend fun syncGroups(accessToken: String): List<GroupInfo> {
            val dao = conversationDao
            groups.forEach { group ->
                val conversationId = "group:${group.groupId}"
                val current = dao?.findConversation(conversationId)
                if (current != null) {
                    dao.upsertConversation(
                        current.copy(
                            peerName = group.name,
                            title = group.name,
                            avatarUrl = group.avatarUrl,
                            updatedAt = group.updatedAt
                        )
                    )
                }
            }
            return groups
        }

        override suspend fun syncMembers(accessToken: String, groupId: String): List<com.codex.im.storage.GroupMember> {
            return emptyList()
        }

        override fun localMembers(groupId: String): List<com.codex.im.storage.GroupMember> {
            return emptyList()
        }
    }

    private class CountingConversationDao(
        private val delegate: com.codex.im.storage.ConversationDao
    ) : com.codex.im.storage.ConversationDao by delegate {
        var pageQueryCount: Int = 0
        override fun listConversationsPage(
            beforeLastMessageTime: Long?,
            beforeConversationId: String?,
            limit: Int
        ): List<com.codex.im.storage.Conversation> {
            pageQueryCount++
            return delegate.listConversationsPage(beforeLastMessageTime, beforeConversationId, limit)
        }
    }
}
