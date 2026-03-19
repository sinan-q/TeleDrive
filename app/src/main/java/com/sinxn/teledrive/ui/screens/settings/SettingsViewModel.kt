package com.sinxn.teledrive.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.crypto.BiometricPasswordStore
import com.sinxn.teledrive.data.crypto.EncryptionManager
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.crypto.Cipher

data class TelegramChat(
    val id: Long,
    val title: String,
    val isChannel: Boolean
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
    private val telegramClient: TelegramClient,
    private val encryptionManager: EncryptionManager,
    private val biometricPasswordStore: BiometricPasswordStore
) : ViewModel() {

    private val _selectedChannelName = MutableStateFlow(
        securePrefs.getStorageChannelName() ?: "Saved Messages"
    )
    val selectedChannelName: StateFlow<String> = _selectedChannelName.asStateFlow()

    private val _chats = MutableStateFlow<List<TelegramChat>>(emptyList())
    val chats: StateFlow<List<TelegramChat>> = _chats.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    private val _authOnLaunch = MutableStateFlow(securePrefs.isAuthOnLaunchEnabled())
    val authOnLaunch: StateFlow<Boolean> = _authOnLaunch.asStateFlow()

    private val _biometricUnlock = MutableStateFlow(
        securePrefs.isBiometricUnlockEnabled() && securePrefs.getBiometricUnlockSecret() != null
    )
    val biometricUnlock: StateFlow<Boolean> = _biometricUnlock.asStateFlow()

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
                        val isChannel = chatObj.type is TdApi.ChatTypeSupergroup && (chatObj.type as TdApi.ChatTypeSupergroup).isChannel
                        TelegramChat(id = chatObj.id, title = chatObj.title, isChannel = isChannel)
                    } else null
                }.filter { 
                    query.isEmpty() || it.title.contains(query, ignoreCase = true)
                }
                
                val finalList = mutableListOf<TelegramChat>()
                if (query.isEmpty()) {
                    finalList.add(TelegramChat(id = 0, title = "Saved Messages", isChannel = false))
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
}
