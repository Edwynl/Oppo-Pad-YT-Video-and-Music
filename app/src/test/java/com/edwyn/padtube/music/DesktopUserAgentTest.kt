package com.edwyn.padtube.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUserAgentTest {
    @Test
    fun reusesTheInstalledWebViewChromeVersion() {
        val webView = "Mozilla/5.0 (Linux; Android 16; OPD2511; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
            MusicActivity.desktopUserAgentFor(webView)
        )
    }

    @Test
    fun clientHintsMatchTheDesktopChromeUserAgent() {
        val webView = "Mozilla/5.0 (Linux; Android 16; OPD2511; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Safari/537.36"

        val metadata = MusicActivity.desktopUserAgentMetadataFor(webView)

        // WebView otherwise reports brand "Android WebView" / platform
        // "Android", contradicting the Windows UA; Google sign-in rejects it.
        val brands = metadata.brandVersionList.map { it.brand to it.majorVersion }
        assertTrue(("Google Chrome" to "153") in brands)
        assertTrue(("Chromium" to "153") in brands)
        assertFalse(brands.any { it.first.contains("WebView") })
        assertEquals("153.0.8010.36", metadata.fullVersion)
        assertEquals("Windows", metadata.platform)
        assertFalse(metadata.isMobile)
    }

    @Test
    fun fallsBackWhenNoChromeVersionIsPresent() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            MusicActivity.desktopUserAgentFor("")
        )
    }
}
