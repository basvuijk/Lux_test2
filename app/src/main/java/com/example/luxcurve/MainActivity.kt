package com.example.luxcurve

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val handler = Handler(Looper.getMainLooper())
    private var loading = true

    private lateinit var swEnable: Switch
    private lateinit var swIdle: Switch
    private lateinit var swLock: Switch
    private lateinit var swProx: Switch
    private lateinit var etIdle: EditText
    private lateinit var etProx: EditText
    private lateinit var tvLive: TextView
    private lateinit var tvProxInfo: TextView
    private lateinit var btnWrite: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnAdmin: Button
    private lateinit var curveView: CurveView

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, AdminReceiver::class.java)

        swEnable = findViewById(R.id.swEnable)
        swIdle = findViewById(R.id.swIdle)
        swLock = findViewById(R.id.swLock)
        swProx = findViewById(R.id.swProx)
        etIdle = findViewById(R.id.etIdle)
        etProx = findViewById(R.id.etProx)
        tvLive = findViewById(R.id.tvLive)
        tvProxInfo = findViewById(R.id.tvProxInfo)
        btnWrite = findViewById(R.id.btnWrite)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAdmin = findViewById(R.id.btnAdmin)
        curveView = findViewById(R.id.curveView)

        // Initial values (listeners are attached afterwards / guarded by `loading`).
        swEnable.isChecked = prefs.enabled
        swIdle.isChecked = prefs.idleEnabled
        swLock.isChecked = prefs.useLock
        swProx.isChecked = prefs.proxEnabled
        etIdle.setText(prefs.idleSeconds.toString())
        etProx.setText(formatCm(prefs.proxThresholdCm))
        curveView.points = prefs.curve
        curveView.onCurveChanged = { prefs.curve = it }

        swEnable.setOnCheckedChangeListener { _, on -> if (!loading) setServiceEnabled(on) }
        swIdle.setOnCheckedChangeListener { _, on -> if (!loading) prefs.idleEnabled = on }
        swProx.setOnCheckedChangeListener { _, on -> if (!loading) prefs.proxEnabled = on }
        swLock.setOnCheckedChangeListener { _, on ->
            if (!loading) {
                prefs.useLock = on
                if (on && !dpm.isAdminActive(admin)) requestAdmin()
            }
        }
        etIdle.onTextChanged { text ->
            if (!loading) text.toIntOrNull()?.let { if (it > 0) prefs.idleSeconds = it }
        }
        etProx.onTextChanged { text ->
            if (!loading) text.replace(',', '.').toFloatOrNull()?.let { prefs.proxThresholdCm = it }
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            prefs.curve = Curve.DEFAULT.copyOf()
            curveView.points = prefs.curve
        }
        btnWrite.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnAdmin.setOnClickListener {
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin) else requestAdmin()
        }

        loading = false

        // Make sure the service runs again after the app was updated or the process was killed.
        if (prefs.enabled) startForegroundService(Intent(this, BrightnessService::class.java))
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun setServiceEnabled(on: Boolean) {
        prefs.enabled = on
        val intent = Intent(this, BrightnessService::class.java)
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            startForegroundService(intent)
        } else {
            stopService(intent)
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Needed to really switch the screen off (lock the device)."
            )
        startActivity(intent)
    }

    private fun refresh() {
        // Live readings.
        val state = StringBuilder()
        state.append("Service    : ").append(if (Live.running) "running" else "stopped")
        if (Live.screenOff) state.append(" (screen off)")
        state.append('\n')
        state.append("Light      : ").append(number(Live.lux, 1)).append(" lx\n")
        state.append("Brightness : ").append(number(Live.percent, 0)).append(" %\n")
        state.append("Proximity  : ").append(number(Live.proximity, 1)).append(" cm")
        tvLive.text = state.toString()
        curveView.liveLog = if (Live.lux >= 0f) Curve.logLux(Live.lux) else -1f

        // Permission buttons.
        btnWrite.text = if (Settings.System.canWrite(this)) {
            "✓ Modify system settings (brightness)"
        } else {
            "Grant: modify system settings (brightness)"
        }
        btnOverlay.text = if (Settings.canDrawOverlays(this)) {
            "✓ Display over other apps (screen off, activity detection)"
        } else {
            "Grant: display over other apps (screen off, activity detection)"
        }
        btnAdmin.text = if (dpm.isAdminActive(admin)) {
            "✓ Device admin active (tap to remove)"
        } else {
            "Optional: device admin (for a real screen off)"
        }

        // Proximity sensor info.
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        tvProxInfo.text = if (prox == null) {
            "This device has no proximity sensor."
        } else {
            "Sensor range: 0 - " + formatCm(prox.maximumRange) + " cm. Many sensors only report " +
                "\"near\" (about 0) or \"far\" (maximum); watch the live reading above and pick a " +
                "value between."
        }
    }

    private fun number(value: Float, decimals: Int): String =
        if (value < 0f) "-" else String.format(Locale.US, "%." + decimals + "f", value)

    private fun formatCm(value: Float): String =
        String.format(Locale.US, "%.1f", value)
}

private fun EditText.onTextChanged(block: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            block(s?.toString() ?: "")
        }
    })
}
