package com.edwyn.padtube

enum class LinkAction { STAY_IN_APP, OPEN_EXTERNALLY, BLOCK }

/**
 * Decides what a navigation out of the allowed hosts may do. Web content must
 * not be able to launch other apps on its own: iframes stay in their frame,
 * only web and mail links may leave the app, and only after a tap. A server
 * redirect carries no gesture of its own, so it is judged by scheme alone.
 */
object ExternalLinkPolicy {
    private val launchableSchemes = setOf("http", "https", "mailto")

    fun actionFor(
        scheme: String?,
        allowedHost: Boolean,
        isMainFrame: Boolean,
        hasGesture: Boolean,
        isRedirect: Boolean
    ): LinkAction = when {
        allowedHost || !isMainFrame -> LinkAction.STAY_IN_APP
        scheme !in launchableSchemes -> LinkAction.BLOCK
        hasGesture || isRedirect -> LinkAction.OPEN_EXTERNALLY
        else -> LinkAction.BLOCK
    }
}
