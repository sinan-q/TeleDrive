package com.sinxn.teledrive.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sinxn.teledrive.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

sealed class HomeUiState {
    data object Loading : HomeUiState()
    data class Success(val messages: List<TdApi.Message>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        fetchSavedMessages()
    }

    fun fetchSavedMessages() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            try {
                val messages = fileRepository.getSavedMessagesHistory()
                _uiState.value = HomeUiState.Success(messages)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to fetch saved messages", e)
                _uiState.value = HomeUiState.Error(e.message ?: "Unknown error occurred")
            }
        }
    }
}
