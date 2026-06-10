package com.buyansong.im.group

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.auth.ValidSessionProvider
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.storage.GroupInfo
import com.buyansong.im.storage.GroupMember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GroupInfoUiState(
    val group: GroupInfo? = null,
    val members: List<GroupMember> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val showRenameDialog: Boolean = false,
    val draftGroupName: String = ""
)

class GroupInfoViewModel(
    val groupId: String,
    private val session: AuthSession,
    private val groupRepository: GroupRepository,
    private val profileRepository: ProfileRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(GroupInfoUiState())
    val state: StateFlow<GroupInfoUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var renameJob: Job? = null
    private var started = false

    fun start() {
        if (started) {
            return
        }
        started = true
        load()
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        renameJob?.cancel()
        renameJob = null
        started = false
    }

    fun load() {
        profileRepository.bootstrapSession(session)
        val cachedGroup = groupRepository.localGroup(groupId)
        val cachedMembers = backfillMembersFromLocal(groupRepository.localMembers(groupId))
        mutableState.value = mutableState.value.copy(
            group = cachedGroup ?: mutableState.value.group,
            members = cachedMembers,
            errorMessage = null
        )
        if (refreshJob?.isActive == true) {
            return
        }
        refreshJob = scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    errorMessage = if (mutableState.value.group == null && mutableState.value.members.isEmpty()) {
                        GroupInfoDisplayPolicy.sessionExpiredMessage
                    } else {
                        null
                    }
                )
                return@launch
            }

            mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
            val remoteMembers = groupRepository.syncMembers(validSession.accessToken, groupId)
            val refreshedGroup = groupRepository.localGroup(groupId)
            val backfilled = backfillMembersRemote(
                members = remoteMembers,
                accessToken = validSession.accessToken.takeIf { it.isNotBlank() }
            )
            mutableState.value = mutableState.value.copy(
                group = refreshedGroup ?: mutableState.value.group,
                members = backfilled,
                isLoading = false,
                errorMessage = if (refreshedGroup == null && backfilled.isEmpty()) {
                    GroupInfoDisplayPolicy.loadFailedMessage
                } else {
                    null
                }
            )
        }
    }

    private fun backfillMembersFromLocal(members: List<GroupMember>): List<GroupMember> {
        if (members.isEmpty()) return members
        val profilesById = members
            .map { it.userId }
            .mapNotNull(profileRepository::localProfile)
            .associateBy { it.userId }
        return members.map { member ->
            val profile = profilesById[member.userId]
            member.copy(
                displayName = profile?.nickname?.takeIf { it.isNotBlank() } ?: member.displayName,
                avatarUrl = profile?.avatarUrl ?: member.avatarUrl
            )
        }
    }

    private suspend fun backfillMembersRemote(
        members: List<GroupMember>,
        accessToken: String?
    ): List<GroupMember> {
        if (members.isEmpty()) {
            return members
        }
        val memberIds = members.map { it.userId }
        if (!accessToken.isNullOrBlank()) {
            val memberVersions = members
                .filter { it.profileVersion > 0L }
                .associate { it.userId to it.profileVersion }
            profileRepository.ensureProfiles(accessToken, memberIds + session.userId, memberVersions)
        }
        return backfillMembersFromLocal(members)
    }

    fun retry() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
        load()
    }

    fun startRename() {
        val currentName = mutableState.value.group?.name.orEmpty()
        mutableState.value = mutableState.value.copy(
            showRenameDialog = true,
            draftGroupName = currentName,
            errorMessage = null
        )
    }

    fun cancelRename() {
        mutableState.value = mutableState.value.copy(
            showRenameDialog = false,
            draftGroupName = "",
            errorMessage = null
        )
    }

    fun updateDraftGroupName(name: String) {
        mutableState.value = mutableState.value.copy(draftGroupName = name)
    }

    fun confirmRename() {
        if (renameJob?.isActive == true) {
            return
        }
        val trimmed = mutableState.value.draftGroupName.trim()
        if (trimmed.isEmpty()) {
            mutableState.value = mutableState.value.copy(
                errorMessage = GroupInfoDisplayPolicy.errorEmptyGroupName
            )
            return
        }
        renameJob = scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = GroupInfoDisplayPolicy.sessionExpiredMessage
                )
                return@launch
            }
            mutableState.value = mutableState.value.copy(isSaving = true, errorMessage = null)
            when (val result = groupRepository.renameGroup(validSession.accessToken, groupId, trimmed)) {
                is GroupResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        group = result.group,
                        isSaving = false,
                        showRenameDialog = false,
                        draftGroupName = "",
                        errorMessage = null
                    )
                }
                is GroupResult.Failure -> {
                    mutableState.value = mutableState.value.copy(
                        isSaving = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }
}
