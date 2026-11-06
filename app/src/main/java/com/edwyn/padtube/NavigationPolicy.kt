package com.edwyn.padtube

import java.net.URI

object NavigationPolicy {
    private val allowedHosts = setOf(
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

    fun isGoogleSignIn(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty().lowercase()
        return host == "accounts.google.com" ||
            host.endsWith(".accounts.google.com") ||
            ((host == "youtube.com" || host.endsWith(".youtube.com")) &&
                (path.startsWith("/signin") || path.startsWith("/o/oauth")))
    }
}
