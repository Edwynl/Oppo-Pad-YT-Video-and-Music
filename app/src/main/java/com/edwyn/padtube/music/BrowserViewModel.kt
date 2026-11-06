package com.edwyn.padtube.music

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MusicUiState(
    val pageTitle: String = "YouTube Music",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val firstLoadComplete: Boolean = false,
    val hasError: Boolean = false,
    val message: String? = null
)

class BrowserViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    fun onPageState(title: String?, progress: Int, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                pageTitle = title?.takeIf(String::isNotBlank) ?: "YouTube Music",
                progress = progress,
                isLoading = progress in 0..99,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                hasError = if (progress in 0..99) false else it.hasError,
                firstLoadComplete = it.firstLoadComplete || progress >= 100
            )
        }
    }

    fun onLoadError() {
        _uiState.update { it.copy(hasError = true) }
    }

    fun onRetry() {
        _uiState.update { it.copy(hasError = false) }
    }

    fun showMessage(message: String?) {
        _uiState.update { it.copy(message = message) }
    }
}
