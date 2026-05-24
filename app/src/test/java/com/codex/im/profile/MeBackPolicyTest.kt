package com.codex.im.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class MeBackPolicyTest {
    @Test
    fun topLevelMeBackExitsApp() {
        assertEquals(
            MeBackAction.ExitApp,
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
