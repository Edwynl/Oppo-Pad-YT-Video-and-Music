package com.edwyn.padtube

import java.net.URI

object AdBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "googletagservices.com",
        "google-analytics.com",
        "adservice.google.com",
        "adservice.google.com.au",
        "ads.youtube.com",
        "pagead2.googlesyndication.com",
        "partner.googleadservices.com",
        "securepubads.g.doubleclick.net",
        "static.doubleclick.net"
    )

    fun shouldBlock(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return blockedHosts.any { blocked -> host == blocked || host.endsWith(".$blocked") }
    }
}
