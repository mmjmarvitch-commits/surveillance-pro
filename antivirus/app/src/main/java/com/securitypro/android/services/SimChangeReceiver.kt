package com.securitypro.android.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class SimChangeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SimChangeReceiver"
        private const val CHANNEL_ID = "sim_alert_channel"
        private const val NOTIFICATION_ID = 4001
        private const val PREFS_NAME = "sim_prefs"
        private const val KEY_SIM_ID = "last_sim_id"
        private const val KEY_ALERT_ENABLED = "sim_alert_enabled"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
            val state = intent.getStringExtra("ss")
            if (state == "READY") {
                checkSimChange(context)
            }
        }
    }
    
    private fun checkSimChange(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        if (!prefs.getBoolean(KEY_ALERT_ENABLED, true)) return
        
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val currentSimId = telephonyManager.simSerialNumber ?: telephonyManager.subscriberId ?: return
            
            val lastSimId = prefs.getString(KEY_SIM_ID, null)
            
            if (lastSimId == null) {
                // Première fois, sauvegarder l'ID
                prefs.edit().putString(KEY_SIM_ID, currentSimId).apply()
                Log.d(TAG, "SIM enregistrée: $currentSimId")
            } else if (lastSimId != currentSimId) {
                // SIM changée !
                Log.w(TAG, "⚠️ SIM CHANGÉE ! Ancienne: $lastSimId, Nouvelle: $currentSimId")
                showSimChangeAlert(context)
                
                // Mettre à jour avec la nouvelle SIM
                prefs.edit().putString(KEY_SIM_ID, currentSimId).apply()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission manquante pour lire SIM", e)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur vérification SIM", e)
        }
    }
    
    private fun showSimChangeAlert(context: Context) {
        createNotificationChannel(context)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("⚠️ ALERTE SÉCURITÉ")
            .setContentText("La carte SIM a été changée !")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("La carte SIM de cet appareil a été remplacée. Si ce n'est pas vous, quelqu'un pourrait avoir accès à votre téléphone."))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()
        
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alertes SIM",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertes de changement de carte SIM"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

class SimAlertManager(context: Context) {
    
    private val prefs = context.getSharedPreferences("sim_prefs", Context.MODE_PRIVATE)
    
    fun isEnabled(): Boolean = prefs.getBoolean("sim_alert_enabled", true)
    
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("sim_alert_enabled", enabled).apply()
    }
    
    fun resetSimId() {
        prefs.edit().remove("last_sim_id").apply()
    }
}
