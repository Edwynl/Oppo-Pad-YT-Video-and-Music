package com.edwyn.padtube.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicViewModelTest {
    private val failedUrl = "https://music.youtube.com/watch?v=abc"
    private val vm = BrowserViewModel().apply {
        onNavigationStarted(failedUrl)
        onLoadError(failedUrl)
    }

    private val hasError get() = vm.uiState.value.hasError

    @Test
    fun errorSurvivesLaterProgressOfTheFailedLoad() {
        vm.onPageState("x", 10, canGoBack = false, canGoForward = false)
        assertTrue(hasError)
    }

    @Test
    fun errorSurvivesTheErrorPageStartingForTheSameUrl() {
        vm.onNavigationStarted(failedUrl)
        assertTrue(hasError)
    }

    @Test
    fun navigatingToAnotherPageClearsTheError() {
        vm.onNavigationStarted("https://music.youtube.com/")
        assertFalse(hasError)
    }

    @Test
    fun retryClearsTheError() {
        vm.onRetry()
        assertFalse(hasError)
    }
}
