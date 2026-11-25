package com.example.luxcurve

import android.app.admin.DeviceAdminReceiver

/** Only needed so the app may call DevicePolicyManager.lockNow() (optional "real screen off" mode). */
class AdminReceiver : DeviceAdminReceiver()
