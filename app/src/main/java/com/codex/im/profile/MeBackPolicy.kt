package com.codex.im.profile

enum class MeBackAction {
    MoveTaskToBack,
    CloseProfileDetail,
    CloseNameEditor
}

object MeBackPolicy {
    fun action(showProfileDetail: Boolean, showNameEditor: Boolean): MeBackAction {
        return when {
            showProfileDetail && showNameEditor -> MeBackAction.CloseNameEditor
            showProfileDetail -> MeBackAction.CloseProfileDetail
            else -> MeBackAction.MoveTaskToBack
        }
    }
}
