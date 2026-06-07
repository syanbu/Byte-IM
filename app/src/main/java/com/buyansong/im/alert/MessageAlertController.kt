package com.buyansong.im.alert

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MessageAlertController(
    private val scope: CoroutineScope,
    private val autoDismissMillis: Long = 4_000L
) {
    private val mutableCurrentAlert = MutableStateFlow<IncomingMessageAlert?>(null)
    val currentAlert: StateFlow<IncomingMessageAlert?> = mutableCurrentAlert.asStateFlow()

    private var autoDismissJob: Job? = null

    fun show(alert: IncomingMessageAlert) {
        autoDismissJob?.cancel()
        mutableCurrentAlert.value = alert
        autoDismissJob = scope.launch {
            delay(autoDismissMillis)
            mutableCurrentAlert.value = null
        }
    }

    fun dismiss() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        mutableCurrentAlert.value = null
    }

    fun openCurrent(onOpenConversation: (String) -> Unit) {
        val alert = mutableCurrentAlert.value ?: return
        dismiss()
        onOpenConversation(alert.conversationId)
    }
}
