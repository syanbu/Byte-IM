package com.codex.im.alert

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageAlertControllerTest {
    @Test
    fun showMakesAlertCurrent() = runTest {
        val controller = controller()
        val alert = alert("a")

        controller.show(alert)
        runCurrent()

        assertEquals(alert, controller.currentAlert.value)
    }

    @Test
    fun alertAutoDismissesAfterFourSeconds() = runTest {
        val controller = controller()

        controller.show(alert("a"))
        advanceTimeBy(3_999L)
        runCurrent()
        assertEquals("a", controller.currentAlert.value?.conversationId)

        advanceTimeBy(1L)
        runCurrent()
        assertNull(controller.currentAlert.value)
    }

    @Test
    fun newAlertReplacesOldAlertAndResetsTimer() = runTest {
        val controller = controller()

        controller.show(alert("a"))
        advanceTimeBy(2_000L)
        controller.show(alert("b"))
        runCurrent()
        assertEquals("b", controller.currentAlert.value?.conversationId)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals("b", controller.currentAlert.value?.conversationId)

        advanceTimeBy(2_000L)
        runCurrent()
        assertNull(controller.currentAlert.value)
    }

    @Test
    fun dismissClearsCurrentAlert() = runTest {
        val controller = controller()
        controller.show(alert("a"))
        runCurrent()

        controller.dismiss()

        assertNull(controller.currentAlert.value)
    }

    @Test
    fun openCurrentDismissesAndCallsCallback() = runTest {
        val controller = controller()
        val opened = mutableListOf<String>()
        controller.show(alert("a"))
        runCurrent()

        controller.openCurrent { opened += it }

        assertEquals(listOf("a"), opened)
        assertNull(controller.currentAlert.value)
    }

    private fun TestScope.controller(): MessageAlertController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MessageAlertController(
            scope = TestScope(dispatcher),
            autoDismissMillis = 4_000L
        )
    }

    private fun alert(conversationId: String): IncomingMessageAlert = IncomingMessageAlert(
        conversationId = conversationId,
        isGroup = false,
        title = "Alice",
        avatarUrl = null,
        preview = "hello",
        rawTimestamp = 1_000L
    )
}
