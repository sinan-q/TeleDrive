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

data class SelectableChat(
    val id: Long,
    val title: String,
    val isChannel: Boolean
)

@HiltViewModel
class StorageSelectionViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val telegramClient: TelegramClient
) : ViewModel() {

    private val _selectedChannelName = MutableStateFlow(
        securePrefs.getStorageChannelName() ?: "Saved Messages"
    )
    val selectedChannelName: StateFlow<String> = _selectedChannelName.asStateFlow()

    private val _chats = MutableStateFlow<List<SelectableChat>>(emptyList())
    val chats: StateFlow<List<SelectableChat>> = _chats.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

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
                        val isChannel = chatObj.type is TdApi.ChatTypeSupergroup && (chatObj.type as TdApi.ChatTypeSupergroup).isChannel
                        SelectableChat(id = chatObj.id, title = chatObj.title, isChannel = isChannel)
                    } else null
                }.filter {
                    query.isEmpty() || it.title.contains(query, ignoreCase = true)
                }

                val finalList = mutableListOf<SelectableChat>()
                if (query.isEmpty()) {
                    finalList.add(SelectableChat(id = 0, title = "Saved Messages", isChannel = false))
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

    fun selectChannel(id: Long, title: String) {
        if (id == 0L) {
            securePrefs.setStorageChannelId(null)
            securePrefs.setStorageChannelName(null)
            _selectedChannelName.value = "Saved Messages"
        } else {
            securePrefs.setStorageChannelId(id)
            securePrefs.setStorageChannelName(title)
            _selectedChannelName.value = title
        }
    }
}
