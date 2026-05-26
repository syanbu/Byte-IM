package com.codex.im.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidDaoInstrumentedTest {
    private lateinit var database: SQLiteDatabase
    private lateinit var helper: ImDatabaseHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(ImDatabaseHelper.DATABASE_NAME)
        helper = ImDatabaseHelper(context)
        database = helper.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
        helper.close()
        ApplicationProvider.getApplicationContext<Context>()
            .deleteDatabase(ImDatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun androidMessageDaoDeduplicatesAndOrdersPagesLikeContractDao() {
        val dao = AndroidMessageDao(database)
        val older = message(messageId = "m0", createdAt = 900L)
        val firstSameTime = message(messageId = "m1", createdAt = 1_000L)
        val secondSameTime = message(messageId = "m2", createdAt = 1_000L)

        assertTrue(dao.insertOrIgnore(firstSameTime))
        assertTrue(dao.insertOrIgnore(older))
        assertTrue(dao.insertOrIgnore(secondSameTime))
        assertFalse(dao.insertOrIgnore(firstSameTime.copy(content = "duplicate")))

        val page = dao.queryPage("single:u1:u2", beforeTime = null, limit = 20)

        assertEquals(listOf("m2", "m1", "m0"), page.map { it.messageId })
        assertEquals("hello", page.first { it.messageId == "m1" }.content)
    }

    @Test
    fun androidConversationDaoClearsUnreadAndUsesStableLatestOrdering() {
        val dao = AndroidConversationDao(database)

        dao.upsertFromMessage(message("c1-100", "c1", "u2", "old", createdAt = 100L), incrementUnread = true)
        dao.upsertFromMessage(message("c2-300", "c2", "u3", "new", createdAt = 300L), incrementUnread = true)
        dao.upsertFromMessage(message("c1-300", "c1", "u2", "latest", createdAt = 300L), incrementUnread = false)
        dao.clearUnread("c1")

        val conversations = dao.listConversations(limit = 20)

        assertEquals(listOf("c1", "c2"), conversations.map { it.conversationId })
        assertEquals("latest", conversations.first().lastMessagePreview)
        assertEquals(0, conversations.first { it.conversationId == "c1" }.unreadCount)
        assertEquals(1, conversations.first { it.conversationId == "c2" }.unreadCount)
    }

    @Test
    fun androidPendingMessageDaoUpsertsDeletesAndQueriesDueMessages() {
        val dao = AndroidPendingMessageDao(database)

        dao.upsert(pending("m2", nextRetryAt = 2_000L))
        dao.upsert(pending("m1", nextRetryAt = 1_000L))
        dao.upsert(pending("m3", nextRetryAt = 3_000L))

        assertEquals(listOf("m1", "m2"), dao.dueMessages(now = 2_000L, limit = 10).map { it.messageId })
        assertTrue(dao.delete("m1"))
        assertFalse(dao.delete("missing"))
        assertEquals(listOf("m2"), dao.dueMessages(now = 2_000L, limit = 10).map { it.messageId })
    }

    @Test
    fun androidTransactionRunnerRollsBackMessageAndPendingWritesTogether() {
        val messageDao = AndroidMessageDao(database)
        val pendingDao = AndroidPendingMessageDao(database)
        val transactionRunner = AndroidTransactionRunner(database)

        try {
            transactionRunner.runInTransaction {
                messageDao.insertOrIgnore(message("atomic-message", createdAt = 1_000L))
                pendingDao.upsert(pending("atomic-message", nextRetryAt = 6_000L))
                error("force rollback")
            }
        } catch (expected: IllegalStateException) {
            // Expected; the assertions below verify the SQLite transaction rolled back.
        }

        assertTrue(messageDao.queryPage("single:u1:u2", beforeTime = null, limit = 20).isEmpty())
        assertTrue(pendingDao.dueMessages(now = 6_000L, limit = 20).isEmpty())
    }

    private fun message(
        messageId: String,
        conversationId: String = "single:u1:u2",
        peerId: String = "u2",
        content: String = "hello",
        createdAt: Long
    ): ChatMessage {
        return ChatMessage(
            messageId = messageId,
            conversationId = conversationId,
            senderId = peerId,
            receiverId = "u1",
            clientSeq = createdAt,
            serverSeq = null,
            content = content,
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    private fun pending(messageId: String, nextRetryAt: Long): PendingMessage {
        return PendingMessage(
            messageId = messageId,
            packetCmd = 10,
            packetBody = "{}",
            retryCount = 0,
            nextRetryAt = nextRetryAt,
            createdAt = nextRetryAt
        )
    }
}
