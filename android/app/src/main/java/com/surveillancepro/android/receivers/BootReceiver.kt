package com.surveillancepro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val storage = DeviceStorage.getInstance(context)
        if (storage.hasAccepted && storage.deviceToken != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ApiClient.getInstance(storage).sendEvent("device_boot", mapOf(
                        "timestamp" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date())
                    ))
                } catch (_: Exception) {}
            }
        }
    }
}
