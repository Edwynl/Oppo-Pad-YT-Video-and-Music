package com.edwyn.padtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBridgeTest {
    @Test
    fun reportScriptPostsToTheOriginScopedListener() {
        val script = MediaBridge.reportScript

        assertTrue(script.contains("window.padtubeMedia"))
        assertTrue(script.contains("padtubeMedia.postMessage(msg)"))
        // Media events do not bubble; they must be captured on document.
        assertTrue(script.contains("document.addEventListener(e, report, true)"))
    }

    @Test
    fun commandScriptDrivesThePlayerApi() {
        assertTrue(MediaBridge.commandScript(play = true).contains("p.playVideo()"))
        assertFalse(MediaBridge.commandScript(play = true).contains("pauseVideo"))
        assertTrue(MediaBridge.commandScript(play = false).contains("p.pauseVideo()"))
    }
}
