package com.edwyn.padtube.music

import java.net.URI

object NavigationPolicy {
    // Google sign-in (accounts.google.com) stays in the app: the WebView hides
    // the X-Requested-With header and uses a desktop user agent, so the
    // logged-in session gets ad cleaning and lock-screen controls too.
    private val allowedHosts = setOf(
        "music.youtube.com",
        "youtube.com",
        "youtu.be",
        "google.com",
        "google.com.au",
        "googleapis.com",
        "gstatic.com"
    )

    fun isAllowed(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isMusicHost(rawUrl: String?): Boolean {
        val host = runCatching { URI(rawUrl.orEmpty()).host?.lowercase() }.getOrNull()
        return host == "music.youtube.com"
    }
}
