package com.sinxn.teledrive.ui.screens.home

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
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
    private val telegramClient: TelegramClient,
    private val securePrefs: SecurePrefs
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
        // 1. Immediately attempt to load from offline cache to skip Loading spinner
        val cachedUserId = securePrefs.getUserId()
        if (cachedUserId != null) {
            userId = cachedUserId
            observeCurrentFolder()
        } else {
            _uiState.value = HomeUiState.Loading
        }

        // 2. Refresh/Fetch data in the background
        viewModelScope.launch {
            try {
                val me = telegramClient.send(TdApi.GetMe()) as TdApi.User
                val fetchedUserId = me.id.toString()
                
                // If it's a new login or previous cache was empty, save it and start observing
                if (fetchedUserId != userId) {
                    userId = fetchedUserId
                    securePrefs.setUserId(userId)
                    observeCurrentFolder()
                }
                
                // Temporary debug folder injection to test UI (Safe to run multiple times due to REPLACE strategy)
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

                // Trigger a background sync of remote data -> Room DB
                try {
                    fileRepository.syncSavedMessages(userId)
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Background sync failed", e)
                }

            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to sync Home screen data", e)
                if (cachedUserId == null) {
                    // Only show full error if we have no cached data to fall back on
                    _uiState.value = HomeUiState.Error(e.message ?: "Unknown error occurred")
                }
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

    fun onFileSelectedForUpload(uri: Uri) {
        // Placeholder for Story 4.2
        Log.d("HomeViewModel", "User selected file for upload: $uri")
    }
}
