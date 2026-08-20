package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.protocol.v2.ChatMessagePayload
import com.buyansong.im.protocol.v2.ImagePayload
import com.buyansong.im.protocol.v2.ImEnvelope
import com.buyansong.im.protocol.v2.ReceiveMessage
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.protocol.v2.ConversationType as ProtoConversationType
import com.buyansong.im.protocol.v2.MessageType as ProtoMessageType
import com.buyansong.im.storage.InMemoryConversationDao
import com.buyansong.im.storage.InMemoryMessageDao
import com.buyansong.im.storage.InMemoryPendingMessageDao
import com.buyansong.im.storage.MessageDao
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRepositoryCacheTest {

    private class CountingMessageDao(
        private val delegate: InMemoryMessageDao = InMemoryMessageDao()
    ) : MessageDao {
        var queryPageCount = 0
        val queryPageLimits = mutableListOf<Int>()

        override fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
            queryPageCount += 1
            queryPageLimits += limit
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

    private class BlockingQueryMessageDao(
        private val delegate: CountingMessageDao,
        private val queryStarted: CountDownLatch,
        private val releaseQuery: CountDownLatch
    ) : MessageDao by delegate {
        override fun queryPage(conversationId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS)
            return delegate.queryPage(conversationId, beforeTime, limit)
        }
    }

    private class FakeConnection : ImConnection {
        val sent = mutableListOf<ImEnvelope>()
        override val states = MutableStateFlow(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImEnvelope>()
        override fun connect(token: String) = Unit
        override fun disconnect() = Unit
        override fun send(envelope: ImEnvelope): Boolean {
            sent += envelope
            return true
        }
    }

    private class CapturingThumbnailScheduler : ThumbnailDownloadScheduler {
        val enqueued = mutableListOf<ChatMessage>()
        var onCached: ((String, String) -> Unit)? = null

        override fun enqueue(
            message: ChatMessage,
            priority: ThumbnailDownloadPriority,
            onCached: (messageId: String, localThumbnailPath: String) -> Unit
        ): Boolean {
            enqueued += message
            this.onCached = onCached
            return true
        }
    }

    private fun repository(messageDao: CountingMessageDao): MessageRepository {
        return MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator()
        )
    }

    private fun repository(
        messageDao: CountingMessageDao,
        thumbnailDownloadScheduler: ThumbnailDownloadScheduler
    ): MessageRepository {
        return MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            thumbnailDownloadScheduler = thumbnailDownloadScheduler
        )
    }

    private fun repository(
        messageDao: MessageDao,
        preloadDispatcher: CoroutineDispatcher
    ): MessageRepository {
        return MessageRepository(
            messageDao = messageDao,
            conversationDao = InMemoryConversationDao(),
            pendingMessageDao = InMemoryPendingMessageDao(),
            connection = FakeConnection(),
            messageIdGenerator = MessageIdGenerator(),
            preloadDispatcher = preloadDispatcher
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
            serverSeq = createdAt,
            content = id,
            status = MessageStatus.SENT,
            direction = MessageDirection.OUTGOING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    private fun insertMessages(
        messageDao: CountingMessageDao,
        count: Int,
        conversationId: String = "single:u_a:u_b"
    ) {
        (1..count).forEach { index ->
            messageDao.insertOrIgnore(
                message(
                    id = "m$index",
                    conversationId = conversationId,
                    createdAt = index.toLong()
                )
            )
        }
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
    fun preloadInitialPage_queriesTwentyOnceAndReturnsCachedInitialPage() = runBlocking {
        val messageDao = CountingMessageDao()
        val repository = repository(messageDao)
        insertMessages(messageDao, count = 25)

        val first = repository.preloadInitialPage("single:u_a:u_b")
        val second = repository.preloadInitialPage("single:u_a:u_b")
        val cached = repository.getCachedInitialPage("single:u_a:u_b")

        assertEquals((25 downTo 6).map { "m$it" }, first.map { it.messageId })
        assertEquals(first, second)
        assertEquals(first, cached)
        assertEquals(1, messageDao.queryPageCount)
        assertEquals(listOf(20), messageDao.queryPageLimits)
    }

    @Test
    fun preloadInitialPage_timeoutReturnsEmptyListAndBackgroundQueryStillPopulatesCache() = runTest {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val countingDao = CountingMessageDao()
        insertMessages(countingDao, count = 1)
        val messageDao = BlockingQueryMessageDao(countingDao, queryStarted, releaseQuery)
        val executor = Executors.newSingleThreadExecutor()
        val preloadDispatcher = executor.asCoroutineDispatcher()
        try {
            val repository = repository(messageDao, preloadDispatcher)

            val deferred = async { repository.preloadInitialPage("single:u_a:u_b") }
            testScheduler.runCurrent()
            assertTrue("DAO query should start before the timeout fires", queryStarted.await(5, TimeUnit.SECONDS))

            // Fire the 100ms preload timeout while the DAO query is still blocked. The
            // in-flight query is not cancellable, so withTimeoutOrNull only resumes once
            // it finishes.
            testScheduler.advanceTimeBy(101L)

            releaseQuery.countDown()
            // Single-threaded FIFO barrier: once this marker task runs, the preload block
            // has finished on the executor thread, including the cache write.
            executor.submit(Runnable { }).get(5, TimeUnit.SECONDS)
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList<ChatMessage>(), deferred.await())
            val cached = repository.getCachedInitialPage("single:u_a:u_b")
            assertNotNull(cached)
            assertEquals(listOf("m1"), cached!!.map { it.messageId })
            assertEquals(listOf("m1"), repository.preloadInitialPage("single:u_a:u_b").map { it.messageId })
            assertEquals(1, countingDao.queryPageCount)
        } finally {
            preloadDispatcher.close()
        }
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
            ImEnvelope.newBuilder()
                .setProtocolVersion(2)
                .setReceiveMessage(
                    ReceiveMessage.newBuilder().setMessage(
                        ChatMessagePayload.newBuilder()
                            .setMessageId("m2")
                            .setSenderId("u_a")
                            .setReceiverId("u_b")
                            .setServerSeq(2L)
                            .setContent("fresh")
                            .setClientTime(2L)
                            .setConversationType(ProtoConversationType.CONVERSATION_TYPE_SINGLE)
                            .setMessageType(ProtoMessageType.MESSAGE_TYPE_TEXT)
                    )
                )
                .build()
        )

        assertNull(repository.getCachedInitialPage("single:u_a:u_b"))
    }

    @Test
    fun incomingImageThumbnailCallbackUpdatesLocalPathForBackgroundConversation() {
        val messageDao = CountingMessageDao()
        val scheduler = CapturingThumbnailScheduler()
        val repository = repository(messageDao, scheduler)

        repository.handlePacket(imageEnvelope(messageId = "img1", senderId = "u_a", receiverId = "u_b"))
        scheduler.onCached?.invoke("img1", "/cache/img1.jpg")

        assertEquals("/cache/img1.jpg", messageDao.findByMessageId("img1")?.localThumbnailPath)
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

    private fun imageEnvelope(messageId: String, senderId: String, receiverId: String): ImEnvelope {
        return ImEnvelope.newBuilder()
            .setProtocolVersion(2)
            .setReceiveMessage(
                ReceiveMessage.newBuilder().setMessage(
                    ChatMessagePayload.newBuilder()
                        .setMessageId(messageId)
                        .setSenderId(senderId)
                        .setReceiverId(receiverId)
                        .setServerSeq(1L)
                        .setContent("[图片]")
                        .setClientTime(1L)
                        .setConversationType(ProtoConversationType.CONVERSATION_TYPE_SINGLE)
                        .setMessageType(ProtoMessageType.MESSAGE_TYPE_IMAGE)
                        .setImage(
                            ImagePayload.newBuilder()
                                .setImageUrl("https://example.test/$messageId-original.jpg")
                                .setThumbnailUrl("https://example.test/$messageId-thumb.jpg")
                                .setWidth(640)
                                .setHeight(480)
                                .setMimeType("image/jpeg")
                                .setSizeBytes(1234L)
                        )
                )
            )
            .build()
    }
}
