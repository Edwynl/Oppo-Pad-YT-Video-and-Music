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
        return isAllowed(uri.scheme, uri.host)
    }

    fun isAllowed(scheme: String?, host: String?): Boolean {
        if (scheme != "http" && scheme != "https") return false
        val normalizedHost = host?.lowercase() ?: return false
        return allowedHosts.any { normalizedHost == it || normalizedHost.endsWith(".$it") }
    }

    /** The hosts YouTubeCleaner is written for; other allowed hosts must not receive it. */
    fun isYouTubeHost(host: String?): Boolean = when (host?.lowercase()) {
        "www.youtube.com", "m.youtube.com" -> true
        else -> false
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
