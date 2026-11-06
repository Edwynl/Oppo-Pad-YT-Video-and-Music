package com.edwyn.padtube

import com.edwyn.padtube.music.MusicCleaner
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * addDocumentStartJavaScript also runs in same-origin iframes such as live chat,
 * so each script must bail out there before installing observers or timers.
 */
class TopFrameOnlyScriptsTest {
    private fun assertGuardComesBefore(script: String, firstSideEffect: String) {
        val guard = script.indexOf("window !== window.top")
        assertTrue("missing top-frame guard", guard >= 0)
        assertTrue("guard must precede $firstSideEffect", guard < script.indexOf(firstSideEffect))
    }

    @Test
    fun youTubeCleanerSkipsIframes() {
        assertGuardComesBefore(YouTubeCleaner.script, "window.__padTubeCleaner = VERSION")
    }

    @Test
    fun musicCleanerSkipsIframes() {
        assertGuardComesBefore(MusicCleaner.script, "window.__padMusicVersion = VERSION")
    }

    @Test
    fun mediaBridgeSkipsIframes() {
        assertGuardComesBefore(MediaBridge.reportScript, "window.__padTubeMedia = true")
    }
}
