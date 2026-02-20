package com.securitypro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.securitypro.android.services.RealTimeProtectionService

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed - démarrage protection")
            
            val prefs = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
            val protectionEnabled = prefs.getBoolean("protection_enabled", true)
            
            if (protectionEnabled) {
                val serviceIntent = Intent(context, RealTimeProtectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
