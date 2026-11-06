package com.edwyn.padtube

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeCleanerTest {
    @Test
    fun tabletLayoutPreservesVideoRatioAndUsesLandscapeWidth() {
        val script = YouTubeCleaner.script

        assertTrue(script.contains("object-fit: contain !important"))
        assertTrue(script.contains("ytm-watch .player-container"))
        assertTrue(script.contains("width: 100% !important"))
        assertTrue(script.contains(".ytp-size-button"))
    }

    @Test
    fun mobileSiteKeepsPlayingInPictureInPictureWithSound() {
        val script = YouTubeCleaner.script

        // The mobile player pauses when outer window area << screen area.
        assertTrue(script.contains("Object.defineProperty(window, 'outerWidth'"))
        assertTrue(script.contains("html.padtube-pip ytm-mobile-topbar-renderer"))
        // Muted autoplay is undone only while YouTube's unmute popup shows.
        assertTrue(script.contains("document.querySelector('.ytp-unmute')"))
    }

    @Test
    fun fullscreenControlsAvoidTheTopSystemGestureZone() {
        val script = YouTubeCleaner.script

        assertTrue(script.contains(".html5-video-player.ytp-fullscreen .ytp-chrome-top"))
        assertTrue(script.contains("env(safe-area-inset-top)"))
    }
}
