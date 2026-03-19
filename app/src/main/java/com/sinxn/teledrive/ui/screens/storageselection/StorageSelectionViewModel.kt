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
    private val telegramClient: TelegramClient
) : ViewModel() {

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

    init {
        refreshSelectedChannel()
    }

    fun refreshSelectedChannel() {
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
}
