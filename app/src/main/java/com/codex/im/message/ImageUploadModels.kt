package com.codex.im.message

import com.codex.im.profile.AvatarPutResult
import com.codex.im.profile.AvatarUploadTarget

data class ImageUploadTargets(
    val messageId: String,
    val thumbnail: AvatarUploadTarget,
    val original: AvatarUploadTarget,
    val expiresAt: Long
)

interface ImageUploadApi {
    suspend fun requestUploadTargets(
        accessToken: String,
        messageId: String,
        contentType: String
    ): ImageUploadTargetsResult

    suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult
}

sealed class ImageUploadTargetsResult {
    data class Success(val targets: ImageUploadTargets) : ImageUploadTargetsResult()
    data class Failure(val message: String) : ImageUploadTargetsResult()
}

data class SelectedChatImage(
    val originalBytes: ByteArray,
    val thumbnailBytes: ByteArray,
    val localOriginalPath: String,
    val localThumbnailPath: String,
    val width: Int,
    val height: Int,
    val mimeType: String
)

object DisabledImageUploadApi : ImageUploadApi {
    override suspend fun requestUploadTargets(
        accessToken: String,
        messageId: String,
        contentType: String
    ): ImageUploadTargetsResult {
        return ImageUploadTargetsResult.Failure("Image upload is not configured")
    }

    override suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult {
        return AvatarPutResult.Failure("Image upload is not configured")
    }
}
