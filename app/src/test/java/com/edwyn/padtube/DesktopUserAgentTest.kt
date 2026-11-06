package com.edwyn.padtube

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopUserAgentTest {
    @Test
    fun reusesTheInstalledWebViewChromeVersion() {
        val webView = "Mozilla/5.0 (Linux; Android 15; OPD2404 Build/AP3A; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            MainActivity.desktopUserAgentFor(webView)
        )
    }

    @Test
    fun fallsBackWhenNoChromeVersionIsPresent() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            MainActivity.desktopUserAgentFor("")
        )
    }
}
