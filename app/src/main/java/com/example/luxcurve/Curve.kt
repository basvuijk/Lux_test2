package com.example.luxcurve

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The brightness curve: a handful of control points at fixed, log-spaced lux values.
 * The user drags the points up and down; in between we interpolate linearly on a log-lux axis.
 */
object Curve {
    const val POINT_COUNT = 7
    const val LOG_MIN = 0f   // 1 lux
    const val LOG_MAX = 4f   // 10 000 lux

    /** Lux value of each control point: 1, 4.6, 21, 100, 464, 2154, 10000. */
    val POINTS_LUX: FloatArray = FloatArray(POINT_COUNT) {
        10.0.pow((LOG_MIN + (LOG_MAX - LOG_MIN) * it / (POINT_COUNT - 1)).toDouble()).toFloat()
    }

    /** Default brightness in percent for each control point. */
    val DEFAULT = floatArrayOf(5f, 10f, 20f, 35f, 55f, 80f, 100f)

    /** log10(lux), clamped to the range the curve covers. */
    fun logLux(lux: Float): Float = log10(lux.coerceAtLeast(1f)).coerceIn(LOG_MIN, LOG_MAX)

    /** Brightness percentage (0..100) for a given value of log10(lux). */
    fun percentForLog(log: Float, points: FloatArray): Float {
        val pos = (log.coerceIn(LOG_MIN, LOG_MAX) - LOG_MIN) / (LOG_MAX - LOG_MIN) * (POINT_COUNT - 1)
        val i = floor(pos).toInt().coerceIn(0, POINT_COUNT - 2)
        val frac = pos - i
        return points[i] + (points[i + 1] - points[i]) * frac
    }

    fun percentForLux(lux: Float, points: FloatArray): Float = percentForLog(logLux(lux), points)
}

/** Latest readings, shared between the service and the settings screen (same process). */
object Live {
    @Volatile var running = false
    @Volatile var lux = -1f
    @Volatile var proximity = -1f
    @Volatile var percent = -1f
    @Volatile var screenOff = false
}
