package com.example.luxcurve

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Foreground service that
 *  1. maps the ambient light sensor to a screen brightness through the user's curve,
 *  2. turns the screen off after a user defined idle time,
 *  3. turns it back on when the proximity sensor reports something nearer than a user defined distance.
 */
class BrightnessService : Service(), SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var sensors: SensorManager
    private lateinit var windows: WindowManager
    private lateinit var power: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private var lightSensor: Sensor? = null
    private var proxSensor: Sensor? = null
    private var lightRegistered = false

    // Smoothed log10(lux); -1 means "no reading yet".
    private var smoothedLog = -1f
    private var lastEventTime = 0L
    private var lastLevel = -1

    private var off = false
    private var blackOverlay: View? = null
    private var touchProbe: View? = null
    private var cpuLock: PowerManager.WakeLock? = null

    private val idleRunnable = Runnable { goOff() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        onPrefsChanged()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Screen switched off by the system / power button: treat it like our own "off".
                Intent.ACTION_SCREEN_OFF -> if (!off) markOff()
                // Screen switched on by something else (power button, notification, ...).
                Intent.ACTION_SCREEN_ON -> if (off) {
                    removeBlackOverlay()
                    resumeActive()
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        power = getSystemService(POWER_SERVICE) as PowerManager
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        proxSensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        startInForeground()

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenReceiver, filter)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)

        registerLight()
        proxSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        ensureTouchProbe()
        scheduleIdle()
        Live.running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(idleRunnable)
        sensors.unregisterListener(this)
        unregisterReceiver(screenReceiver)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        removeBlackOverlay()
        removeTouchProbe()
        releaseCpuLock()
        Live.running = false
        Live.screenOff = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Brightness service", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Lux Curve is running")
            .setContentText("Setting brightness from the light sensor")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    // ---------------------------------------------------------------- sensors

    private fun registerLight() {
        val s = lightSensor ?: return
        if (!lightRegistered) {
            lightRegistered = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterLight() {
        val s = lightSensor ?: return
        if (lightRegistered) {
            sensors.unregisterListener(this, s)
            lightRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> onLight(event.values[0])
            Sensor.TYPE_PROXIMITY -> onProximity(event.values[0], event.sensor.maximumRange)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- brightness

    private fun onLight(lux: Float) {
        if (off) return
        Live.lux = lux
        val now = SystemClock.elapsedRealtime()
        val target = Curve.logLux(lux)
        if (smoothedLog < 0f) {
            smoothedLog = target
        } else {
            val dt = (now - lastEventTime) / 1000f
            val alpha = 1f - exp(-dt / SMOOTHING_SECONDS)
            smoothedLog += (target - smoothedLog) * alpha
        }
        lastEventTime = now
        applyBrightness(false)
    }

    private fun applyBrightness(force: Boolean) {
        if (smoothedLog < 0f) return
        val percent = Curve.percentForLog(smoothedLog, prefs.curve)
        Live.percent = percent
        if (!Settings.System.canWrite(this)) return
        val level = (percent / 100f * 255f).roundToInt().coerceIn(1, 255)
        if (!force && level == lastLevel) return
        try {
            if (lastLevel < 0 || force) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            lastLevel = level
        } catch (e: SecurityException) {
            // Permission was revoked while running; the settings screen shows the status.
        }
    }

    private fun onPrefsChanged() {
        ensureTouchProbe()
        if (!off) {
            scheduleIdle()
            applyBrightness(true)
        }
    }

    // ---------------------------------------------------------------- proximity

    private fun onProximity(value: Float, maxRange: Float) {
        Live.proximity = value
        // Many sensors only report 0 (near) and maxRange (far); "far" is never "near".
        val near = value < maxRange && value <= prefs.proxThresholdCm
        if (!near) return
        if (off) {
            if (prefs.proxEnabled) wake()
        } else if (prefs.proxEnabled) {
            scheduleIdle() // something is in front of the screen: counts as activity
        }
    }

    // ---------------------------------------------------------------- screen off / on

    private fun scheduleIdle() {
        handler.removeCallbacks(idleRunnable)
        if (!off && prefs.idleEnabled) {
            handler.postDelayed(idleRunnable, prefs.idleSeconds * 1000L)
        }
    }

    private fun goOff() {
        if (off || !prefs.idleEnabled) return
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (prefs.useLock && dpm.isAdminActive(admin)) {
            markOff()
            dpm.lockNow()
        } else if (Settings.canDrawOverlays(this)) {
            showBlackOverlay()
            markOff()
        }
    }

    private fun markOff() {
        off = true
        Live.screenOff = true
        handler.removeCallbacks(idleRunnable)
        unregisterLight()
        // Keep the CPU awake so proximity events still arrive while the screen is off.
        if (prefs.proxEnabled) acquireCpuLock()
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        if (!off) return
        removeBlackOverlay()
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE
        power.newWakeLock(flags, "luxcurve:wake").acquire(3000L)
        resumeActive()
    }

    private fun resumeActive() {
        off = false
        Live.screenOff = false
        releaseCpuLock()
        smoothedLog = -1f
        lastLevel = -1
        registerLight()
        scheduleIdle()
    }

    private fun acquireCpuLock() {
        val lock: PowerManager.WakeLock = cpuLock
            ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luxcurve:proximity")
        cpuLock = lock
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseCpuLock() {
        val lock = cpuLock
        if (lock != null && lock.isHeld) lock.release()
    }

    // ---------------------------------------------------------------- overlays

    /** Full-screen black window with brightness override 0. Tapping it wakes the screen. */
    private fun showBlackOverlay() {
        if (blackOverlay != null) return
        val view = View(this)
        view.setBackgroundColor(Color.BLACK)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) wake()
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            windows.addView(view, lp)
            blackOverlay = view
        } catch (e: Exception) {
            blackOverlay = null
        }
    }

    private fun removeBlackOverlay() {
        val view = blackOverlay ?: return
        blackOverlay = null
        try {
            windows.removeView(view)
        } catch (e: IllegalArgumentException) {
            // already gone
        }
    }

    /**
     * Invisible 1x1 window that receives ACTION_OUTSIDE for every touch anywhere on the screen,
     * which is how we notice user activity in other apps without an accessibility service.
     */
    private fun ensureTouchProbe() {
        val want = prefs.idleEnabled && Settings.canDrawOverlays(this)
        if (want && touchProbe == null) {
            val view = View(this)
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) scheduleIdle()
                false
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.START or Gravity.TOP
            try {
                windows.addView(view, lp)
                touchProbe = view
            } catch (e: Exception) {
                touchProbe = null
            }
        } else if (!want && touchProbe != null) {
            removeTouchProbe()
        }
    }

    private fun removeTouchProbe() {
        val view = touchProbe ?: return
        touchProbe = null
        try {
            windows.removeView(view)
        } catch (e: IllegalArgumentException) {
            // already gone
        }
    }

    companion object {
        private const val CHANNEL = "lux_curve_service"
        private const val SMOOTHING_SECONDS = 2f
    }
}
