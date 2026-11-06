package com.edwyn.padtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTapTest {
    @Test
    fun parsesAPointGivenAsFractionsOfTheViewport() {
        assertEquals(0.5 to 0.25, NativeTap.parse("0.5000,0.2500"))
    }

    @Test
    fun rejectsMalformedMessages() {
        assertNull(NativeTap.parse(""))
        assertNull(NativeTap.parse("0.5"))
        assertNull(NativeTap.parse("a,b"))
        assertNull(NativeTap.parse("0.1,0.2,0.3"))
    }

    @Test
    fun rejectsPointsOutsideTheVisiblePage() {
        assertNull(NativeTap.parse("1.2,0.5"))
        assertNull(NativeTap.parse("0.5,-0.1"))
        assertNull(NativeTap.parse("NaN,0.5"))
    }

    @Test
    fun scalesFractionsToViewPixels() {
        assertEquals(1200f to 750f, NativeTap.pointFor(0.5 to 0.25, 2400, 3000))
    }

    @Test
    fun cleanerAsksForATapOnlyWhereTheSkipButtonIsOnTop() {
        val script = YouTubeCleaner.script
        assertTrue(script.contains("window.${NativeTap.LISTENER_NAME}"))
        assertTrue(script.contains("document.elementFromPoint("))
    }
}
