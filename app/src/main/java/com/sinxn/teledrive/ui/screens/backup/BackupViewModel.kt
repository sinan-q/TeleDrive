package com.sinxn.teledrive.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager
) : ViewModel() {

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    fun exportBackup(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupState.value = BackupState.Loading
            runCatching {
                backupManager.exportBackup(context, uri)
            }.onSuccess {
                _backupState.value = BackupState.Completed("Backup exported successfully.")
            }.onFailure { error ->
                _backupState.value = BackupState.Error(error.message ?: "Backup export failed.")
            }
        }
    }

    fun importBackup(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupState.value = BackupState.Loading
            runCatching {
                backupManager.importBackup(context, uri)
            }.onSuccess {
                _backupState.value = BackupState.Completed("Backup imported successfully.")
            }.onFailure { error ->
                _backupState.value = BackupState.Error(error.message ?: "Backup import failed.")
            }
        }
    }

    fun consumeState() {
        _backupState.value = BackupState.Idle
    }
}

sealed interface BackupState {
    data object Idle : BackupState
    data object Loading : BackupState
    data class Completed(val message: String) : BackupState
    data class Error(val message: String) : BackupState
}
