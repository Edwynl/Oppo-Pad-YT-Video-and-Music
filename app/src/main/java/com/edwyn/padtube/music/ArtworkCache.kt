package com.edwyn.padtube.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny single-entry cache for the current track's cover art. Only the currently
 * playing track matters, so keeping one bitmap is enough and avoids re-fetching
 * on every recomposition or notification refresh. [load] blocks and must be
 * called off the main thread.
 */
object ArtworkCache {
    private val lock = Any()
    private var cachedUrl: String? = null
    private var cachedBitmap: Bitmap? = null

    internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) sampleSize *= 2
        return sampleSize
    }

    fun get(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        synchronized(lock) {
            return if (url == cachedUrl) cachedBitmap else null
        }
    }

    fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        get(url)?.let { return it }
        val bitmap = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                doInput = true
            }
            try {
                decode(connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null
        synchronized(lock) {
            cachedUrl = url
            cachedBitmap = bitmap
        }
        return bitmap
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private const val MAX_DIMENSION = 1024
}
