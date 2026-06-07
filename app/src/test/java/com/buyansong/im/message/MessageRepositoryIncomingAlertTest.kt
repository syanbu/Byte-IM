package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.profile.ProfileApi
import com.buyansong.im.profile.ProfileBatchResult
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.profile.ProfileResult
import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.Conversation
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.TransactionRunner
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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
                nickname = "Alice",
                avatarUrl = "https://example.com/u2.png",
                avatarUpdatedAt = 1L,
                updatedAt = 1L
            )
        )
        val alertDeferred = async { fixture.repository.messageAlerts.first() }
        runCurrent()

        fixture.repository.handlePacket(singleTextPacket(messageId = "m1", content = "hello"))

        val alert = withTimeout(1_000L) { alertDeferred.await() }
        assertEquals("single:u1:u2", alert.conversationId)
        assertEquals(false, alert.isGroup)
        assertEquals("Alice", alert.title)
        assertEquals("https://example.com/u2.png", alert.avatarUrl)
        assertEquals("hello", alert.preview)
        assertEquals(1_000L, alert.rawTimestamp)
    }

    @Test
    fun incomingSingleTextDoesNotEmitWhenConversationIsOpen() = runTest {
        val fixture = Fixture()
        fixture.repository.openConversationById(
            currentUserId = "u1",
            conversationId = "single:u1:u2",
            peerId = "u2",
            now = 1_000L
        )

        fixture.repository.handlePacket(singleTextPacket(messageId = "m1", content = "hello"))

        assertNull(fixture.repository.messageAlerts.replayCache.firstOrNull())
    }

    @Test
    fun duplicateIncomingMessageDoesNotEmitSecondAlert() = runTest {
        val fixture = Fixture()
        val packet = singleTextPacket(messageId = "m1", content = "hello")
        val alertDeferred = async { fixture.repository.messageAlerts.first() }
        runCurrent()

        fixture.repository.handlePacket(packet)
        withTimeout(1_000L) { alertDeferred.await() }
        fixture.repository.handlePacket(packet)

        assertEquals(0, fixture.repository.messageAlerts.replayCache.size)
    }

    @Test
    fun incomingSingleTextFallsBackToSenderIdWhenProfileIsMissing() = runTest {
        val fixture = Fixture()
        val alertDeferred = async { fixture.repository.messageAlerts.first() }
        runCurrent()

        fixture.repository.handlePacket(singleTextPacket(messageId = "m1", content = "hello"))

        val alert = withTimeout(1_000L) { alertDeferred.await() }
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
                nickname = "Alice",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L
            )
        )
        fixture.conversationDao.upsertConversation(
            Conversation(
                conversationId = "group:g1",
                peerId = "group:g1",
                peerName = "Project",
                type = ConversationType.GROUP,
                title = "Project",
                avatarUrl = "https://example.com/group.png",
                lastMessageId = "old",
                lastMessagePreview = "old",
                lastMessageTime = 1L,
                unreadCount = 0,
                updatedAt = 1L
            )
        )
        val alertDeferred = async { fixture.repository.messageAlerts.first() }
        runCurrent()

        fixture.repository.handlePacket(groupTextPacket(messageId = "gm1", content = "received"))

        val alert = withTimeout(1_000L) { alertDeferred.await() }
        assertEquals("group:g1", alert.conversationId)
        assertEquals(true, alert.isGroup)
        assertEquals("Project", alert.title)
        assertEquals("https://example.com/group.png", alert.avatarUrl)
        assertEquals("Alice: received", alert.preview)
    }

    @Test
    fun incomingImageUsesImagePreview() = runTest {
        val fixture = Fixture()
        val alertDeferred = async { fixture.repository.messageAlerts.first() }
        runCurrent()

        fixture.repository.handlePacket(singleImagePacket(messageId = "img1"))

        val alert = withTimeout(1_000L) { alertDeferred.await() }
        assertEquals("[图片]", alert.preview)
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
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket): Boolean = true
    }

    private class FakeProfileApi : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("failed")
        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("failed")
        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult = ProfileBatchResult.Failure("failed")
        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("failed")
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

    private fun groupTextPacket(messageId: String, content: String): ImPacket = ImPacket(
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
              "groupId":"g1",
              "groupName":"Project"
            }
        """.trimIndent().replace(Regex("\\s+"), "").toByteArray()
    )
}
