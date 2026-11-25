package com.example.luxcurve

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the service after a reboot if it was switched on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).enabled) {
            context.startForegroundService(Intent(context, BrightnessService::class.java))
        }
    }
}
