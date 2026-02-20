package com.securitypro.android.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SignatureUpdate(
    val version: Int,
    val malwarePackages: List<String>,
    val spywarePackages: List<String>,
    val fakeParentalPackages: List<String>,
    val suspiciousPatterns: List<String>,
    val timestamp: Long
)

class SignatureUpdater(private val context: Context) {
    
    companion object {
        private const val TAG = "SignatureUpdater"
        private const val PREFS_NAME = "signature_prefs"
        private const val KEY_VERSION = "signature_version"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_CUSTOM_MALWARE = "custom_malware"
        private const val KEY_CUSTOM_SPYWARE = "custom_spyware"
        private const val KEY_CUSTOM_FAKE_PARENTAL = "custom_fake_parental"
        
        // URL du serveur de signatures (à configurer)
        private const val SIGNATURES_URL = "https://your-server.com/api/security/signatures"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Signatures personnalisées ajoutées par les mises à jour
    private var customMalware = mutableSetOf<String>()
    private var customSpyware = mutableSetOf<String>()
    private var customFakeParental = mutableSetOf<String>()
    
    init {
        loadCustomSignatures()
    }
    
    private fun loadCustomSignatures() {
        customMalware = prefs.getStringSet(KEY_CUSTOM_MALWARE, emptySet())?.toMutableSet() ?: mutableSetOf()
        customSpyware = prefs.getStringSet(KEY_CUSTOM_SPYWARE, emptySet())?.toMutableSet() ?: mutableSetOf()
        customFakeParental = prefs.getStringSet(KEY_CUSTOM_FAKE_PARENTAL, emptySet())?.toMutableSet() ?: mutableSetOf()
    }
    
    private fun saveCustomSignatures() {
        prefs.edit()
            .putStringSet(KEY_CUSTOM_MALWARE, customMalware)
            .putStringSet(KEY_CUSTOM_SPYWARE, customSpyware)
            .putStringSet(KEY_CUSTOM_FAKE_PARENTAL, customFakeParental)
            .apply()
    }
    
    fun getCurrentVersion(): Int = prefs.getInt(KEY_VERSION, 1)
    
    fun getLastUpdateTime(): Long = prefs.getLong(KEY_LAST_UPDATE, 0)
    
    fun getLastUpdateFormatted(): String {
        val lastUpdate = getLastUpdateTime()
        if (lastUpdate == 0L) return "Jamais"
        val diff = System.currentTimeMillis() - lastUpdate
        val hours = diff / (1000 * 60 * 60)
        val days = hours / 24
        return when {
            days > 0 -> "Il y a $days jour(s)"
            hours > 0 -> "Il y a $hours heure(s)"
            else -> "Récemment"
        }
    }
    
    suspend fun checkForUpdates(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(SIGNATURES_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Current-Version", getCurrentVersion().toString())
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                
                val serverVersion = json.getInt("version")
                val currentVersion = getCurrentVersion()
                
                if (serverVersion > currentVersion) {
                    // Nouvelles signatures disponibles
                    val update = parseSignatureUpdate(json)
                    applyUpdate(update)
                    
                    UpdateResult.Success(
                        newVersion = serverVersion,
                        newSignatures = update.malwarePackages.size + 
                                       update.spywarePackages.size + 
                                       update.fakeParentalPackages.size
                    )
                } else {
                    UpdateResult.AlreadyUpToDate
                }
            } else {
                UpdateResult.Error("Erreur serveur: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur mise à jour signatures", e)
            UpdateResult.Error(e.message ?: "Erreur inconnue")
        }
    }
    
    private fun parseSignatureUpdate(json: JSONObject): SignatureUpdate {
        return SignatureUpdate(
            version = json.getInt("version"),
            malwarePackages = json.optJSONArray("malware")?.toStringList() ?: emptyList(),
            spywarePackages = json.optJSONArray("spyware")?.toStringList() ?: emptyList(),
            fakeParentalPackages = json.optJSONArray("fakeParental")?.toStringList() ?: emptyList(),
            suspiciousPatterns = json.optJSONArray("patterns")?.toStringList() ?: emptyList(),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
    
    private fun applyUpdate(update: SignatureUpdate) {
        customMalware.addAll(update.malwarePackages)
        customSpyware.addAll(update.spywarePackages)
        customFakeParental.addAll(update.fakeParentalPackages)
        
        saveCustomSignatures()
        
        prefs.edit()
            .putInt(KEY_VERSION, update.version)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
        
        Log.d(TAG, "Signatures mises à jour: v${update.version}")
    }
    
    // Méthodes pour vérifier les signatures (utilisées par MalwareDatabase)
    fun isCustomMalware(packageName: String): Boolean {
        return customMalware.any { packageName.lowercase().contains(it.lowercase()) }
    }
    
    fun isCustomSpyware(packageName: String): Boolean {
        return customSpyware.any { packageName.lowercase().contains(it.lowercase()) }
    }
    
    fun isCustomFakeParental(packageName: String): Boolean {
        return customFakeParental.any { packageName.lowercase().contains(it.lowercase()) }
    }
    
    fun getTotalCustomSignatures(): Int {
        return customMalware.size + customSpyware.size + customFakeParental.size
    }
    
    private fun JSONArray.toStringList(): List<String> {
        val list = mutableListOf<String>()
        for (i in 0 until length()) {
            list.add(getString(i))
        }
        return list
    }
}

sealed class UpdateResult {
    data class Success(val newVersion: Int, val newSignatures: Int) : UpdateResult()
    object AlreadyUpToDate : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
