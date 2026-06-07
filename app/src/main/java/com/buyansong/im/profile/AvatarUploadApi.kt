package com.buyansong.im.profile

data class AvatarUploadTarget(
    val objectKey: String,
    val uploadUrl: String,
    val publicUrl: String,
    val expiresAt: Long
)

interface AvatarUploadApi {
    suspend fun requestUploadTarget(accessToken: String, contentType: String): AvatarUploadResult

    suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult
}

sealed class AvatarUploadResult {
    data class Success(val target: AvatarUploadTarget) : AvatarUploadResult()
    data class Failure(val message: String) : AvatarUploadResult()
}

sealed class AvatarPutResult {
    data object Success : AvatarPutResult()
    data class Failure(val message: String) : AvatarPutResult()
}

object DisabledAvatarUploadApi : AvatarUploadApi {
    override suspend fun requestUploadTarget(accessToken: String, contentType: String): AvatarUploadResult {
        return AvatarUploadResult.Failure("头像上传未配置")
    }

    override suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult {
        return AvatarPutResult.Failure("头像上传未配置")
    }
}
