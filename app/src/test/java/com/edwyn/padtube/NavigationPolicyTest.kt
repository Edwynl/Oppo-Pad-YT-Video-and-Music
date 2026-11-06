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
}
