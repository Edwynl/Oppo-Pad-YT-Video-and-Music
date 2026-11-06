package com.edwyn.padtube

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererRecoveryTest {
    private val home = "https://www.youtube.com/"
    private val watch = "https://www.youtube.com/watch?v=abc"

    @Test
    fun firstCrashReturnsToTheCurrentPage() {
        assertEquals(watch, RendererRecovery.urlAfterCrash(watch, home, null, 50_000L))
    }

    @Test
    fun crashLongAfterTheLastRecoveryReturnsToTheCurrentPage() {
        assertEquals(watch, RendererRecovery.urlAfterCrash(watch, home, 1_000L, 11_000L))
    }

    @Test
    fun repeatedCrashGoesHomeSoAFaultyPageCannotLoopForever() {
        assertEquals(home, RendererRecovery.urlAfterCrash(watch, home, 1_000L, 10_999L))
    }

    @Test
    fun goesHomeWhenNoPageWasEverLoaded() {
        assertEquals(home, RendererRecovery.urlAfterCrash(null, home, null, 50_000L))
    }
}
