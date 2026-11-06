package com.edwyn.padtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {
    @Test
    fun blocksExactAndSubdomainMatches() {
        assertTrue(AdBlocker.shouldBlock("https://doubleclick.net/ad.js"))
        assertTrue(AdBlocker.shouldBlock("https://securepubads.g.doubleclick.net/tag.js"))
    }

    @Test
    fun doesNotBlockLookalikeOrVideoHosts() {
        assertFalse(AdBlocker.shouldBlock("https://notdoubleclick.net/video"))
        assertFalse(AdBlocker.shouldBlock("https://rr1---sn.googlevideo.com/videoplayback"))
        assertFalse(AdBlocker.shouldBlock("not a url"))
    }
}
