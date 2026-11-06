package com.edwyn.padtube

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BrowserUiState(
    val title: String = "YouTube",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val desktopMode: Boolean = true,
    val firstLoadComplete: Boolean = false,
    val hasError: Boolean = false,
    val autoLogin: Boolean = false,
    val loggedIn: Boolean = false,
    val message: String? = null
)

class BrowserViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    fun onPageState(title: String?, progress: Int, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                title = title?.takeIf(String::isNotBlank) ?: "YouTube",
                progress = progress,
                isLoading = progress in 0..99,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                // A fresh navigation clears any previous error overlay.
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

    fun setDesktopMode(enabled: Boolean) {
        _uiState.update { it.copy(desktopMode = enabled) }
    }

    fun setAutoLogin(enabled: Boolean) {
        _uiState.update { it.copy(autoLogin = enabled) }
    }

    fun setLoggedIn(loggedIn: Boolean) {
        _uiState.update { it.copy(loggedIn = loggedIn) }
    }

    fun showMessage(message: String?) {
        _uiState.update { it.copy(message = message) }
    }
}
