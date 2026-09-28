package com.aurora.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.data.FolderContent
import com.aurora.music.desktop.DesktopContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderUiState(
    val loading: Boolean = true,
    val content: FolderContent? = null,
)

class FolderViewModel(private val container: DesktopContainer) : ViewModel() {
    private val _state = MutableStateFlow(FolderUiState())
    val state: StateFlow<FolderUiState> = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(folderId: String) {
        if (folderId == loadedId) return
        loadedId = folderId
        viewModelScope.launch {
            _state.update { it.copy(loading = true, content = null) }
            val content = container.repository.browseFolder(folderId)
            _state.update { it.copy(loading = false, content = content) }
        }
    }
}
