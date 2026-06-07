package com.buyansong.im.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class MeBackPolicyTest {
    @Test
    fun topLevelMeBackMovesTaskToBack() {
        assertEquals(
            MeBackAction.MoveTaskToBack,
            MeBackPolicy.action(showProfileDetail = false, showNameEditor = false)
        )
    }

    @Test
    fun profileDetailBackReturnsToMeHome() {
        assertEquals(
            MeBackAction.CloseProfileDetail,
            MeBackPolicy.action(showProfileDetail = true, showNameEditor = false)
        )
    }

    @Test
    fun nameEditorBackReturnsToProfileDetail() {
        assertEquals(
            MeBackAction.CloseNameEditor,
            MeBackPolicy.action(showProfileDetail = true, showNameEditor = true)
        )
    }
}
