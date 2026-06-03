package com.codex.im.profile

enum class MeBackAction {
    MoveTaskToBack,
    CloseProfileDetail,
    CloseNameEditor,
    CloseGenderEditor,
    CloseSignatureEditor
}

object MeBackPolicy {
    fun action(
        showProfileDetail: Boolean,
        showNameEditor: Boolean,
        showGenderEditor: Boolean = false,
        showSignatureEditor: Boolean = false
    ): MeBackAction {
        return when {
            showNameEditor -> MeBackAction.CloseNameEditor
            showGenderEditor -> MeBackAction.CloseGenderEditor
            showSignatureEditor -> MeBackAction.CloseSignatureEditor
            showProfileDetail -> MeBackAction.CloseProfileDetail
            else -> MeBackAction.MoveTaskToBack
        }
    }
}
