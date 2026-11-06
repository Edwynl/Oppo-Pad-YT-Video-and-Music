package com.edwyn.padtube

/**
 * Picks the page to reload after the WebView renderer dies. A second crash soon
 * after recovering suggests the page itself is at fault, so go home instead of
 * reloading it forever.
 */
object RendererRecovery {
    private const val CRASH_LOOP_WINDOW_MS = 10_000L

    fun urlAfterCrash(
        lastUrl: String?,
        homeUrl: String,
        lastRecoveryAtMs: Long?,
        nowMs: Long
    ): String {
        val crashLoop = lastRecoveryAtMs != null && nowMs - lastRecoveryAtMs < CRASH_LOOP_WINDOW_MS
        return if (crashLoop) homeUrl else lastUrl ?: homeUrl
    }
}
