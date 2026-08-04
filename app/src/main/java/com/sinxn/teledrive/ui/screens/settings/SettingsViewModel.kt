package com.sinxn.teledrive.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.crypto.BiometricPasswordStore
import com.sinxn.teledrive.data.crypto.EncryptionManager
import com.sinxn.teledrive.data.local.entity.UserEntity
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.repository.FileFolderSummary
import com.sinxn.teledrive.data.repository.FileRepository
import com.sinxn.teledrive.data.repository.UserRepository
import com.sinxn.teledrive.data.telegram.AuthManager
import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.crypto.Cipher
import com.sinxn.teledrive.ui.screens.storageselection.StorageLocationKind
import com.sinxn.teledrive.ui.screens.storageselection.StorageLocationOption
import com.sinxn.teledrive.ui.screens.storageselection.toStorageLocationOption

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val telegramClient: TelegramClient,
    private val encryptionManager: EncryptionManager,
    private val biometricPasswordStore: BiometricPasswordStore,
    private val fileRepository: FileRepository,
    private val userRepository: UserRepository,
    private val authManager: AuthManager,
    private val fileDao: com.sinxn.teledrive.data.local.dao.FileDao,
    private val userDao: com.sinxn.teledrive.data.local.dao.UserDao
) : ViewModel() {
    private var fileFolderSummaryJob: Job? = null


    private val _selectedChannelName = MutableStateFlow(
        securePrefs.getStorageChannelName() ?: "Saved Messages"
    )
    val selectedChannelName: StateFlow<String> = _selectedChannelName.asStateFlow()

    private val _selectedChannelKind = MutableStateFlow(StorageLocationKind.SavedMessages)
    val selectedChannelKind: StateFlow<StorageLocationKind> = _selectedChannelKind.asStateFlow()

    private val _chats = MutableStateFlow<List<StorageLocationOption>>(emptyList())
    val chats: StateFlow<List<StorageLocationOption>> = _chats.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    private val _authOnLaunch = MutableStateFlow(securePrefs.isAuthOnLaunchEnabled())
    val authOnLaunch: StateFlow<Boolean> = _authOnLaunch.asStateFlow()

    private val _biometricUnlock = MutableStateFlow(
        securePrefs.isBiometricUnlockEnabled() && securePrefs.getBiometricUnlockSecret() != null
    )
    val biometricUnlock: StateFlow<Boolean> = _biometricUnlock.asStateFlow()

    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    private val _fileFolderSummary = MutableStateFlow(FileFolderSummary())
    val fileFolderSummary: StateFlow<FileFolderSummary> = _fileFolderSummary.asStateFlow()

    init {
        refreshStorageSelection()
        viewModelScope.launch {
            userRepository.getCurrentUser().collectLatest { user ->
                _currentUser.value = user
                user?.photoFileId?.let { fileId ->
                    if (user.photoPath.isNullOrBlank()) {
                        requestProfilePhoto(fileId)
                    }
                }
                if (user == null) {
                    fileFolderSummaryJob?.cancel()
                    _fileFolderSummary.value = FileFolderSummary()
                } else {
                    fileFolderSummaryJob?.cancel()
                    fileFolderSummaryJob = launch {
                        fileRepository.observeFileFolderCounts(user.id).collectLatest { summary ->
                            _fileFolderSummary.value = summary
                        }
                    }
                }
            }
        }
        observeProfilePhotoUpdates()
    }

    fun refreshStorageSelection() {
        val selectedChatId = securePrefs.getStorageChannelId()
        if (selectedChatId == null) {
            _selectedChannelName.value = "Saved Messages"
            _selectedChannelKind.value = StorageLocationKind.SavedMessages
            return
        }

        viewModelScope.launch {
            runCatching {
                telegramClient.send(TdApi.GetChat(selectedChatId))
            }.getOrNull()
                ?.let { chatObj ->
                    if (chatObj is TdApi.Chat) {
                        val selectedChat = chatObj.toStorageLocationOption()
                        _selectedChannelName.value = selectedChat.title
                        _selectedChannelKind.value = selectedChat.kind
                    }
                }
        }
    }

    fun loadChats(query: String = "") {
        viewModelScope.launch {
            _isSearchLoading.value = true
            try {
                // Ensure main chat list is loaded initially
                telegramClient.sendAsync(TdApi.LoadChats(TdApi.ChatListMain(), 100))

                val chatIds = if (query.isEmpty()) {
                    val result = telegramClient.send(TdApi.GetChats(TdApi.ChatListMain(), 100))
                    if (result is TdApi.Chats) result.chatIds.toList() else emptyList()
                } else {
                    val result = telegramClient.send(TdApi.SearchChats(query, 50))
                    if (result is TdApi.Chats) result.chatIds.toList() else emptyList()
                }

                val loadedChats = chatIds.mapNotNull { id ->
                    val chatObj = telegramClient.send(TdApi.GetChat(id))
                    if (chatObj is TdApi.Chat) {
                        chatObj.toStorageLocationOption()
                    } else null
                }.filter { 
                    query.isEmpty() || it.title.contains(query, ignoreCase = true)
                }
                
                val finalList = mutableListOf<StorageLocationOption>()
                if (query.isEmpty()) {
                    finalList.add(
                        StorageLocationOption(
                            id = 0,
                            title = "Saved Messages",
                            kind = StorageLocationKind.SavedMessages
                        )
                    )
                }
                finalList.addAll(loadedChats)
                
                _chats.value = finalList

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSearchLoading.value = false
            }
        }
    }

    fun selectChannel(id: Long, title: String, kind: StorageLocationKind) {
        if (id == 0L) {
            securePrefs.setStorageChannelId(null)
            securePrefs.setStorageChannelName(null)
            _selectedChannelName.value = "Saved Messages"
            _selectedChannelKind.value = StorageLocationKind.SavedMessages
        } else {
            securePrefs.setStorageChannelId(id)
            securePrefs.setStorageChannelName(title)
            _selectedChannelName.value = title
            _selectedChannelKind.value = kind
        }
    }

    fun setAuthOnLaunch(enabled: Boolean) {
        securePrefs.setAuthOnLaunch(enabled)
        _authOnLaunch.value = enabled
        if (!enabled) {
            disableBiometricUnlock()
        }
    }

    fun prepareBiometricEnrollmentCipher(): Cipher? {
        if (!encryptionManager.isUnlocked) return null
        return try {
            biometricPasswordStore.createBiometricEncryptCipher()
        } catch (_: Exception) {
            null
        }
    }

    fun enableBiometricUnlock(cipher: Cipher): Boolean {
        return try {
            val encryptedSecret = biometricPasswordStore.encryptForBiometric(
                encryptionManager.getSessionPassword(),
                cipher
            )
            securePrefs.setBiometricUnlockSecret(encryptedSecret)
            securePrefs.setBiometricUnlock(true)
            _biometricUnlock.value = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disableBiometricUnlock() {
        securePrefs.clearBiometricUnlockSecret()
        securePrefs.setBiometricUnlock(false)
        _biometricUnlock.value = false
    }

    fun logout() {
        disableBiometricUnlock()
        securePrefs.clearAutoUnlockSecret()
        securePrefs.clearMasterPassword()
        securePrefs.setAuthOnLaunch(false)
        _authOnLaunch.value = false
        securePrefs.setStorageChannelId(null)
        securePrefs.setStorageChannelName(null)
        securePrefs.setMetadataMessageId(null)
        encryptionManager.lock()
        _selectedChannelName.value = "Saved Messages"
        _selectedChannelKind.value = StorageLocationKind.SavedMessages

        viewModelScope.launch {
            fileDao.deleteAllFiles()
            userDao.deleteAllUsers()
            securePrefs.clearUserId()
            authManager.logout()
        }
    }

    private fun observeProfilePhotoUpdates() {
        viewModelScope.launch {
            telegramClient.updates.collectLatest { update ->
                if (update !is TdApi.UpdateFile) return@collectLatest

                val local = update.file.local
                if (!local.isDownloadingCompleted || local.path.isEmpty()) return@collectLatest

                val user = _currentUser.value ?: return@collectLatest
                if (user.photoFileId != update.file.id || user.photoPath == local.path) return@collectLatest

                val updatedUser = user.copy(photoPath = local.path)
                _currentUser.value = updatedUser
                userRepository.saveUser(updatedUser)
            }
        }
    }

    private fun requestProfilePhoto(fileId: Int) {
        telegramClient.sendAsync(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { }
    }
}
