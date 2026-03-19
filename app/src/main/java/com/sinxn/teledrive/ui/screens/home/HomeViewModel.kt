package com.sinxn.teledrive.ui.screens.home

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.local.entity.FileEntity
import com.sinxn.teledrive.data.local.entity.UserEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.repository.DownloadEvent
import com.sinxn.teledrive.data.repository.FileRepository
import com.sinxn.teledrive.data.repository.UserRepository
import com.sinxn.teledrive.data.telegram.AuthManager
import com.sinxn.teledrive.data.telegram.AuthState
import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import android.app.Application
import com.sinxn.teledrive.utils.FileUtils

sealed class HomeUiState {
    data object Loading : HomeUiState()
    data class Success(val files: List<FileEntity>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val userRepository: UserRepository,
    private val telegramClient: TelegramClient,
    private val securePrefs: SecurePrefs,
    private val authManager: AuthManager,
    private val application: Application
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

    fun onFileSelectedForUpload(uri: Uri) {
        viewModelScope.launch {
            try {
                // Read from prefs if userId is not populated yet
                val activeUserId = userId.takeIf { it.isNotBlank() } ?: securePrefs.getUserId()
                if (activeUserId.isNullOrBlank()) {
                    _snackbarEvents.emit("User ID not loaded. Please try again.")
                    return@launch
                }

                val tempFile = withContext(Dispatchers.IO) {
                    FileUtils.resolveUriToTempFile(application, uri)
                }
                
                if (tempFile != null) {
                    // Start upload; the new FileRepository update listener will catch it when TDLib finishes
                    val currentParentId = currentFolder.value?.id
                    fileRepository.uploadFile(tempFile.absolutePath, activeUserId, currentParentId)
                    _snackbarEvents.emit("Uploading \"${tempFile.name}\"...")
                } else {
                    _snackbarEvents.emit("Could not read the selected file")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error processing file upload", e)
                _snackbarEvents.emit("Upload failed")
            }
        }
    }

    /** One-shot events for Snackbar messages */
    private val _snackbarEvents = MutableSharedFlow<String>()
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            fileRepository.downloadEvents.collect { event ->
                when (event) {
                    is DownloadEvent.Success -> {
                        _snackbarEvents.emit("Saved \"${event.fileName}\" to Downloads/TeleDrive")
                    }
                    is DownloadEvent.Failure -> {
                        _snackbarEvents.emit("Download failed for \"${event.fileName}\": ${event.reason}")
                    }
                }
            }
        }
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
                // Wait for TDLib to be fully initialized or throw to an input state
                val finalAuthState = authManager.authState.filter { 
                    it is AuthState.Ready || it is AuthState.WaitPhoneNumber || it is AuthState.Error
                }.first()

                if (finalAuthState is AuthState.WaitPhoneNumber) {
                    securePrefs.setHasSession(false)
                    _uiState.value = HomeUiState.Error("Session expired. Please log out and log in again.")
                    return@launch
                }
                if (finalAuthState is AuthState.Error) {
                    _uiState.value = HomeUiState.Error("Authentication error: ${finalAuthState.message}")
                    return@launch
                }

                val me = telegramClient.send(TdApi.GetMe()) as TdApi.User
                val fetchedUserId = me.id.toString()
                
                // Save the authenticated user to the local database to satisfy FileEntity FOREIGN KEY constraints
                userRepository.saveUser(
                    UserEntity(
                        id = fetchedUserId,
                        tgId = fetchedUserId,
                        username = "", // Using empty safely
                        name = "${me.firstName} ${me.lastName}".trim(),
                        phone = me.phoneNumber
                    )
                )
                
                // If it's a new login or previous cache was empty, save it and start observing
                if (fetchedUserId != userId) {
                    userId = fetchedUserId
                    securePrefs.setUserId(userId)
                    observeCurrentFolder()
                }

            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to sync Home screen data", e)
                if (cachedUserId == null) {
                    _uiState.value = HomeUiState.Error(e.message ?: "Unknown error occurred")
                } else {
                    _snackbarEvents.emit("Sync failed. Showing cached files.")
                }
            }
        }
    }

    /** Retry initial load/sync — called from the error state UI */
    fun retrySync() {
        _uiState.value = HomeUiState.Loading
        loadFiles()
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

    fun createFolder(name: String) {
        val folderName = name.trim().takeIf { it.isNotEmpty() } ?: "New Folder"
        val newFolder = FileEntity(
            id = "folder_${UUID.randomUUID()}",
            name = folderName,
            type = "folder",
            userId = userId,
            parentId = currentFolder.value?.id
        )
        
        viewModelScope.launch {
            try {
                fileRepository.saveFile(newFolder)
                _snackbarEvents.emit("Folder \"$folderName\" created")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to create folder", e)
                _snackbarEvents.emit("Failed to create folder")
            }
        }
    }



    fun downloadFile(file: FileEntity) {
        viewModelScope.launch {
            try {
                fileRepository.downloadFile(file)
                _snackbarEvents.emit("Downloading \"${file.name}\"...")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error initiating file download", e)
                _snackbarEvents.emit("Failed to download \"${file.name}\"")
            }
        }
    }

    fun deleteFile(file: FileEntity) {
        viewModelScope.launch {
            try {
                fileRepository.deleteFile(file)
                _snackbarEvents.emit("\"${file.name}\" deleted")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error deleting file", e)
                _snackbarEvents.emit("Failed to delete \"${file.name}\"")
            }
        }
    }

    fun renameFile(id: String, newName: String) {
        viewModelScope.launch {
            try {
                fileRepository.renameFile(id, newName)
                _snackbarEvents.emit("Renamed to \"$newName\"")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error renaming file", e)
                _snackbarEvents.emit("Failed to rename")
            }
        }
    }

    private val _folders = MutableStateFlow<List<FileEntity>>(emptyList())
    val folders: StateFlow<List<FileEntity>> = _folders.asStateFlow()

    fun loadFolders() {
        if (userId.isBlank()) return
        viewModelScope.launch {
            try {
                _folders.value = fileRepository.getFolders(userId)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error loading folders", e)
            }
        }
    }

    fun moveFile(id: String, newParentId: String?) {
        viewModelScope.launch {
            try {
                fileRepository.moveFile(id, newParentId)
                _snackbarEvents.emit("File moved")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error moving file", e)
                _snackbarEvents.emit("Failed to move file")
            }
        }
    }
}
