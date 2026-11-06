package com.edwyn.padtube.music

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCacheTest {
    @Test
    fun keepsImagesWithinTheLimitAtFullSize() {
        assertEquals(1, ArtworkCache.sampleSizeFor(544, 544, 1024))
        assertEquals(1, ArtworkCache.sampleSizeFor(1024, 600, 1024))
    }

    @Test
    fun halvesLargeImagesUntilTheLongerSideFits() {
        assertEquals(2, ArtworkCache.sampleSizeFor(1025, 100, 1024))
        assertEquals(4, ArtworkCache.sampleSizeFor(600, 4000, 1024))
    }

    @Test
    fun undecodableBoundsDoNotLoop() {
        assertEquals(1, ArtworkCache.sampleSizeFor(-1, -1, 1024))
    }
}
