package com.codex.im.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AlbumImageItem(
    val id: Long,
    val uriString: String,
    val dateTakenMillis: Long,
    val width: Int?,
    val height: Int?,
    val selectionOrder: Int? = null
)

data class AlbumPickerUiState(
    val images: List<AlbumImageItem> = emptyList(),
    val selected: List<AlbumImageItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

interface AlbumImageRepository {
    suspend fun loadImages(limit: Int = AlbumPickerViewModel.DEFAULT_ALBUM_LIMIT): List<AlbumImageItem>
}

class AlbumPickerViewModel(
    private val repository: AlbumImageRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(AlbumPickerUiState())
    val state: StateFlow<AlbumPickerUiState> = mutableState.asStateFlow()
    private var started = false

    fun start() {
        if (started) {
            return
        }
        started = true
        scope.launch(dispatcher) {
            mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
            try {
                val images = repository.loadImages()
                mutableState.value = mutableState.value.copy(
                    images = images,
                    isLoading = false,
                    errorMessage = null
                )
            } catch (error: RuntimeException) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    errorMessage = error.message ?: "相册读取失败"
                )
            }
        }
    }

    fun toggleSelection(image: AlbumImageItem) {
        val selected = mutableState.value.selected
        val existing = selected.indexOfFirst { it.uriString == image.uriString }
        val next = if (existing >= 0) {
            selected.toMutableList().also { it.removeAt(existing) }
        } else if (selected.size >= MAX_SELECTION_COUNT) {
            selected
        } else {
            selected + image
        }
        applySelected(next)
    }

    fun removeSelected(uriString: String) {
        applySelected(mutableState.value.selected.filterNot { it.uriString == uriString })
    }

    private fun applySelected(selected: List<AlbumImageItem>) {
        val compacted = selected.mapIndexed { index, item -> item.copy(selectionOrder = index) }
        val orderByUri = compacted.associateBy { it.uriString }
        mutableState.value = mutableState.value.copy(
            selected = compacted,
            images = mutableState.value.images.map { image ->
                orderByUri[image.uriString] ?: image.copy(selectionOrder = null)
            }
        )
    }

    suspend fun selectedInOrder(): List<AlbumImageItem> {
        return withContext(dispatcher) {
            state.value.selected.sortedBy { it.selectionOrder ?: Int.MAX_VALUE }
        }
    }

    companion object {
        const val MAX_SELECTION_COUNT = 9
        const val DEFAULT_ALBUM_LIMIT = 500
    }
}
