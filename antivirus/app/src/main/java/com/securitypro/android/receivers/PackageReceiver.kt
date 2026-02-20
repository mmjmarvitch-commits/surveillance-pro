package com.securitypro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PackageReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PackageReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                Log.d(TAG, "Nouvelle app installée: $packageName")
                context.sendBroadcast(Intent("com.securitypro.NEW_APP").apply {
                    putExtra("package_name", packageName)
                })
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "App mise à jour: $packageName")
            }
        }
    }
}
