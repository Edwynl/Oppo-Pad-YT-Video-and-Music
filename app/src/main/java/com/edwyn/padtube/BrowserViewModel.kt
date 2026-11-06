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
    private var failedUrl: String? = null

    fun onPageState(title: String?, progress: Int, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                title = title?.takeIf(String::isNotBlank) ?: "YouTube",
                progress = progress,
                isLoading = progress in 0..99,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                firstLoadComplete = it.firstLoadComplete || progress >= 100
            )
        }
    }

    /**
     * A navigation elsewhere clears the error overlay. The failed URL does not:
     * WebView's own error page can start loading it again right after the error.
     */
    fun onNavigationStarted(url: String?) {
        if (url != failedUrl) onRetry()
    }

    fun onLoadError(failedUrl: String?) {
        this.failedUrl = failedUrl
        _uiState.update { it.copy(hasError = true) }
    }

    /** The user asked to load again, so hide the error even for the same URL. */
    fun onRetry() {
        failedUrl = null
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
