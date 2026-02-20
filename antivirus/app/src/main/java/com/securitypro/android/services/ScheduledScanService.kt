package com.securitypro.android.services

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securitypro.android.data.ScanHistoryEntry
import com.securitypro.android.data.ScanHistoryManager
import com.securitypro.android.data.ScanType
import com.securitypro.android.scanner.AppScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduledScanReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ScheduledScan"
        const val ACTION_SCHEDULED_SCAN = "com.securitypro.SCHEDULED_SCAN"
        private const val CHANNEL_ID = "scheduled_scan_channel"
        private const val NOTIFICATION_ID = 3001
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SCHEDULED_SCAN) {
            Log.d(TAG, "Démarrage scan programmé")
            performScheduledScan(context)
        }
    }
    
    private fun performScheduledScan(context: Context) {
        createNotificationChannel(context)
        showScanningNotification(context)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scanner = AppScanner(context)
                val result = scanner.quickScan()
                
                // Sauvegarder dans l'historique
                val historyManager = ScanHistoryManager(context)
                historyManager.saveEntry(
                    ScanHistoryEntry(
                        scanType = ScanType.QUICK,
                        totalApps = result.totalApps,
                        scannedApps = result.scannedApps,
                        threatsFound = result.threats.size,
                        threatNames = result.threats.map { it.appName },
                        durationMs = result.scanDurationMs,
                        systemSecure = result.systemStatus?.isSecure() ?: true
                    )
                )
                
                // Notification de résultat
                showResultNotification(context, result.threats.size)
                
                Log.d(TAG, "Scan programmé terminé: ${result.threats.size} menaces")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur scan programmé", e)
            }
        }
    }
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scan programmé",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications de scan automatique"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun showScanningNotification(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Security Pro")
            .setContentText("Scan de sécurité en cours...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun showResultNotification(context: Context, threatsCount: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (threatsCount > 0) "⚠️ Menaces détectées" else "✓ Appareil sécurisé")
            .setContentText(
                if (threatsCount > 0) "$threatsCount menace(s) trouvée(s) - Ouvrez l'app"
                else "Aucune menace détectée"
            )
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(if (threatsCount > 0) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
}

class ScanScheduler(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "scan_scheduler"
        private const val KEY_ENABLED = "scheduled_enabled"
        private const val KEY_INTERVAL = "scan_interval"
        private const val REQUEST_CODE = 12345
        
        // Intervalles disponibles
        const val INTERVAL_DAILY = 24 * 60 * 60 * 1000L
        const val INTERVAL_WEEKLY = 7 * INTERVAL_DAILY
        const val INTERVAL_12H = 12 * 60 * 60 * 1000L
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    
    fun getInterval(): Long = prefs.getLong(KEY_INTERVAL, INTERVAL_DAILY)
    
    fun getIntervalName(): String {
        return when (getInterval()) {
            INTERVAL_12H -> "Toutes les 12h"
            INTERVAL_DAILY -> "Quotidien"
            INTERVAL_WEEKLY -> "Hebdomadaire"
            else -> "Quotidien"
        }
    }
    
    fun enable(interval: Long = INTERVAL_DAILY) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_INTERVAL, interval)
            .apply()
        
        scheduleNextScan(interval)
    }
    
    fun disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        cancelScheduledScan()
    }
    
    fun scheduleNextScan(interval: Long = getInterval()) {
        val intent = Intent(context, ScheduledScanReceiver::class.java).apply {
            action = ScheduledScanReceiver.ACTION_SCHEDULED_SCAN
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = System.currentTimeMillis() + interval
        
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
    }
    
    private fun cancelScheduledScan() {
        val intent = Intent(context, ScheduledScanReceiver::class.java).apply {
            action = ScheduledScanReceiver.ACTION_SCHEDULED_SCAN
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
    }
}
