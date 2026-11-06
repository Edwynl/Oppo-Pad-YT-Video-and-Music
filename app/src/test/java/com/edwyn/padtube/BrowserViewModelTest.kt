package com.edwyn.padtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewModelTest {
    private val failedUrl = "https://www.youtube.com/watch?v=abc"
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
        vm.onNavigationStarted("https://www.youtube.com/")
        assertFalse(hasError)
    }

    @Test
    fun retryClearsTheError() {
        vm.onRetry()
        assertFalse(hasError)
    }
}
