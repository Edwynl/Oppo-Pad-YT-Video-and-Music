package com.edwyn.padtube.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun keepsMusicAndGoogleSignInInsideTheApp() {
        assertTrue(NavigationPolicy.isAllowed("https://music.youtube.com/"))
        assertTrue(NavigationPolicy.isAllowed("https://accounts.google.com/v3/signin/identifier"))
        assertTrue(NavigationPolicy.isAllowed("https://music.youtube.com/signin"))
        assertFalse(NavigationPolicy.isAllowed("https://example.com/"))
        assertFalse(NavigationPolicy.isAllowed("intent://music.youtube.com/"))
    }

    @Test
    fun judgesAlreadyParsedSchemeAndHost() {
        assertTrue(NavigationPolicy.isAllowed("https", "music.youtube.com"))
        assertFalse(NavigationPolicy.isAllowed("https", "example.com"))
        assertFalse(NavigationPolicy.isAllowed("intent", "music.youtube.com"))
        assertFalse(NavigationPolicy.isAllowed("https", null))
    }
}
