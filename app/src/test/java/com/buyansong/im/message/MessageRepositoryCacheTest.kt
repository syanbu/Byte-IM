package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.MessageDao
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MessageRepositoryCacheTest {

    private class CountingMessageDao(
        private val delegate: InMemoryMessageDao = InMemoryMessageDao()
    ) : MessageDao {
        var queryPageCount = 0

        override fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
            queryPageCount += 1
            return delegate.queryPage(conversationId, beforeTime, limit)
        }

        override fun insertOrIgnore(message: ChatMessage): Boolean = delegate.insertOrIgnore(message)

        override fun queryIncomingImagesMissingLocalThumbnail(
            conversationId: String,
            limit: Int
        ): List<ChatMessage> = delegate.queryIncomingImagesMissingLocalThumbnail(conversationId, limit)

        override fun findByMessageId(messageId: String): ChatMessage? = delegate.findByMessageId(messageId)

        override fun deleteByConversationId(conversationId: String): Int = delegate.deleteByConversationId(conversationId)

        override fun updateImageUploadResult(
            messageId: String,
            imageUrl: String,
            thumbnailUrl: String,
            imageWidth: Int,
            imageHeight: Int,
            mimeType: String,
            fileSizeBytes: Long,
            status: MessageStatus,
            updatedAt: Long
        ): Boolean {
            return delegate.updateImageUploadResult(
                messageId = messageId,
                imageUrl = imageUrl,
                thumbnailUrl = thumbnailUrl,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                mimeType = mimeType,
                fileSizeBytes = fileSizeBytes,
                status = status,
                updatedAt = updatedAt
            )
        }

        override fun updateLocalThumbnailPath(messageId: String, localThumbnailPath: String, updatedAt: Long): Boolean {
            return delegate.updateLocalThumbnailPath(messageId, localThumbnailPath, updatedAt)
        }

        override fun markStatus(messageId: String, status: MessageStatus, updatedAt: Long): Boolean {
            return delegate.markStatus(messageId, status, updatedAt)
        }

        override fun markAcked(messageId: String, serverSeq: Long, updatedAt: Long): Boolean {
            return delegate.markAcked(messageId, serverSeq, updatedAt)
        }

        override fun markFailed(messageId: String, updatedAt: Long): Boolean = delegate.markFailed(messageId, updatedAt)

        override fun markRecalled(messageId: String, recalledBy: String, recalledAt: Long): Boolean {
            return delegate.markRecalled(messageId, recalledBy, recalledAt)
        }

        override fun maxIncomingServerSeq(conversationId: String): Long? = delegate.maxIncomingServerSeq(conversationId)
    }

    private class FakeConnection : ImConnection {
        val sent = mutableListOf<ImPacket>()
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImPacket>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(packet: ImPacket): Boolean {
            sent += packet
            return true
        }
    }

    private fun repository(messageDao: CountingMessageDao): MessageRepository {
        return MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            seqGenerator = SeqGenerator()
        )
    }

    private fun message(
        id: String,
        conversationId: String = "single:u_a:u_b",
        createdAt: Long = 1L
    ): ChatMessage {
        return ChatMessage(
            messageId = id,
            conversationId = conversationId,
            senderId = "u_a",
            receiverId = "u_b",
            clientSeq = createdAt,
            serverSeq = createdAt,
            content = id,
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    @Test
    fun historyPageByConversationId_reusesCachedInitialPage() {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        messageDao.insertOrIgnore(message("m1"))

        assertEquals(listOf("m1"), repository.historyPageByConversationId("single:u_a:u_b", null, 20).map { it.messageId })
        assertEquals(listOf("m1"), repository.historyPageByConversationId("single:u_a:u_b", null, 20).map { it.messageId })

        assertEquals(1, messageDao.queryPageCount)
    }

    @Test
    fun historyPageByConversationId_evictsLeastRecentlyUsedInitialPageAfterTenConversations() {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        (1..11).forEach { index ->
            val conversationId = "single:u_a:u_$index"
            messageDao.insertOrIgnore(message("m$index", conversationId, createdAt = index.toLong()))
            repository.historyPageByConversationId(conversationId, null, 20)
        }
        val queriesAfterWarmup = messageDao.queryPageCount

        repository.historyPageByConversationId("single:u_a:u_1", null, 20)

        assertEquals(queriesAfterWarmup + 1, messageDao.queryPageCount)
    }

    @Test
    fun getCachedInitialPage_returnsCachedPageWithoutQueryingAgain() {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        messageDao.insertOrIgnore(message("m1"))

        repository.preloadInitialPageSync("single:u_a:u_b")
        val cached = repository.getCachedInitialPage("single:u_a:u_b")

        assertNotNull(cached)
        assertEquals(listOf("m1"), cached!!.map { it.messageId })
        assertEquals(1, messageDao.queryPageCount)
    }

    @Test
    fun sendText_invalidatesCachedInitialPageForConversation() {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        messageDao.insertOrIgnore(message("m1"))
        repository.historyPageByConversationId("single:u_a:u_b", null, 20)

        repository.sendText(senderId = "u_a", receiverId = "u_b", content = "fresh", now = 2L)

        assertNull(repository.getCachedInitialPage("single:u_a:u_b"))
    }

    @Test
    fun incomingMessage_invalidatesCachedInitialPageForConversation() {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        messageDao.insertOrIgnore(message("m1"))
        repository.historyPageByConversationId("single:u_a:u_b", null, 20)

        repository.handlePacket(
            ImPacket(
                cmd = ImCommand.RECEIVE_MESSAGE.value,
                body = """
                    {
                      "messageId":"m2",
                      "senderId":"u_a",
                      "receiverId":"u_b",
                      "clientSeq":2,
                      "serverSeq":2,
                      "content":"fresh",
                      "timestamp":2
                    }
                """.trimIndent().toByteArray()
            )
        )

        assertNull(repository.getCachedInitialPage("single:u_a:u_b"))
    }

    @Test
    fun deleteLocalConversation_invalidatesCachedInitialPageForConversation() {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        messageDao.insertOrIgnore(message("m1"))
        repository.historyPageByConversationId("single:u_a:u_b", null, 20)

        repository.deleteLocalConversation("single:u_a:u_b")

        assertNull(repository.getCachedInitialPage("single:u_a:u_b"))
    }
}
