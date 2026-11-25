package com.example.luxcurve

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored in SharedPreferences. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("lux_curve", Context.MODE_PRIVATE)

    /** Is the background service supposed to be running (also used to restart after boot). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Brightness (0..100 %) at each of the fixed lux positions in [Curve.POINTS_LUX]. */
    var curve: FloatArray
        get() {
            val parts = sp.getString("curve", null)?.split(",")
            if (parts == null || parts.size != Curve.POINT_COUNT) return Curve.DEFAULT.copyOf()
            return try {
                FloatArray(Curve.POINT_COUNT) { parts[it].toFloat().coerceIn(0f, 100f) }
            } catch (e: NumberFormatException) {
                Curve.DEFAULT.copyOf()
            }
        }
        set(v) = sp.edit().putString("curve", v.joinToString(",")).apply()

    /** Turn the screen off after [idleSeconds] without activity. */
    var idleEnabled: Boolean
        get() = sp.getBoolean("idle_enabled", false)
        set(v) = sp.edit().putBoolean("idle_enabled", v).apply()

    var idleSeconds: Int
        get() = sp.getInt("idle_seconds", 60)
        set(v) = sp.edit().putInt("idle_seconds", v.coerceIn(1, 86400)).apply()

    /** true = really lock/turn off the screen (needs device admin), false = black overlay at brightness 0. */
    var useLock: Boolean
        get() = sp.getBoolean("use_lock", false)
        set(v) = sp.edit().putBoolean("use_lock", v).apply()

    /** Switch the screen back on when the proximity sensor reports something near. */
    var proxEnabled: Boolean
        get() = sp.getBoolean("prox_enabled", false)
        set(v) = sp.edit().putBoolean("prox_enabled", v).apply()

    /** "Near" means: sensor distance in cm is less than or equal to this value. */
    var proxThresholdCm: Float
        get() = sp.getFloat("prox_threshold", 1f)
        set(v) = sp.edit().putFloat("prox_threshold", v.coerceAtLeast(0f)).apply()
}
