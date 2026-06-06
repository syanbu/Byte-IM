package com.codex.im.message

import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.ConversationType
import com.codex.im.storage.InMemoryConversationDao
import com.codex.im.storage.InMemoryMessageDao
import com.codex.im.storage.InMemoryPendingMessageDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.TransactionRunner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryTest {
    @Test
    fun sendTextStoresSendingMessageAndSendsPacket() {
        val fixture = Fixture()

        val message = fixture.repository.sendText(
            senderId = "u1",
            receiverId = "u2",
            content = "hello",
            now = 1_000L
        )

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.SENDING, stored.status)
        assertEquals(MessageDirection.OUTGOING, stored.direction)
        assertEquals("hello", stored.content)
        assertEquals(ImCommand.SEND_MESSAGE.value, fixture.connection.sentPackets.single().cmd)
        assertEquals(message.messageId, fixture.pendingDao.dueMessages(now = 6_000L, limit = 10).single().messageId)
    }

    @Test
    fun sendTextSendsNetworkPacketOnlyAfterLocalTransactionCommits() {
        val events = mutableListOf<String>()
        val fixture = Fixture(
            transactionRunner = RecordingTransactionRunner(events),
            events = events
        )

        fixture.repository.sendText(
            senderId = "u1",
            receiverId = "u2",
            content = "hello",
            now = 1_000L
        )

        assertEquals(listOf("begin", "commit", "send"), events)
    }

    @Test
    fun messageAckMarksMessageSentAndRemovesPendingRecord() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.MESSAGE_ACK.value,
                body = """{"messageId":"${message.messageId}","serverSeq":88,"serverTime":1200}""".toByteArray()
            )
        )

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.SENT, stored.status)
        assertEquals(88L, stored.serverSeq)
        assertTrue(fixture.pendingDao.dueMessages(now = 6_000L, limit = 10).isEmpty())
    }

    @Test
    fun retryDuePendingMessagesResendsOriginalPacketBodyAndSchedulesBackoff() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        val originalPacket = fixture.connection.sentPackets.single()

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        assertEquals(2, fixture.connection.sentPackets.size)
        val retriedPacket = fixture.connection.sentPackets.last()
        assertEquals(originalPacket.cmd, retriedPacket.cmd)
        assertEquals(originalPacket.body.decodeToString(), retriedPacket.body.decodeToString())
        val pending = fixture.pendingDao.dueMessages(now = 16_000L, limit = 10).single()
        assertEquals(message.messageId, pending.messageId)
        assertEquals(1, pending.retryCount)
        assertEquals(11_000L, pending.nextRetryAt)
    }

    @Test
    fun retryDuePendingMessagesDoesNotRecreatePendingAfterAckArrivesDuringRetrySend() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        fixture.connection.onSend = {
            fixture.repository.handlePacket(
                ImPacket(
                    cmd = ImCommand.MESSAGE_ACK.value,
                    body = """{"messageId":"${message.messageId}","serverSeq":88,"serverTime":1200}""".toByteArray()
                )
            )
        }

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals(MessageStatus.SENT, stored?.status)
        assertTrue(fixture.pendingDao.dueMessages(now = 999_999L, limit = 10).isEmpty())
    }

    @Test
    fun retryDuePendingMessagesMarksFailedAfterRetryExhaustion() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        val exhausted = fixture.pendingDao.dueMessages(now = 6_000L, limit = 10)
            .single()
            .copy(retryCount = 5, nextRetryAt = 6_000L)
        fixture.pendingDao.upsert(exhausted)

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.FAILED, stored.status)
        assertTrue(fixture.pendingDao.dueMessages(now = 60_000L, limit = 10).isEmpty())
        assertEquals(1, fixture.connection.sentPackets.size)
    }

    @Test
    fun retryDuePendingMessagesCountsAttemptWhenSendReturnsFalse() {
        val fixture = Fixture()
        fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        fixture.connection.sendSucceeds = false

        fixture.repository.retryDuePendingMessages(now = 6_000L)

        val pending = fixture.pendingDao.dueMessages(now = 11_000L, limit = 10).single()
        assertEquals(1, pending.retryCount)
        assertEquals(2, fixture.connection.sentPackets.size)
    }

    @Test
    fun retryDuePendingMessagesMarksFailedAfterRepeatedSendFailures() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        fixture.connection.sendSucceeds = false

        fixture.repository.retryDuePendingMessages(now = 6_000L)
        fixture.repository.retryDuePendingMessages(now = 11_000L)
        fixture.repository.retryDuePendingMessages(now = 21_000L)
        fixture.repository.retryDuePendingMessages(now = 41_000L)
        fixture.repository.retryDuePendingMessages(now = 81_000L)
        fixture.repository.retryDuePendingMessages(now = 141_000L)

        val stored = fixture.messageDao.queryPage(message.conversationId, beforeTime = null, limit = 20).single()
        assertEquals(MessageStatus.FAILED, stored.status)
        assertTrue(fixture.pendingDao.dueMessages(now = 141_000L, limit = 10).isEmpty())
    }

    @Test
    fun incomingMessageIsPersistedAndIncrementsUnread() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-1",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":7,
                      "serverSeq":90,
                      "content":"hi",
                      "timestamp":1500
                    }
                """.trimIndent().toByteArray()
            )
        )

        val stored = fixture.messageDao.queryPage("single:u1:u2", beforeTime = null, limit = 20).single()
        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(MessageStatus.RECEIVED, stored.status)
        assertEquals(MessageDirection.INCOMING, stored.direction)
        assertEquals("hi", conversation.lastMessagePreview)
        assertEquals(1, conversation.unreadCount)
        assertEquals(ImCommand.DELIVERY_ACK.value, fixture.connection.sentPackets.single().cmd)
        assertEquals(
            """{"messageId":"remote-1","conversationId":"single:u1:u2","serverSeq":90,"receiverId":"u1"}""",
            fixture.connection.sentPackets.single().body.decodeToString()
        )
    }

    @Test
    fun incomingMessageForOpenConversationDoesNotIncrementUnread() {
        val fixture = Fixture()
        fixture.repository.openConversation(currentUserId = "u1", peerId = "u2")

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-open-1",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":8,
                      "serverSeq":91,
                      "content":"already reading",
                      "timestamp":1600
                    }
                """.trimIndent().toByteArray()
            )
        )

        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals("already reading", conversation.lastMessagePreview)
        assertEquals(0, conversation.unreadCount)
    }

    @Test
    fun openConversationSendsReadAckForLatestIncomingServerSeq() {
        val fixture = Fixture()
        fixture.repository.handlePacket(incomingMessagePacket("remote-read-1", "u2", "u1", 1, 6, "read me", 1_000L))
        fixture.connection.sentPackets.clear()

        fixture.repository.openConversation(currentUserId = "u1", peerId = "u2", now = 2_000L)

        val packet = fixture.connection.sentPackets.single()
        assertEquals(ImCommand.READ_ACK.value, packet.cmd)
        assertEquals(
            """{"conversationId":"single:u1:u2","readerId":"u1","peerId":"u2","readUpToServerSeq":6,"readAt":2000}""",
            packet.body.decodeToString()
        )
    }

    @Test
    fun readAckIsNotSentRepeatedlyForSameOrLowerCursor() {
        val fixture = Fixture()
        fixture.repository.handlePacket(incomingMessagePacket("remote-read-1", "u2", "u1", 1, 6, "read me", 1_000L))
        fixture.connection.sentPackets.clear()

        fixture.repository.openConversation(currentUserId = "u1", peerId = "u2", now = 2_000L)
        fixture.repository.openConversation(currentUserId = "u1", peerId = "u2", now = 3_000L)

        assertEquals(1, fixture.connection.sentPackets.size)
    }

    @Test
    fun incomingMessageForOpenConversationSendsNewReadAckAfterPersisting() {
        val fixture = Fixture()
        fixture.repository.openConversation(currentUserId = "u1", peerId = "u2", now = 1_000L)
        fixture.connection.sentPackets.clear()

        fixture.repository.handlePacket(incomingMessagePacket("remote-open-read", "u2", "u1", 1, 7, "new", 2_000L))

        assertEquals(listOf(ImCommand.DELIVERY_ACK.value, ImCommand.READ_ACK.value), fixture.connection.sentPackets.map { it.cmd })
        assertEquals(
            """{"conversationId":"single:u1:u2","readerId":"u1","peerId":"u2","readUpToServerSeq":7,"readAt":2000}""",
            fixture.connection.sentPackets.last().body.decodeToString()
        )
    }

    @Test
    fun inboundReadAckUpdatesConversationPeerReadCursor() {
        val fixture = Fixture()
        fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.READ_ACK.value,
                body = """{"conversationId":"single:u1:u2","readerId":"u2","readUpToServerSeq":12,"readAt":3000}""".toByteArray()
            )
        )

        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(12L, conversation.peerReadUpToServerSeq)
        assertEquals(3_000L, conversation.peerReadAt)
    }

    @Test
    fun sendRecallRequestSendsRecallMessagePacket() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "hello", now = 1_000L)
        fixture.repository.handlePacket(ImPacket(ImCommand.MESSAGE_ACK.value, """{"messageId":"${message.messageId}","serverSeq":8,"serverTime":1100}""".toByteArray()))
        fixture.connection.sentPackets.clear()

        assertEquals(true, fixture.repository.recallMessage(message.messageId, requesterId = "u1", now = 2_000L))

        val packet = fixture.connection.sentPackets.single()
        assertEquals(ImCommand.RECALL_MESSAGE.value, packet.cmd)
        assertTrue(packet.body.decodeToString().contains(""""messageId":"${message.messageId}""""))
        assertTrue(packet.body.decodeToString().contains(""""requesterId":"u1""""))
    }

    @Test
    fun successfulRecallAckMarksRequesterMessageRecalledAndUpdatesPreview() {
        val fixture = Fixture()
        val message = fixture.repository.sendText("u1", "u2", "secret", now = 1_000L)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECALL_ACK.value,
                body = """{"messageId":"${message.messageId}","conversationId":"single:u1:u2","success":true,"recalledBy":"u1","recalledAt":2000}""".toByteArray()
            )
        )

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals(true, stored?.isRecalled)
        assertEquals("你撤回了一条消息", fixture.conversationDao.listConversations(limit = 20).single().lastMessagePreview)
    }

    @Test
    fun recallAckFailureEmitsRetryableRecallFailureEvent() = runTest {
        val fixture = Fixture()
        val failures = mutableListOf<String>()
        val job = launch {
            fixture.repository.recallFailures.collect { failures += it }
        }
        runCurrent()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECALL_ACK.value,
                body = """{"messageId":"missing","conversationId":"single:u1:u2","success":false,"reason":"EXPIRED"}""".toByteArray()
            )
        )
        runCurrent()

        assertEquals(listOf("撤回失败，请重试"), failures)
        job.cancel()
    }

    @Test
    fun recallNotifyMarksPeerMessageRecalledAndUpdatesPreview() {
        val fixture = Fixture()
        fixture.repository.handlePacket(incomingMessagePacket("remote-recall", "u2", "u1", 1, 7, "secret", 1_000L))
        fixture.connection.sentPackets.clear()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECALL_NOTIFY.value,
                body = """{"messageId":"remote-recall","conversationId":"single:u1:u2","recalledBy":"u2","recalledAt":2000}""".toByteArray()
            )
        )

        val stored = fixture.messageDao.findByMessageId("remote-recall")
        assertEquals(true, stored?.isRecalled)
        assertEquals("对方撤回了一条消息", fixture.conversationDao.listConversations(limit = 20).single().lastMessagePreview)
        val ack = fixture.connection.sentPackets.single()
        assertEquals(ImCommand.RECALL_NOTIFY_ACK.value, ack.cmd)
        assertEquals(
            """{"messageId":"remote-recall","conversationId":"single:u1:u2","receiverId":"u1","recalledAt":2000}""",
            ack.body.decodeToString()
        )
    }

    @Test
    fun duplicateIncomingMessageStillSendsDeliveryAckWithoutDuplicatingLocalRow() {
        val fixture = Fixture()
        val packet = ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """
                {
                  "messageId":"remote-dup-1",
                  "conversationId":"single:u2:u1",
                  "senderId":"u2",
                  "receiverId":"u1",
                  "clientSeq":9,
                  "serverSeq":92,
                  "content":"dup",
                  "timestamp":1700
                }
            """.trimIndent().toByteArray()
        )

        fixture.repository.handlePacket(packet)
        fixture.repository.handlePacket(packet)

        val stored = fixture.messageDao.queryPage("single:u1:u2", beforeTime = null, limit = 20)
        assertEquals(listOf("remote-dup-1"), stored.map { it.messageId })
        assertEquals(2, fixture.connection.sentPackets.size)
        assertTrue(fixture.connection.sentPackets.all { it.cmd == ImCommand.DELIVERY_ACK.value })
    }

    @Test
    fun totalUnreadCountReturnsUnreadAcrossAllConversations() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            incomingMessagePacket(
                messageId = "remote-1",
                senderId = "u2",
                receiverId = "u1",
                clientSeq = 7,
                serverSeq = 90,
                content = "hi",
                timestamp = 1_500L
            )
        )
        fixture.repository.handlePacket(
            incomingMessagePacket(
                messageId = "remote-2",
                senderId = "u3",
                receiverId = "u1",
                clientSeq = 8,
                serverSeq = 91,
                content = "hello",
                timestamp = 1_600L
            )
        )

        assertEquals(2, fixture.repository.totalUnreadCount())
    }

    @Test
    fun conversationPageReturnsNextPageAfterCursor() {
        val fixture = Fixture()
        fixture.repository.sendText("u1", "u2", "old", now = 1_000L)
        fixture.repository.sendText("u1", "u3", "middle", now = 2_000L)
        fixture.repository.sendText("u1", "u4", "new", now = 3_000L)

        val firstPage = fixture.repository.conversationPage(
            beforeLastMessageTime = null,
            beforeConversationId = null,
            limit = 2
        )
        val secondPage = fixture.repository.conversationPage(
            beforeLastMessageTime = firstPage.last().lastMessageTime,
            beforeConversationId = firstPage.last().conversationId,
            limit = 2
        )

        assertEquals(listOf("single:u1:u4", "single:u1:u3"), firstPage.map { it.conversationId })
        assertEquals(listOf("single:u1:u2"), secondPage.map { it.conversationId })
    }

    @Test
    fun sendGroupTextStoresPendingGroupMessageAndMentionPayload() {
        val fixture = Fixture()

        val message = fixture.repository.sendGroupText(
            senderId = "u1",
            groupId = "g_1001",
            content = "@u2 hello",
            mentionedUserIds = listOf("u2"),
            now = 1_000L
        )

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals("group:g_1001", stored?.conversationId)
        assertEquals(ConversationType.GROUP, stored?.conversationType)
        assertEquals("g_1001", stored?.groupId)
        assertEquals(listOf("u2"), stored?.mentionedUserIds)
        assertEquals(message.messageId, fixture.pendingDao.dueMessages(now = 6_000L, limit = 10).single().messageId)
        val body = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(body.contains(""""conversationType":"GROUP""""))
        assertTrue(body.contains(""""groupId":"g_1001""""))
        assertTrue(body.contains(""""content":"@u2 hello""""))
        assertTrue(body.contains(""""mentionedUserIds":["u2"]"""))
    }

    @Test
    fun incomingGroupMessageUsesPacketConversationIdAndMentionUnread() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"group-remote-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":7,
                      "serverSeq":90,
                      "type":"TEXT",
                      "content":"@u1 hi",
                      "mentionedUserIds":["u1"],
                      "timestamp":1500
                    }
                """.trimIndent().toByteArray()
            )
        )

        val stored = fixture.messageDao.queryPage("group:g_1001", beforeTime = null, limit = 20).single()
        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(ConversationType.GROUP, stored.conversationType)
        assertEquals("g_1001", stored.groupId)
        assertEquals(listOf("u1"), stored.mentionedUserIds)
        assertEquals("group:g_1001", conversation.conversationId)
        assertEquals(1, conversation.unreadCount)
        assertEquals(1, conversation.mentionUnreadCount)
        assertEquals(
            """{"messageId":"group-remote-1","conversationId":"group:g_1001","serverSeq":90,"receiverId":"u1"}""",
            fixture.connection.sentPackets.single().body.decodeToString()
        )
    }

    @Test
    fun incomingGroupMessageCreatesConversationWithGroupTitleFromPacket() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"group-title-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "groupName":"群聊(2)",
                      "senderId":"13800113800",
                      "receiverId":"13900113900",
                      "clientSeq":1,
                      "serverSeq":93,
                      "content":"grouptest1111",
                      "mentionedUserIds":[],
                      "timestamp":1800
                    }
                """.trimIndent().toByteArray()
            )
        )

        val conversation = fixture.conversationDao.findConversation("group:g_1001")
        assertEquals("group:g_1001", conversation?.conversationId)
        assertEquals("group:g_1001", conversation?.peerId)
        assertEquals("群聊(2)", conversation?.peerName)
        assertEquals("群聊(2)", conversation?.title)
    }

    @Test
    fun incomingGroupMentionForOtherUserDoesNotIncrementMentionUnread() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"group-remote-2",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":8,
                      "serverSeq":91,
                      "content":"@u3 hi",
                      "mentionedUserIds":["u3"],
                      "timestamp":1600
                    }
                """.trimIndent().toByteArray()
            )
        )

        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(1, conversation.unreadCount)
        assertEquals(0, conversation.mentionUnreadCount)
    }

    @Test
    fun incomingGroupMessageForOpenConversationDoesNotIncrementUnread() {
        val fixture = Fixture()
        fixture.repository.openConversationById(currentUserId = "u1", conversationId = "group:g_1001")

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"group-open-1",
                      "conversationId":"group:g_1001",
                      "conversationType":"GROUP",
                      "groupId":"g_1001",
                      "senderId":"u2",
                      "receiverId":"u1",
                      "clientSeq":9,
                      "serverSeq":92,
                      "content":"@u1 while open",
                      "mentionedUserIds":["u1"],
                      "timestamp":1700
                    }
                """.trimIndent().toByteArray()
            )
        )

        val conversation = fixture.conversationDao.listConversations(limit = 20).single()
        assertEquals(0, conversation.unreadCount)
        assertEquals(0, conversation.mentionUnreadCount)
    }

    @Test
    fun createLocalImageMessageStoresUploadingMessageWithoutPendingRow() {
        val fixture = Fixture()

        val message = fixture.repository.createLocalImageMessage(
            senderId = "u1",
            receiverId = "u2",
            localOriginalPath = "cache/original.jpg",
            localThumbnailPath = "cache/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            now = 1_000L
        )

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals(MessageStatus.UPLOADING, stored?.status)
        assertEquals(MessageType.IMAGE, stored?.type)
        assertEquals("[图片]", stored?.content)
        assertEquals("cache/original.jpg", stored?.localOriginalPath)
        assertEquals(1440, stored?.imageWidth)
        assertEquals(960, stored?.imageHeight)
        assertTrue(fixture.pendingDao.dueMessages(now = 999_999L, limit = 10).isEmpty())
        assertEquals("[图片]", fixture.conversationDao.listConversations(limit = 20).single().lastMessagePreview)
        assertTrue(fixture.connection.sentPackets.isEmpty())
    }

    @Test
    fun completeImageUploadAndQueueSendCreatesPendingRowAndSendsPacket() {
        val fixture = Fixture()
        val message = fixture.repository.createLocalImageMessage(
            senderId = "u1",
            receiverId = "u2",
            localOriginalPath = "cache/original.jpg",
            localThumbnailPath = "cache/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            now = 1_000L
        )

        fixture.repository.completeImageUploadAndQueueSend(
            messageId = message.messageId,
            imageUrl = "https://oss.example.com/origin.jpg",
            thumbnailUrl = "https://oss.example.com/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            fileSizeBytes = 345_678L,
            now = 2_000L
        )

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals(MessageStatus.SENDING, stored?.status)
        assertEquals("https://oss.example.com/origin.jpg", stored?.imageUrl)
        assertEquals("https://oss.example.com/thumb.jpg", stored?.thumbnailUrl)
        assertEquals(ImCommand.SEND_MESSAGE.value, fixture.connection.sentPackets.single().cmd)
        assertEquals(message.messageId, fixture.pendingDao.dueMessages(now = 9_999L, limit = 10).single().messageId)
    }

    @Test
    fun markImageUploadFailedDoesNotCreatePendingRow() {
        val fixture = Fixture()
        val message = fixture.repository.createLocalImageMessage(
            senderId = "u1",
            receiverId = "u2",
            localOriginalPath = "cache/original.jpg",
            localThumbnailPath = "cache/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            now = 1_000L
        )

        fixture.repository.markImageUploadFailed(message.messageId, now = 2_000L)

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals(MessageStatus.UPLOAD_FAILED, stored?.status)
        assertTrue(fixture.pendingDao.dueMessages(now = 999_999L, limit = 10).isEmpty())
        assertTrue(fixture.connection.sentPackets.isEmpty())
    }

    @Test
    fun incomingImageMessagePersistsTypeAndImageFields() {
        val fixture = Fixture()

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-image-1",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
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
        )

        val stored = fixture.messageDao.findByMessageId("remote-image-1")
        assertEquals(MessageType.IMAGE, stored?.type)
        assertEquals("https://oss.example.com/origin.jpg", stored?.imageUrl)
        assertEquals("https://oss.example.com/thumb.jpg", stored?.thumbnailUrl)
        assertEquals(900, stored?.imageWidth)
        assertEquals(600, stored?.imageHeight)
        assertEquals("image/jpeg", stored?.mimeType)
        assertEquals(456_789L, stored?.fileSizeBytes)
        assertEquals(MessageStatus.RECEIVED, stored?.status)
    }

    @Test
    fun incomingImageMessageCachesRemoteThumbnailAfterPersistingMessage() {
        val thumbnailCache = FakeThumbnailCache(localPath = "cache/thumb-remote-image-1.jpg")
        val fixture = Fixture(thumbnailCache = thumbnailCache)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-image-1",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
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
        )

        val stored = fixture.messageDao.findByMessageId("remote-image-1")
        assertEquals(listOf("remote-image-1" to "https://oss.example.com/thumb.jpg"), thumbnailCache.requests)
        assertEquals("cache/thumb-remote-image-1.jpg", stored?.localThumbnailPath)
    }

    @Test
    fun incomingImageMessageEnqueuesThumbnailDownloadWithNormalPriority() {
        val scheduler = RecordingThumbnailDownloadScheduler()
        val fixture = Fixture(thumbnailDownloadScheduler = scheduler)

        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-normal-priority"))

        assertEquals(
            listOf(
                ThumbnailDownloadRequest(
                    messageId = "remote-image-normal-priority",
                    thumbnailUrl = "https://oss.example.com/thumb.jpg",
                    priority = ThumbnailDownloadPriority.NORMAL
                )
            ),
            scheduler.requests
        )
        assertEquals(null, fixture.messageDao.findByMessageId("remote-image-normal-priority")?.localThumbnailPath)
    }

    @Test
    fun retryIncomingImageThumbnailEnqueuesHighPriorityDownloadAndCallbackUpdatesLocalPath() {
        val scheduler = RecordingThumbnailDownloadScheduler()
        val fixture = Fixture(thumbnailDownloadScheduler = scheduler)
        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-high-priority"))

        val accepted = fixture.repository.retryIncomingImageThumbnail("remote-image-high-priority")
        scheduler.complete("remote-image-high-priority", "cache/thumb-high-priority.jpg")

        assertEquals(true, accepted)
        assertEquals(
            listOf(
                ThumbnailDownloadRequest(
                    messageId = "remote-image-high-priority",
                    thumbnailUrl = "https://oss.example.com/thumb.jpg",
                    priority = ThumbnailDownloadPriority.NORMAL
                ),
                ThumbnailDownloadRequest(
                    messageId = "remote-image-high-priority",
                    thumbnailUrl = "https://oss.example.com/thumb.jpg",
                    priority = ThumbnailDownloadPriority.HIGH
                )
            ),
            scheduler.requests
        )
        assertEquals(
            "cache/thumb-high-priority.jpg",
            fixture.messageDao.findByMessageId("remote-image-high-priority")?.localThumbnailPath
        )
    }

    @Test
    fun historyPageHidesIncomingImageMessageUntilThumbnailIsCached() {
        val thumbnailCache = FakeThumbnailCache(localPath = null)
        val fixture = Fixture(thumbnailCache = thumbnailCache)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-image-uncached",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
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
        )

        assertEquals("remote-image-uncached", fixture.messageDao.findByMessageId("remote-image-uncached")?.messageId)
        assertEquals(
            emptyList<ChatMessage>(),
            fixture.repository.historyPage(userId = "u1", peerId = "u2", beforeTime = null, limit = 20)
        )
    }

    @Test
    fun historyPageShowsIncomingImageMessageAfterThumbnailIsCached() {
        val thumbnailCache = FakeThumbnailCache(localPath = "cache/thumb-remote-image-cached.jpg")
        val fixture = Fixture(thumbnailCache = thumbnailCache)

        fixture.repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"remote-image-cached",
                      "conversationId":"single:u2:u1",
                      "senderId":"u2",
                      "receiverId":"u1",
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
        )

        assertEquals(
            listOf("remote-image-cached"),
            fixture.repository.historyPage(userId = "u1", peerId = "u2", beforeTime = null, limit = 20)
                .map { it.messageId }
        )
    }

    @Test
    fun missingIncomingImageThumbnailsReturnsPersistedHiddenImages() {
        val thumbnailCache = FakeThumbnailCache(localPath = null)
        val fixture = Fixture(thumbnailCache = thumbnailCache)
        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-missing"))

        assertEquals(
            listOf("remote-image-missing"),
            fixture.repository.missingIncomingImageThumbnails(userId = "u1", peerId = "u2", limit = 20)
                .map { it.messageId }
        )
    }

    @Test
    fun retryIncomingImageThumbnailCachesImageAndMakesItDisplayable() {
        val thumbnailCache = FakeThumbnailCache(localPaths = mutableListOf(null, "cache/thumb-after-retry.jpg"))
        val fixture = Fixture(thumbnailCache = thumbnailCache)
        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-retry"))

        val changed = fixture.repository.retryIncomingImageThumbnail("remote-image-retry")

        assertEquals(true, changed)
        assertEquals("cache/thumb-after-retry.jpg", fixture.messageDao.findByMessageId("remote-image-retry")?.localThumbnailPath)
        assertEquals(
            listOf("remote-image-retry"),
            fixture.repository.historyPage(userId = "u1", peerId = "u2", beforeTime = null, limit = 20)
                .map { it.messageId }
        )
    }

    @Test
    fun retryIncomingImageThumbnailKeepsImageHiddenWhenCacheStillFails() {
        val thumbnailCache = FakeThumbnailCache(localPath = null)
        val fixture = Fixture(thumbnailCache = thumbnailCache)
        fixture.repository.handlePacket(incomingImagePacket(messageId = "remote-image-still-hidden"))

        val changed = fixture.repository.retryIncomingImageThumbnail("remote-image-still-hidden")

        assertEquals(false, changed)
        assertEquals(
            emptyList<ChatMessage>(),
            fixture.repository.historyPage(userId = "u1", peerId = "u2", beforeTime = null, limit = 20)
        )
    }

    @Test
    fun recentLocalThumbnailPathsReturnsCachedImagePathsForPeer() {
        val fixture = Fixture()
        fixture.messageDao.insertOrIgnore(
            sampleImageMessage(
                messageId = "image-older",
                createdAt = 1_000L,
                localThumbnailPath = "cache/older.jpg"
            )
        )
        fixture.messageDao.insertOrIgnore(
            sampleImageMessage(
                messageId = "image-newer",
                createdAt = 2_000L,
                localThumbnailPath = "cache/newer.jpg"
            )
        )
        fixture.messageDao.insertOrIgnore(
            sampleImageMessage(
                messageId = "image-uncached",
                createdAt = 3_000L,
                localThumbnailPath = null
            )
        )

        val paths = fixture.repository.recentLocalThumbnailPaths(
            userId = "u1",
            peerId = "u2",
            limit = 10
        )

        assertEquals(listOf("cache/newer.jpg", "cache/older.jpg"), paths)
    }

    @Test
    fun completeImageUploadAndQueueSendBuildsImagePayloadJson() {
        val fixture = Fixture()
        val message = fixture.repository.createLocalImageMessage(
            senderId = "u1",
            receiverId = "u2",
            localOriginalPath = "cache/original.jpg",
            localThumbnailPath = "cache/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            now = 1_000L
        )

        fixture.repository.completeImageUploadAndQueueSend(
            messageId = message.messageId,
            imageUrl = "https://oss.example.com/origin.jpg",
            thumbnailUrl = "https://oss.example.com/thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            fileSizeBytes = 345_678L,
            now = 2_000L
        )

        val body = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(body.contains(""""type":"IMAGE""""))
        assertTrue(body.contains(""""imageUrl":"https://oss.example.com/origin.jpg""""))
        assertTrue(body.contains(""""thumbnailUrl":"https://oss.example.com/thumb.jpg""""))
    }

    @Test
    fun createLocalGroupImageMessageStoresUnderGroupConversation() {
        val fixture = Fixture()

        val message = fixture.repository.createLocalGroupImageMessage(
            senderId = "u1",
            groupId = "g_1001",
            localOriginalPath = "cache/group-original.jpg",
            localThumbnailPath = "cache/group-thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            now = 1_000L
        )

        val stored = fixture.messageDao.findByMessageId(message.messageId)
        assertEquals("group:g_1001", stored?.conversationId)
        assertEquals(ConversationType.GROUP, stored?.conversationType)
        assertEquals("g_1001", stored?.groupId)
        assertEquals("g_1001", stored?.receiverId)
        assertEquals(listOf("group:g_1001"), fixture.conversationDao.listConversations(limit = 20).map { it.conversationId })
    }

    @Test
    fun completeGroupImageUploadAndQueueSendBuildsGroupImagePayloadJson() {
        val fixture = Fixture()
        val message = fixture.repository.createLocalGroupImageMessage(
            senderId = "u1",
            groupId = "g_1001",
            localOriginalPath = "cache/group-original.jpg",
            localThumbnailPath = "cache/group-thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            now = 1_000L
        )

        fixture.repository.completeImageUploadAndQueueSend(
            messageId = message.messageId,
            imageUrl = "https://oss.example.com/group-origin.jpg",
            thumbnailUrl = "https://oss.example.com/group-thumb.jpg",
            imageWidth = 1440,
            imageHeight = 960,
            mimeType = "image/jpeg",
            fileSizeBytes = 345_678L,
            now = 2_000L
        )

        val body = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(body.contains(""""conversationId":"group:g_1001""""))
        assertTrue(body.contains(""""conversationType":"GROUP""""))
        assertTrue(body.contains(""""groupId":"g_1001""""))
        assertTrue(body.contains(""""receiverId":"g_1001""""))
        assertTrue(body.contains(""""type":"IMAGE""""))
        assertTrue(body.contains(""""imageUrl":"https://oss.example.com/group-origin.jpg""""))
        assertTrue(body.contains(""""thumbnailUrl":"https://oss.example.com/group-thumb.jpg""""))
    }

    @Test
    fun requeueFailedImageMessageCreatesPendingRowAndSendsExistingOssPayload() {
        val fixture = Fixture()
        val message = sampleImageMessage(
            messageId = "failed-image-1",
            createdAt = 1_000L,
            localThumbnailPath = "cache/thumb.jpg"
        ).copy(
            status = MessageStatus.FAILED,
            imageUrl = "https://oss.example.com/origin-existing.jpg",
            thumbnailUrl = "https://oss.example.com/thumb-existing.jpg"
        )
        fixture.messageDao.insertOrIgnore(message)

        val changed = fixture.repository.requeueImageMessageSend("failed-image-1", now = 2_000L)

        val stored = fixture.messageDao.findByMessageId("failed-image-1")
        assertEquals(true, changed)
        assertEquals(MessageStatus.SENDING, stored?.status)
        assertEquals("failed-image-1", fixture.pendingDao.dueMessages(now = 7_000L, limit = 10).single().messageId)
        val body = fixture.connection.sentPackets.single().body.decodeToString()
        assertTrue(body.contains(""""imageUrl":"https://oss.example.com/origin-existing.jpg""""))
        assertTrue(body.contains(""""thumbnailUrl":"https://oss.example.com/thumb-existing.jpg""""))
    }

    private class Fixture(
        transactionRunner: TransactionRunner? = null,
        events: MutableList<String> = mutableListOf(),
        thumbnailCache: ChatThumbnailCache = NoopChatThumbnailCache,
        thumbnailDownloadScheduler: ThumbnailDownloadScheduler = ImmediateThumbnailDownloadScheduler(thumbnailCache)
    ) {
        val events = events
        val messageDao = InMemoryMessageDao()
        val conversationDao = InMemoryConversationDao()
        val pendingDao = InMemoryPendingMessageDao()
        val connection = FakeConnection(events)
        val repository = MessageRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            pendingMessageDao = pendingDao,
            connection = connection,
            messageIdGenerator = MessageIdGenerator(startCounter = 1),
            seqGenerator = SeqGenerator(),
            transactionRunner = transactionRunner ?: TransactionRunner.immediate(),
            thumbnailCache = thumbnailCache,
            thumbnailDownloadScheduler = thumbnailDownloadScheduler
        )
    }

    private data class ThumbnailDownloadRequest(
        val messageId: String,
        val thumbnailUrl: String,
        val priority: ThumbnailDownloadPriority
    )

    private class RecordingThumbnailDownloadScheduler : ThumbnailDownloadScheduler {
        val requests = mutableListOf<ThumbnailDownloadRequest>()
        private val callbacks = mutableMapOf<String, (String, String) -> Unit>()

        override fun enqueue(
            message: ChatMessage,
            priority: ThumbnailDownloadPriority,
            onCached: (messageId: String, localThumbnailPath: String) -> Unit
        ): Boolean {
            requests += ThumbnailDownloadRequest(
                messageId = message.messageId,
                thumbnailUrl = message.thumbnailUrl.orEmpty(),
                priority = priority
            )
            callbacks[message.messageId] = onCached
            return true
        }

        fun complete(messageId: String, localThumbnailPath: String) {
            callbacks.getValue(messageId).invoke(messageId, localThumbnailPath)
        }
    }

    private class FakeConnection(
        private val events: MutableList<String> = mutableListOf()
    ) : ImConnection {
        val sentPackets = mutableListOf<ImPacket>()
        var sendSucceeds = true
        var onSend: (() -> Unit)? = null
        override val states: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)
        override val incomingPackets: SharedFlow<ImPacket> = MutableSharedFlow()

        override fun connect(token: String) = Unit

        override fun disconnect() = Unit

        override fun send(packet: ImPacket): Boolean {
            events += "send"
            sentPackets.add(packet)
            onSend?.invoke()
            return sendSucceeds
        }
    }

    private class RecordingTransactionRunner(
        private val events: MutableList<String>
    ) : TransactionRunner {
        override fun runInTransaction(block: () -> Unit) {
            events += "begin"
            block()
            events += "commit"
        }
    }

    private class FakeThumbnailCache(
        private val localPaths: MutableList<String?>
    ) : ChatThumbnailCache {
        constructor(localPath: String?) : this(mutableListOf(localPath))

        val requests = mutableListOf<Pair<String, String>>()

        override fun cacheThumbnail(messageId: String, thumbnailUrl: String): String? {
            requests += messageId to thumbnailUrl
            return if (localPaths.size > 1) localPaths.removeAt(0) else localPaths.firstOrNull()
        }
    }

    private fun incomingImagePacket(messageId: String): ImPacket {
        return ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """
                {
                  "messageId":"$messageId",
                  "conversationId":"single:u2:u1",
                  "senderId":"u2",
                  "receiverId":"u1",
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

    private fun sampleImageMessage(
        messageId: String,
        createdAt: Long,
        localThumbnailPath: String?
    ) = com.codex.im.storage.ChatMessage(
        messageId = messageId,
        conversationId = "single:u1:u2",
        senderId = "u1",
        receiverId = "u2",
        clientSeq = createdAt,
        serverSeq = null,
        content = "[图片]",
        status = MessageStatus.SENT,
        direction = MessageDirection.OUTGOING,
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

    private fun incomingMessagePacket(
        messageId: String,
        senderId: String,
        receiverId: String,
        clientSeq: Long,
        serverSeq: Long,
        content: String,
        timestamp: Long
    ): ImPacket {
        return ImPacket(
            cmd = ImCommand.RECEIVE_MESSAGE.value,
            body = """
                {
                  "messageId":"$messageId",
                  "conversationId":"single:$senderId:$receiverId",
                  "senderId":"$senderId",
                  "receiverId":"$receiverId",
                  "clientSeq":$clientSeq,
                  "serverSeq":$serverSeq,
                  "content":"$content",
                  "timestamp":$timestamp
                }
            """.trimIndent().toByteArray()
        )
    }
}
