package com.sinxn.teledrive.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.local.prefs.SecurePrefs
import com.sinxn.teledrive.data.model.SyncStatus
import com.sinxn.teledrive.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncProgressViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val securePrefs: SecurePrefs
) : ViewModel() {

    val syncStatus: StateFlow<SyncStatus> = fileRepository.syncStatus

    init {
        startSync()
    }

    fun startSync() {
        val userId = securePrefs.getUserId()
        if (userId != null) {
            viewModelScope.launch {
                fileRepository.syncExistingFiles(userId)
            }
        }
    }
}
