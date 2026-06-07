package com.codex.im.group

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.storage.GroupInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JoinedGroupItem(
    val groupId: String,
    val name: String,
    val avatarUrl: String?,
    val memberCount: Int
)

data class JoinedGroupsUiState(
    val items: List<JoinedGroupItem> = emptyList(),
    val isLoading: Boolean = false
)

class JoinedGroupsViewModel(
    private val session: AuthSession,
    private val groupRepository: GroupRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(JoinedGroupsUiState())
    val state: StateFlow<JoinedGroupsUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var started = false

    fun start() {
        if (started) {
            refresh()
            return
        }
        started = true
        refresh()
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        started = false
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch(dispatcher) {
            // (1) 本地缓存优先
            mutableState.value = mutableState.value.copy(
                items = groupRepository.joinedGroups(session.userId).toItems(),
                isLoading = true
            )
            // (2) 异步拉远端
            val validSession = validSessionProvider() ?: run {
                mutableState.value = mutableState.value.copy(isLoading = false)
                return@launch
            }
            groupRepository.syncGroups(validSession.accessToken)
            // (3) 重新读本地，emit 最终结果
            mutableState.value = mutableState.value.copy(
                items = groupRepository.joinedGroups(session.userId).toItems(),
                isLoading = false
            )
        }
    }

    private fun List<GroupInfo>.toItems(): List<JoinedGroupItem> =
        map { JoinedGroupItem(it.groupId, it.name, it.avatarUrl, it.memberCount) }
}
