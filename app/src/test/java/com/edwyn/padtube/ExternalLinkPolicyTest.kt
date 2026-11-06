package com.edwyn.padtube

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalLinkPolicyTest {
    private fun action(
        scheme: String? = "https",
        allowedHost: Boolean = false,
        mainFrame: Boolean = true,
        gesture: Boolean = false,
        redirect: Boolean = false
    ) = ExternalLinkPolicy.actionFor(scheme, allowedHost, mainFrame, gesture, redirect)

    @Test
    fun allowedHostsStayInAppWithoutAGesture() {
        assertEquals(LinkAction.STAY_IN_APP, action(allowedHost = true))
    }

    @Test
    fun subframesNeverLaunchAnotherApp() {
        assertEquals(LinkAction.STAY_IN_APP, action(mainFrame = false, gesture = true))
        assertEquals(LinkAction.STAY_IN_APP, action(scheme = "tel", mainFrame = false))
    }

    @Test
    fun tappedWebLinkOpensExternally() {
        assertEquals(LinkAction.OPEN_EXTERNALLY, action(gesture = true))
    }

    @Test
    fun serverRedirectOpensExternallyEvenThoughItCarriesNoGesture() {
        assertEquals(LinkAction.OPEN_EXTERNALLY, action(redirect = true))
    }

    @Test
    fun scriptDrivenJumpToAnotherSiteIsBlocked() {
        assertEquals(LinkAction.BLOCK, action())
    }

    @Test
    fun appLaunchingSchemesAreBlockedEvenWhenTapped() {
        listOf("tel", "intent", "market", "vnd.youtube", "file", "content", null).forEach {
            assertEquals("scheme=$it", LinkAction.BLOCK, action(scheme = it, gesture = true))
        }
    }

    @Test
    fun mailtoRequiresAGesture() {
        assertEquals(LinkAction.OPEN_EXTERNALLY, action(scheme = "mailto", gesture = true))
        assertEquals(LinkAction.BLOCK, action(scheme = "mailto"))
    }
}
