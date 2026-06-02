package com.codex.im.profile

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MeUiState(
    val profile: UserProfile? = null,
    val isLoading: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val draftNickname: String = "",
    val errorMessage: String? = null
)

class MeViewModel(
    private val session: AuthSession,
    private val profileRepository: ProfileRepository,
    private val avatarUploadApi: AvatarUploadApi = DisabledAvatarUploadApi,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(MeUiState())
    val state: StateFlow<MeUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) {
            return
        }
        mutableState.value = mutableState.value.copy(
            profile = profileRepository.bootstrapSession(session),
            isLoading = true,
            errorMessage = null
        )
        job = scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            val profile = if (validSession == null) {
                profileRepository.bootstrapSession(session)
            } else {
                profileRepository.currentUserProfile(validSession)
            }
            mutableState.value = mutableState.value.copy(
                profile = profile,
                isLoading = false,
                errorMessage = null
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun startEditing() {
        val profile = mutableState.value.profile ?: profileRepository.bootstrapSession(session)
        mutableState.value = mutableState.value.copy(
            profile = profile,
            isEditing = true,
            draftNickname = profile.nickname,
            errorMessage = null
        )
        selectedAvatarBytes = null
    }

    fun updateDraftNickname(value: String) {
        mutableState.value = mutableState.value.copy(draftNickname = value)
    }

    fun setSelectedAvatarBytes(bytes: ByteArray) {
        selectedAvatarBytes = bytes
        mutableState.value = mutableState.value.copy(errorMessage = null)
    }

    fun cancelEditing() {
        selectedAvatarBytes = null
        mutableState.value = mutableState.value.copy(
            isEditing = false,
            isSaving = false,
            draftNickname = "",
            errorMessage = null
        )
    }

    fun saveProfile() {
        val current = mutableState.value
        val nickname = current.draftNickname.trim()
        if (nickname.isEmpty()) {
            mutableState.value = current.copy(errorMessage = "昵称不能为空")
            return
        }
        saveProfile(nickname)
    }

    fun saveAvatarBytes(bytes: ByteArray) {
        selectedAvatarBytes = bytes
        val profile = mutableState.value.profile ?: profileRepository.bootstrapSession(session)
        mutableState.value = mutableState.value.copy(
            profile = profile,
            isEditing = false,
            draftNickname = "",
            errorMessage = null
        )
        saveProfile(profile.nickname)
    }

    private fun saveProfile(nickname: String) {
        val current = mutableState.value
        mutableState.value = current.copy(isSaving = true, errorMessage = null)
        scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = "登录已过期，请重新登录"
                )
                return@launch
            }
            val upload = uploadSelectedAvatarIfNeeded(validSession)
            if (upload is UploadOutcome.Failure) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = upload.message
                )
                return@launch
            }
            val avatar = upload as UploadOutcome.Success
            val fallbackAvatarUrl = mutableState.value.profile?.avatarUrl
            val updated = profileRepository.updateMe(
                session = validSession,
                nickname = nickname,
                avatarUrl = avatar.publicUrl ?: fallbackAvatarUrl,
                avatarObjectKey = avatar.objectKey
            )
            if (updated == null) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    errorMessage = "更新资料失败"
                )
            } else {
                selectedAvatarBytes = null
                mutableState.value = mutableState.value.copy(
                    profile = updated,
                    isEditing = false,
                    isSaving = false,
                    draftNickname = "",
                    errorMessage = null
                )
            }
        }
    }

    private suspend fun uploadSelectedAvatarIfNeeded(validSession: AuthSession): UploadOutcome {
        val bytes = selectedAvatarBytes ?: return UploadOutcome.Success(publicUrl = null, objectKey = null)
        val target = when (val result = avatarUploadApi.requestUploadTarget(validSession.accessToken, AvatarImageCompressor.JPEG_CONTENT_TYPE)) {
            is AvatarUploadResult.Success -> result.target
            is AvatarUploadResult.Failure -> return UploadOutcome.Failure(result.message)
        }
        return when (val put = avatarUploadApi.upload(target.uploadUrl, AvatarImageCompressor.JPEG_CONTENT_TYPE, bytes)) {
            AvatarPutResult.Success -> UploadOutcome.Success(publicUrl = target.publicUrl, objectKey = target.objectKey)
            is AvatarPutResult.Failure -> UploadOutcome.Failure(put.message)
        }
    }

    private var selectedAvatarBytes: ByteArray? = null

    private sealed class UploadOutcome {
        data class Success(val publicUrl: String?, val objectKey: String?) : UploadOutcome()
        data class Failure(val message: String) : UploadOutcome()
    }
}
