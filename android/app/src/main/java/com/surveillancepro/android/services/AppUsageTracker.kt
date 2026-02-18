package com.surveillancepro.android.services

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.util.Calendar

class AppUsageTracker(private val context: Context) {

    fun getInstalledApps(): List<Map<String, String>> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return packages
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                mapOf(
                    "packageName" to it.packageName,
                    "appName" to (pm.getApplicationLabel(it)?.toString() ?: it.packageName),
                )
            }
    }

    fun getUsageToday(): List<Map<String, Any>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        if (stats.isNullOrEmpty()) return emptyList()

        val pm = context.packageManager
        return stats
            .filter { it.totalTimeInForeground > 60000 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(30)
            .map {
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0))?.toString()
                        ?: it.packageName
                } catch (_: Exception) { it.packageName }

                mapOf(
                    "packageName" to it.packageName,
                    "appName" to appName,
                    "minutesUsed" to (it.totalTimeInForeground / 60000),
                    "lastUsed" to it.lastTimeUsed,
                )
            }
    }
}
