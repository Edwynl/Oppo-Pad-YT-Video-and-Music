package com.edwyn.padtube

/**
 * YouTube ignores script clicks on its "Skip" ad button, so the cleaner asks
 * the app to tap it like a finger would. The page sends "x,y" as fractions of
 * its visible viewport; the app turns that into view pixels.
 */
internal object NativeTap {
    const val LISTENER_NAME = "padtubeTap"

    /** Minimum gap between taps, so a page cannot drive a stream of input. */
    const val MIN_INTERVAL_MS = 800L

    fun parse(message: String): Pair<Double, Double>? {
        val parts = message.split(',')
        if (parts.size != 2) return null
        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        return x to y
    }

    fun pointFor(fraction: Pair<Double, Double>, width: Int, height: Int): Pair<Float, Float> =
        (fraction.first * width).toFloat() to (fraction.second * height).toFloat()
}
