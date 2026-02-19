package com.surveillancepro.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(context)

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val appName = resolveAppName(context, packageName)

                queue.enqueue(if (isUpdate) "app_updated" else "app_installed", mapOf(
                    "packageName" to packageName,
                    "appName" to appName,
                    "timestamp" to timestamp,
                ))
                Log.d(TAG, "${if (isUpdate) "Updated" else "Installed"}: $appName ($packageName)")
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (isUpdate) return

                queue.enqueue("app_removed", mapOf(
                    "packageName" to packageName,
                    "timestamp" to timestamp,
                ))
                Log.d(TAG, "Removed: $packageName")
            }
        }
    }

    private fun resolveAppName(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo)?.toString() ?: packageName
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    companion object {
        private const val TAG = "AppInstallReceiver"
    }
}
