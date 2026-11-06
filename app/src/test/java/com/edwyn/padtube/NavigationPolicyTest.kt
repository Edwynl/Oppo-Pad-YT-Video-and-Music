package com.edwyn.padtube

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun detectsGoogleAndYouTubeSignInRoutes() {
        assertTrue(
            NavigationPolicy.isGoogleSignIn(
                "https://accounts.google.com/v3/signin/identifier"
            )
        )
        assertTrue(
            NavigationPolicy.isGoogleSignIn(
                "https://www.youtube.com/signin?action_handle_signin=true"
            )
        )
    }

    @Test
    fun doesNotTreatOrdinaryYouTubePagesAsSignIn() {
        assertFalse(
            NavigationPolicy.isGoogleSignIn(
                "https://www.youtube.com/watch?v=example"
            )
        )
    }

    @Test
    fun keepsInternalNavigationInsideTheApp() {
        assertTrue(NavigationPolicy.isAllowed("https://m.youtube.com/"))
        assertFalse(NavigationPolicy.isAllowed("https://example.com/"))
    }

    @Test
    fun recognisesOnlyTheHostsTheCleanerIsWrittenFor() {
        assertTrue(NavigationPolicy.isYouTubeHost("www.youtube.com"))
        assertTrue(NavigationPolicy.isYouTubeHost("M.YOUTUBE.COM"))
        assertFalse(NavigationPolicy.isYouTubeHost("accounts.google.com"))
        assertFalse(NavigationPolicy.isYouTubeHost("www.youtube.com.example.org"))
        assertFalse(NavigationPolicy.isYouTubeHost(null))
    }

    // Overload fed from WebResourceRequest.url, which (unlike java.net.URI)
    // parses URLs containing raw |, ^ or " in the query.
    @Test
    fun judgesAlreadyParsedSchemeAndHost() {
        assertTrue(NavigationPolicy.isAllowed("https", "www.youtube.com"))
        assertTrue(NavigationPolicy.isAllowed("https", "WWW.YOUTUBE.COM"))
        assertFalse(NavigationPolicy.isAllowed("https", "notyoutube.com"))
        assertFalse(NavigationPolicy.isAllowed("intent", "www.youtube.com"))
        assertFalse(NavigationPolicy.isAllowed("https", null))
        assertFalse(NavigationPolicy.isAllowed(null, "www.youtube.com"))
    }
}
