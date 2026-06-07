package com.buyansong.im.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDaoContractTest {
    @Test
    fun insertOrIgnoreDeduplicatesByMessageId() {
        val dao = InMemoryMessageDao()
        val message = sampleMessage(messageId = "m1", createdAt = 100)

        assertTrue(dao.insertOrIgnore(message))
        assertFalse(dao.insertOrIgnore(message.copy(content = "duplicate")))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)
        assertEquals(1, page.size)
        assertEquals("hello", page.single().content)
    }

    @Test
    fun queryPageReturnsMessagesBeforeCursorNewestFirst() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "m1", createdAt = 100))
        dao.insertOrIgnore(sampleMessage(messageId = "m2", createdAt = 200))
        dao.insertOrIgnore(sampleMessage(messageId = "m3", createdAt = 300))

        val page = dao.queryPage("single:u1:u2", beforeTime = 300, limit = 2)

        assertEquals(listOf("m2", "m1"), page.map { it.messageId })
    }

    @Test
    fun queryPageOrdersServerSequencedMessagesBeforeLocalTime() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "seq-2", createdAt = 1_000, serverSeq = 2))
        dao.insertOrIgnore(sampleMessage(messageId = "seq-1", createdAt = 2_000, serverSeq = 1))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("seq-2", "seq-1"), page.map { it.messageId })
    }

    @Test
    fun queryPageKeepsSendingMessagesVisibleWithoutBreakingServerSeqOrder() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "seq-1", createdAt = 4_000, serverSeq = 1))
        dao.insertOrIgnore(sampleMessage(messageId = "sending", createdAt = 5_000, serverSeq = null))
        dao.insertOrIgnore(sampleMessage(messageId = "seq-2", createdAt = 1_000, serverSeq = 2))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("sending", "seq-2", "seq-1"), page.map { it.messageId })
    }

    @Test
    fun queryPagePlacesFailedLocalMessagesByCreateTimeAmongServerSequencedMessages() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "seq-3", createdAt = 3_000, serverSeq = 3, status = MessageStatus.SENT))
        dao.insertOrIgnore(sampleMessage(messageId = "failed-2500", createdAt = 2_500, serverSeq = null, status = MessageStatus.UPLOAD_FAILED))
        dao.insertOrIgnore(sampleMessage(messageId = "seq-2", createdAt = 2_000, serverSeq = 2, status = MessageStatus.SENT))
        dao.insertOrIgnore(sampleMessage(messageId = "failed-1500", createdAt = 1_500, serverSeq = null, status = MessageStatus.FAILED))
        dao.insertOrIgnore(sampleMessage(messageId = "seq-1", createdAt = 1_000, serverSeq = 1, status = MessageStatus.SENT))
        dao.insertOrIgnore(sampleMessage(messageId = "sending", createdAt = 500, serverSeq = null, status = MessageStatus.SENDING))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(
            listOf("sending", "seq-3", "failed-2500", "seq-2", "failed-1500", "seq-1"),
            page.map { it.messageId }
        )
    }

    @Test
    fun queryPageInsertsFailedLocalMessageByCreateTimeWhenServerSeqAndTimeDisagree() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "server-newest", createdAt = 1_000, serverSeq = 3, status = MessageStatus.SENT))
        dao.insertOrIgnore(sampleMessage(messageId = "failed-local", createdAt = 2_500, serverSeq = null, status = MessageStatus.UPLOAD_FAILED))
        dao.insertOrIgnore(sampleMessage(messageId = "server-middle", createdAt = 3_000, serverSeq = 2, status = MessageStatus.SENT))
        dao.insertOrIgnore(sampleMessage(messageId = "server-oldest", createdAt = 4_000, serverSeq = 1, status = MessageStatus.SENT))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(
            listOf("failed-local", "server-newest", "server-middle", "server-oldest"),
            page.map { it.messageId }
        )
    }

    @Test
    fun queryPageDoesNotKeepOlderFailedUploadAboveNewerSentMessage() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "failed-upload",
                createdAt = 1_000,
                serverSeq = null,
                status = MessageStatus.UPLOAD_FAILED,
                type = MessageType.IMAGE
            )
        )
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "newer-sent",
                createdAt = 2_000,
                serverSeq = 8,
                status = MessageStatus.SENT,
                type = MessageType.IMAGE
            )
        )

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("newer-sent", "failed-upload"), page.map { it.messageId })
    }

    @Test
    fun queryPageOrdersNewerFailedUploadByCreateTimeAgainstOlderSentMessage() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "older-sent",
                createdAt = 1_000,
                serverSeq = 8,
                status = MessageStatus.SENT,
                type = MessageType.IMAGE
            )
        )
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "newer-failed-upload",
                createdAt = 2_000,
                serverSeq = null,
                status = MessageStatus.UPLOAD_FAILED,
                type = MessageType.IMAGE
            )
        )

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("newer-failed-upload", "older-sent"), page.map { it.messageId })
    }

    @Test
    fun ackUpdatesMessageStatusAndServerSeq() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "m1", createdAt = 100))

        assertTrue(dao.markAcked(messageId = "m1", serverSeq = 42L, updatedAt = 150L))

        val message = dao.queryPage("single:u1:u2", beforeTime = null, limit = 1).single()
        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(42L, message.serverSeq)
        assertEquals(150L, message.updatedAt)
    }

    @Test
    fun insertAndQueryImageMessagePersistsImageFields() {
        val dao = InMemoryMessageDao()
        val message = sampleMessage(
            messageId = "img-1",
            createdAt = 100,
            content = "[图片]",
            status = MessageStatus.UPLOADING,
            type = MessageType.IMAGE,
            imageUrl = null,
            thumbnailUrl = "https://oss.example.com/thumb.jpg",
            imageWidth = 1080,
            imageHeight = 720,
            mimeType = "image/jpeg",
            fileSizeBytes = 123_456L,
            localOriginalPath = "cache/original.jpg",
            localThumbnailPath = "cache/thumb.jpg"
        )

        assertTrue(dao.insertOrIgnore(message))

        val loaded = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20).single()
        assertEquals(MessageType.IMAGE, loaded.type)
        assertNull(loaded.imageUrl)
        assertEquals("https://oss.example.com/thumb.jpg", loaded.thumbnailUrl)
        assertEquals(1080, loaded.imageWidth)
        assertEquals(720, loaded.imageHeight)
        assertEquals("image/jpeg", loaded.mimeType)
        assertEquals(123_456L, loaded.fileSizeBytes)
        assertEquals("cache/original.jpg", loaded.localOriginalPath)
        assertEquals("cache/thumb.jpg", loaded.localThumbnailPath)
    }

    @Test
    fun updateImageUploadResultTransitionsToSending() {
        val dao = InMemoryMessageDao()
        val message = sampleMessage(
            messageId = "img-2",
            createdAt = 100,
            content = "[图片]",
            status = MessageStatus.UPLOADING,
            type = MessageType.IMAGE,
            localOriginalPath = "cache/original.jpg",
            localThumbnailPath = "cache/thumb.jpg"
        )
        dao.insertOrIgnore(message)

        assertTrue(
            dao.updateImageUploadResult(
                messageId = "img-2",
                imageUrl = "https://oss.example.com/origin.jpg",
                thumbnailUrl = "https://oss.example.com/thumb.jpg",
                imageWidth = 1440,
                imageHeight = 960,
                mimeType = "image/jpeg",
                fileSizeBytes = 345_678L,
                status = MessageStatus.SENDING,
                updatedAt = 200L
            )
        )

        val updated = dao.findByMessageId("img-2")
        assertNotNull(updated)
        assertEquals(MessageStatus.SENDING, updated?.status)
        assertEquals("https://oss.example.com/origin.jpg", updated?.imageUrl)
        assertEquals("https://oss.example.com/thumb.jpg", updated?.thumbnailUrl)
        assertEquals(345_678L, updated?.fileSizeBytes)
    }

    @Test
    fun markRecalledKeepsOriginalRowAndRecallMetadata() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "recall-me", createdAt = 100, content = "secret"))

        assertTrue(dao.markRecalled(messageId = "recall-me", recalledBy = "u1", recalledAt = 200L))

        val recalled = dao.findByMessageId("recall-me")
        assertEquals("secret", recalled?.content)
        assertEquals(true, recalled?.isRecalled)
        assertEquals(200L, recalled?.recalledAt)
        assertEquals("u1", recalled?.recalledBy)
        assertEquals(listOf("recall-me"), dao.queryPage("single:u1:u2", beforeTime = null, limit = 20).map { it.messageId })
    }

    @Test
    fun deleteConversationMessagesRemovesOnlyTargetConversationHistory() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "target-1", createdAt = 100))
        dao.insertOrIgnore(sampleMessage(messageId = "target-2", createdAt = 200))
        dao.insertOrIgnore(sampleMessage(messageId = "other-1", createdAt = 300).copy(conversationId = "single:u1:u3"))

        assertEquals(2, dao.deleteByConversationId("single:u1:u2"))

        assertEquals(emptyList<String>(), dao.queryPage("single:u1:u2", beforeTime = null, limit = 20).map { it.messageId })
        assertEquals(listOf("other-1"), dao.queryPage("single:u1:u3", beforeTime = null, limit = 20).map { it.messageId })
    }

    @Test
    fun maxIncomingServerSeqIgnoresOutgoingAndLocalMessages() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(sampleMessage(messageId = "incoming-7", createdAt = 100, serverSeq = 7, direction = MessageDirection.INCOMING))
        dao.insertOrIgnore(sampleMessage(messageId = "incoming-local", createdAt = 200, serverSeq = null, direction = MessageDirection.INCOMING))
        dao.insertOrIgnore(sampleMessage(messageId = "outgoing-9", createdAt = 300, serverSeq = 9, direction = MessageDirection.OUTGOING))

        assertEquals(7L, dao.maxIncomingServerSeq("single:u1:u2"))
    }

    @Test
    fun queryIncomingImagesMissingLocalThumbnailReturnsOnlyUncachedIncomingImages() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "incoming-missing",
                createdAt = 300,
                content = "[图片]",
                status = MessageStatus.RECEIVED,
                type = MessageType.IMAGE,
                thumbnailUrl = "https://oss.example.com/thumb.jpg",
                direction = MessageDirection.INCOMING,
                localThumbnailPath = null
            )
        )
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "incoming-cached",
                createdAt = 200,
                content = "[图片]",
                status = MessageStatus.RECEIVED,
                type = MessageType.IMAGE,
                direction = MessageDirection.INCOMING,
                localThumbnailPath = "cache/thumb.jpg"
            )
        )
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "outgoing-missing",
                createdAt = 100,
                content = "[图片]",
                status = MessageStatus.SENT,
                type = MessageType.IMAGE,
                direction = MessageDirection.OUTGOING,
                localThumbnailPath = null
            )
        )

        val missing = dao.queryIncomingImagesMissingLocalThumbnail("single:u1:u2", limit = 20)

        assertEquals(listOf("incoming-missing"), missing.map { it.messageId })
    }

    @Test
    fun queryRecentImagesWithLocalThumbnailReturnsOnlyCachedImagesNewestFirst() {
        val dao = InMemoryMessageDao()
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "older-cached",
                createdAt = 100,
                content = "[图片]",
                type = MessageType.IMAGE,
                localThumbnailPath = "cache/older.jpg"
            )
        )
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "newer-cached",
                createdAt = 300,
                content = "[图片]",
                type = MessageType.IMAGE,
                localThumbnailPath = "cache/newer.jpg"
            )
        )
        dao.insertOrIgnore(
            sampleMessage(
                messageId = "newer-uncached",
                createdAt = 400,
                content = "[图片]",
                type = MessageType.IMAGE,
                localThumbnailPath = null
            )
        )
        dao.insertOrIgnore(sampleMessage(messageId = "text", createdAt = 500))

        val images = dao.queryRecentImagesWithLocalThumbnail("single:u1:u2", limit = 20)

        assertEquals(listOf("newer-cached", "older-cached"), images.map { it.messageId })
        assertEquals(listOf("cache/newer.jpg", "cache/older.jpg"), images.map { it.localThumbnailPath })
    }

    private fun sampleMessage(
        messageId: String,
        createdAt: Long,
        serverSeq: Long? = null,
        content: String = "hello",
        status: MessageStatus = MessageStatus.SENDING,
        type: MessageType = MessageType.TEXT,
        imageUrl: String? = null,
        thumbnailUrl: String? = null,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
        mimeType: String? = null,
        fileSizeBytes: Long? = null,
        localOriginalPath: String? = null,
        localThumbnailPath: String? = null,
        direction: MessageDirection = MessageDirection.OUTGOING
    ): ChatMessage {
        return ChatMessage(
            messageId = messageId,
            conversationId = "single:u1:u2",
            senderId = "u1",
            receiverId = "u2",
            clientSeq = createdAt,
            serverSeq = serverSeq,
            content = content,
            status = status,
            direction = direction,
            createdAt = createdAt,
            updatedAt = createdAt,
            type = type,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes,
            localOriginalPath = localOriginalPath,
            localThumbnailPath = localThumbnailPath
        )
    }
}
