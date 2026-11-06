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
    private var failedUrl: String? = null

    fun onPageState(title: String?, progress: Int, canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update {
            it.copy(
                pageTitle = title?.takeIf(String::isNotBlank) ?: "YouTube Music",
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

    fun showMessage(message: String?) {
        _uiState.update { it.copy(message = message) }
    }
}
