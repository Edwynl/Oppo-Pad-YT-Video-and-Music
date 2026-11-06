package com.edwyn.padtube

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Keeps the CPU and Wi-Fi awake only while media plays, so streaming does not
 * stall with the screen off. Paused playback holds neither.
 */
class PlaybackLocks(context: Context, tag: String) {
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag:Playback")
        .apply { setReferenceCounted(false) }

    // HIGH_PERF is the mode documented to work with the screen off. Null on
    // devices without Wi-Fi.
    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$tag:Playback")
        ?.apply { setReferenceCounted(false) }

    fun update(playing: Boolean) {
        if (!playing) {
            release()
            return
        }
        if (!wakeLock.isHeld) wakeLock.acquire(MAX_HOLD_MS)
        if (wifiLock?.isHeld == false) wifiLock.acquire()
    }

    fun release() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock?.isHeld == true) wifiLock.release()
    }

    private companion object {
        const val MAX_HOLD_MS = 8 * 60 * 60 * 1000L
    }
}
