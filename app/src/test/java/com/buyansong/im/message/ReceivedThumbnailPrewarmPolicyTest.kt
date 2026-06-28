package com.buyansong.im.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivedThumbnailPrewarmPolicyTest {

    @Test
    fun shouldPrewarmReturnsTrueForActiveConversationWithinBudget() {
        assertEquals(
            true,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = "single:u_a:u_b",
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 5
            )
        )
    }

    @Test
    fun shouldPrewarmReturnsFalseForBackgroundConversation() {
        assertEquals(
            false,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = "single:u_b:u_c",
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 5
            )
        )
    }

    @Test
    fun shouldPrewarmReturnsFalseWhenNoConversationIsActive() {
        assertEquals(
            false,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = null,
                alreadyPrewarmedInDrain = 0,
                maxPrewarmPerDrain = 5
            )
        )
    }

    @Test
    fun shouldPrewarmReturnsFalseWhenBudgetIsExhausted() {
        assertEquals(
            false,
            ReceivedThumbnailPrewarmPolicy.shouldPrewarm(
                messageConversationId = "single:u_a:u_b",
                activeConversationId = "single:u_a:u_b",
                alreadyPrewarmedInDrain = 5,
                maxPrewarmPerDrain = 5
            )
        )
    }
}
