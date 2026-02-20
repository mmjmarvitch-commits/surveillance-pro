package com.securitypro.android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

data class ScanHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val scanType: ScanType,
    val totalApps: Int,
    val scannedApps: Int,
    val threatsFound: Int,
    val threatNames: List<String>,
    val durationMs: Long,
    val systemSecure: Boolean
) {
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
        return sdf.format(Date(timestamp))
    }
    
    fun getFormattedDuration(): String {
        val seconds = durationMs / 1000
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
    }
}

class ScanHistoryManager(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "scan_history"
        private const val KEY_HISTORY = "history_entries"
        private const val MAX_ENTRIES = 50
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    fun saveEntry(entry: ScanHistoryEntry) {
        val history = getHistory().toMutableList()
        history.add(0, entry)
        
        // Garder seulement les 50 derniers
        val trimmed = history.take(MAX_ENTRIES)
        
        val json = gson.toJson(trimmed)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
    
    fun getHistory(): List<ScanHistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ScanHistoryEntry>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun getLastScan(): ScanHistoryEntry? {
        return getHistory().firstOrNull()
    }
    
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
    
    fun getTotalScans(): Int = getHistory().size
    
    fun getTotalThreatsFound(): Int = getHistory().sumOf { it.threatsFound }
    
    fun getAverageThreatsPerScan(): Float {
        val history = getHistory()
        if (history.isEmpty()) return 0f
        return history.sumOf { it.threatsFound }.toFloat() / history.size
    }
}
