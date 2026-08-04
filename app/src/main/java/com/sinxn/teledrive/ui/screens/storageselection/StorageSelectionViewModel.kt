package com.sinxn.teledrive.ui.screens.storageselection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

@HiltViewModel
class StorageSelectionViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val telegramClient: TelegramClient,
    private val metadataManager: com.sinxn.teledrive.data.repository.MetadataManager
) : ViewModel() {

    fun validateStorageLocation(onResult: (com.sinxn.teledrive.data.repository.StorageValidation) -> Unit) {
        viewModelScope.launch {
            try {
                val configuredChatId = selectedChannelId
                val targetChatId = if (configuredChatId != null) {
                    configuredChatId
                } else {
                    val activeUserId = securePrefs.getUserId()
                    if (activeUserId != null) {
                        val chat = telegramClient.send(
                            TdApi.CreatePrivateChat(activeUserId.toLong(), true)
                        ) as? TdApi.Chat
                        chat?.id
                    } else null
                }

                if (targetChatId == null) {
                    onResult(com.sinxn.teledrive.data.repository.StorageValidation.Invalid)
                    return@launch
                }

                val validation = metadataManager.validateStorageChat(targetChatId)
                onResult(validation)
            } catch (e: Exception) {
                onResult(com.sinxn.teledrive.data.repository.StorageValidation.Invalid)
            }
        }
    }

    fun checkHasVault(onResult: (Boolean) -> Unit) {
        validateStorageLocation { validation ->
            onResult(validation is com.sinxn.teledrive.data.repository.StorageValidation.ExistingVault)
        }
    }

    fun saveSetupProgress(isSyncMode: Boolean) {
        securePrefs.setSyncMode(isSyncMode)
        securePrefs.setSetupStep(com.sinxn.teledrive.data.local.prefs.SetupStep.MASTER_PASSWORD)
    }

    private val selectedChannelId: Long?
        get() = securePrefs.getStorageChannelId()

    private val _selectedChannelName = MutableStateFlow(
        securePrefs.getStorageChannelName() ?: "Saved Messages"
    )
    val selectedChannelName: StateFlow<String> = _selectedChannelName.asStateFlow()

    private val _selectedChannelKind = MutableStateFlow(StorageLocationKind.SavedMessages)
    val selectedChannelKind: StateFlow<StorageLocationKind> = _selectedChannelKind.asStateFlow()

    private val _selectedChannelPhotoPath = MutableStateFlow<String?>(null)
    val selectedChannelPhotoPath: StateFlow<String?> = _selectedChannelPhotoPath.asStateFlow()

    private val _chats = MutableStateFlow<List<StorageLocationOption>>(emptyList())
    val chats: StateFlow<List<StorageLocationOption>> = _chats.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    init {
        observeFileUpdates()
        refreshSelectedChannel()
    }

    private fun observeFileUpdates() {
        viewModelScope.launch {
            telegramClient.updates.collect { update ->
                if (update is TdApi.UpdateFile) {
                    val local = update.file.local
                    if (!local.isDownloadingCompleted || local.path.isEmpty()) return@collect

                    _chats.value = _chats.value.map { chat ->
                        if (chat.photoFileId == update.file.id) {
                            chat.copy(photoPath = local.path)
                        } else {
                            chat
                        }
                    }

                    val configuredChatId = selectedChannelId
                    if (configuredChatId != null) {
                        _chats.value.firstOrNull { it.id == configuredChatId }?.let { selectedChat ->
                            _selectedChannelPhotoPath.value = selectedChat.photoPath
                        }
                    }
                }
            }
        }
    }

    fun refreshSelectedChannel() {
        val selectedChatId = selectedChannelId
        if (selectedChatId == null) {
            _selectedChannelName.value = "Saved Messages"
            _selectedChannelKind.value = StorageLocationKind.SavedMessages
            _selectedChannelPhotoPath.value = null
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
                        _selectedChannelPhotoPath.value = selectedChat.photoPath
                        selectedChat.photoFileId?.let(::requestChatPhoto)
                    }
                }
        }
    }

    fun loadChats(query: String = "") {
        viewModelScope.launch {
            _isSearchLoading.value = true
            try {
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
                        chatObj.toStorageLocationOption().also { chat ->
                            chat.photoFileId?.let(::requestChatPhoto)
                        }
                    } else null
                }.filter {
                    it.hasWritePermission && (query.isEmpty() || it.title.contains(query, ignoreCase = true))
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
        securePrefs.setMetadataMessageId(null)
        if (id == 0L) {
            securePrefs.setStorageChannelId(null)
            securePrefs.setStorageChannelName(null)
            _selectedChannelName.value = "Saved Messages"
            _selectedChannelKind.value = StorageLocationKind.SavedMessages
            _selectedChannelPhotoPath.value = null
        } else {
            securePrefs.setStorageChannelId(id)
            securePrefs.setStorageChannelName(title)
            _selectedChannelName.value = title
            _selectedChannelKind.value = kind
            _selectedChannelPhotoPath.value = _chats.value.firstOrNull { it.id == id }?.photoPath
        }
    }

    private fun requestChatPhoto(fileId: Int) {
        telegramClient.sendAsync(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { }
    }
}
