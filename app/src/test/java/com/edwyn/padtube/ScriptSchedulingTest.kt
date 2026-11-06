package com.edwyn.padtube

import com.edwyn.padtube.music.MusicCleaner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSchedulingTest {
    @Test
    fun cleanersDoNotRunAFullPassOnEveryTimerTick() {
        assertFalse(YouTubeCleaner.script.contains("setInterval(cleanNow"))
        assertFalse(MusicCleaner.script.contains("setInterval(cleanNow"))
    }

    @Test
    fun musicReportsPositionFromTheVideoNowThatTheTimerIsSlow() {
        assertTrue(MusicCleaner.script.contains("'timeupdate'"))
    }

    @Test
    fun mediaBridgeIgnoresMutedPreviewsButNotAds() {
        assertTrue(MediaBridge.reportScript.contains("!v.muted"))
        assertTrue(MediaBridge.reportScript.contains(".ad-showing"))
        assertTrue(MediaBridge.reportScript.contains("'volumechange'"))
    }
}
