package com.securitypro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Service
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.securitypro.android.data.ThreatLevel
import com.securitypro.android.scanner.AppScanner
import kotlinx.coroutines.*

class RealTimeProtectionService : Service() {
    
    companion object {
        private const val TAG = "RealTimeProtection"
        private const val CHANNEL_ID = "protection_channel"
        private const val NOTIFICATION_ID = 2001
        
        var isRunning = false
            private set
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var scanner: AppScanner
    
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    Log.d(TAG, "Nouvelle app: $pkg")
                    scanNewApp(pkg)
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    Log.d(TAG, "App mise à jour: $pkg")
                    scanNewApp(pkg)
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        scanner = AppScanner(this)
        createNotificationChannel()
        registerPackageReceiver()
        isRunning = true
        Log.d(TAG, "Protection temps réel activée")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur unregister", e)
        }
        Log.d(TAG, "Protection temps réel désactivée")
    }
    
    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }
    
    private fun scanNewApp(packageName: String) {
        scope.launch {
            try {
                val threats = scanner.scanSingleApp(packageName)
                if (threats.isNotEmpty()) {
                    val maxThreat = threats.maxByOrNull { it.threatLevel.ordinal }
                    if (maxThreat != null && maxThreat.threatLevel >= ThreatLevel.HIGH) {
                        showThreatAlert(maxThreat.appName, maxThreat.description)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur scan $packageName", e)
            }
        }
    }
    
    private fun showThreatAlert(appName: String, description: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Menace détectée")
            .setContentText("$appName: $description")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$appName\n$description"))
            .build()
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Protection en temps réel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Protection active contre les menaces"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Pro")
            .setContentText("Protection active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
