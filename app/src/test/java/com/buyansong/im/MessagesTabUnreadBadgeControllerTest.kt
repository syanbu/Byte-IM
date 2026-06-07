package com.buyansong.im

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessagesTabUnreadBadgeControllerTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshesBadgeCountWhenConversationUpdatesEmit() = runTest {
        val source = FakeBadgeSource()
        val controller = MessagesTabUnreadBadgeController(
            repository = source,
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler)
        )
        controller.start()
        advanceUntilIdle()

        source.unreadCount = 1
        source.emitConversationUpdate()
        advanceUntilIdle()

        assertEquals(1, controller.unreadCount.value)
    }

    private class FakeBadgeSource : MessagesTabUnreadBadgeSource {
        var unreadCount: Int = 0
        private val mutableConversationUpdates = MutableSharedFlow<Unit>()
        override val conversationUpdates: SharedFlow<Unit> = mutableConversationUpdates

        override fun totalUnreadCount(): Int = unreadCount

        suspend fun emitConversationUpdate() {
            mutableConversationUpdates.emit(Unit)
        }
    }
}
