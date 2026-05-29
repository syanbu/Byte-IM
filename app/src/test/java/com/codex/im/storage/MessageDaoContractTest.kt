package com.codex.im.storage

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
        localThumbnailPath: String? = null
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
            direction = MessageDirection.OUTGOING,
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
