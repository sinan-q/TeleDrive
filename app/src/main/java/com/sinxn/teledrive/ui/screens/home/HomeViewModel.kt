package com.sinxn.teledrive.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.repository.FileRepository
import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

sealed class HomeUiState {
    data object Loading : HomeUiState()
    data class Success(val files: List<FileEntity>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val telegramClient: TelegramClient
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _isGridView = MutableStateFlow(false)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<FileEntity>>(emptyList())
    val breadcrumbs: StateFlow<List<FileEntity>> = _breadcrumbs.asStateFlow()

    private val currentFolder = MutableStateFlow<FileEntity?>(null)
    private var observeJob: Job? = null
    private var userId: String = ""

    init {
        loadFiles()
    }

    private fun loadFiles() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            try {
                // 1. Get the current user ID to scope our queries
                val me = telegramClient.send(TdApi.GetMe()) as TdApi.User
                userId = me.id.toString()
                
                // Temporary debug folder injection to test UI
                try {
                    fileRepository.saveFile(
                        FileEntity(
                            id = "debug_folder_1",
                            name = "Test Virtual Folder",
                            type = "folder",
                            userId = userId,
                            parentId = null
                        )
                    )
                } catch (e: Exception) { Log.e("HomeViewModel", "Failed to mock folder", e) }

                // 2. Trigger a background sync of remote data -> Room DB
                launch {
                    try {
                        fileRepository.syncSavedMessages(userId)
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Background sync failed", e)
                    }
                }

                // 3. Observe the local database
                observeCurrentFolder()

            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to initialize Home screen", e)
                _uiState.value = HomeUiState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }

    private fun observeCurrentFolder() {
        observeJob?.cancel()
        val currFolderId = currentFolder.value?.id

        observeJob = viewModelScope.launch {
            fileRepository.getFilesByParent(currFolderId, userId)
                .catch { e ->
                    Log.e("HomeViewModel", "Failed to read database flow", e)
                    _uiState.value = HomeUiState.Error(e.message ?: "Database error")
                }
                .collect { files ->
                    _uiState.value = HomeUiState.Success(files)
                }
        }
    }

    fun navigateToFolder(folder: FileEntity) {
        val currentStack = _breadcrumbs.value.toMutableList()
        currentStack.add(folder)
        _breadcrumbs.value = currentStack
        currentFolder.value = folder
        observeCurrentFolder()
    }

    fun navigateUp(): Boolean {
        val currentStack = _breadcrumbs.value.toMutableList()
        if (currentStack.isEmpty()) return false // Already at root

        currentStack.removeLast()
        _breadcrumbs.value = currentStack
        currentFolder.value = currentStack.lastOrNull()
        observeCurrentFolder()
        return true
    }

    fun navigateToBreadcrumb(index: Int) {
        val currentStack = _breadcrumbs.value
        if (index < 0 || index >= currentStack.size) return
        
        // Truncate the stack up to the selected index
        val newStack = currentStack.subList(0, index + 1)
        _breadcrumbs.value = newStack
        currentFolder.value = newStack.lastOrNull()
        observeCurrentFolder()
    }

    fun navigateToRoot() {
        _breadcrumbs.value = emptyList()
        currentFolder.value = null
        observeCurrentFolder()
    }

    fun toggleViewMode() {
        _isGridView.value = !_isGridView.value
    }
}
