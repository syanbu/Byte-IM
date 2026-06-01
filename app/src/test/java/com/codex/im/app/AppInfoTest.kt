package com.codex.im.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {
    @Test
    fun exposesAppInfo() {
        assertEquals("ByteIM", AppInfo.name)
        assertEquals("0.1.0", AppInfo.versionName)
        assertTrue(AppInfo.completedMilestones.contains("phase-0-project-skeleton"))
    }
}
