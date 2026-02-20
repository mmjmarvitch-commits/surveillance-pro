package com.securitypro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securitypro.android.data.ScanResult
import com.securitypro.android.data.ThreatLevel
import com.securitypro.android.scanner.AppScanner
import kotlinx.coroutines.*

class ScanService : Service() {
    
    companion object {
        private const val TAG = "ScanService"
        private const val CHANNEL_ID = "scan_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_QUICK_SCAN = "com.securitypro.QUICK_SCAN"
        const val ACTION_FULL_SCAN = "com.securitypro.FULL_SCAN"
        const val EXTRA_RESULT = "scan_result"
        
        var lastScanResult: ScanResult? = null
            private set
        
        var isScanning = false
            private set
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var scanner: AppScanner
    
    override fun onCreate() {
        super.onCreate()
        scanner = AppScanner(this)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Préparation du scan..."))
        
        when (intent?.action) {
            ACTION_FULL_SCAN -> runFullScan()
            ACTION_QUICK_SCAN -> runQuickScan()
            else -> runQuickScan()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        isScanning = false
    }
    
    private fun runQuickScan() {
        isScanning = true
        scope.launch {
            try {
                updateNotification("Scan rapide en cours...")
                val result = scanner.quickScan()
                lastScanResult = result
                handleResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur scan rapide", e)
            } finally {
                isScanning = false
                stopSelf()
            }
        }
    }
    
    private fun runFullScan() {
        isScanning = true
        scope.launch {
            try {
                updateNotification("Scan complet en cours...")
                val result = scanner.fullScan()
                lastScanResult = result
                handleResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Erreur scan complet", e)
            } finally {
                isScanning = false
                stopSelf()
            }
        }
    }
    
    private fun handleResult(result: ScanResult) {
        val criticalCount = result.threats.count { it.threatLevel >= ThreatLevel.HIGH }
        
        if (criticalCount > 0) {
            showThreatNotification(criticalCount)
        } else {
            updateNotification("Scan terminé - Aucune menace")
        }
        
        sendBroadcast(Intent("com.securitypro.SCAN_COMPLETE").apply {
            putExtra("threats_count", result.threats.size)
            putExtra("critical_count", criticalCount)
            putExtra("apps_scanned", result.scannedApps)
        })
        
        Log.d(TAG, "Scan terminé: ${result.scannedApps} apps, ${result.threats.size} menaces")
    }
    
    private fun showThreatNotification(count: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Menaces détectées")
            .setContentText("$count menace(s) critique(s) trouvée(s)")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scan de sécurité",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications de scan antivirus"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
