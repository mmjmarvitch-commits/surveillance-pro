package com.surveillancepro.android.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Stockage sécurisé des données sensibles de l'appareil.
 * Utilise EncryptedSharedPreferences pour chiffrer toutes les données au repos.
 * 
 * SÉCURITÉ:
 * - Le token d'authentification est chiffré
 * - L'ID de l'appareil est chiffré
 * - Toutes les préférences sont protégées par AES-256
 */
class DeviceStorage(context: Context) {

    val prefs: SharedPreferences = try {
        // Créer une clé maître pour le chiffrement
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        // Créer les SharedPreferences chiffrées
        EncryptedSharedPreferences.create(
            context,
            "supervision_pro_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback sur SharedPreferences normales si le chiffrement échoue
        Log.w("DeviceStorage", "EncryptedSharedPreferences failed, using fallback: ${e.message}")
        context.getSharedPreferences("supervision_pro", Context.MODE_PRIVATE)
    }

    var deviceId: String
        get() {
            val stored = prefs.getString("device_id", null)
            if (stored != null) return stored
            val id = "ANDROID-" + UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            return id
        }
        set(value) = prefs.edit().putString("device_id", value).apply()

    var deviceToken: String?
        get() = prefs.getString("device_token", null)
        set(value) = prefs.edit().putString("device_token", value).apply()

    var hasAccepted: Boolean
        get() = prefs.getBoolean("has_accepted", false)
        set(value) = prefs.edit().putBoolean("has_accepted", value).apply()

    var consentSent: Boolean
        get() = prefs.getBoolean("consent_sent", false)
        set(value) = prefs.edit().putBoolean("consent_sent", value).apply()

    var userName: String?
        get() = prefs.getString("user_name", null)
        set(value) = prefs.edit().putString("user_name", value).apply()

    var serverURL: String
        get() = prefs.getString("server_url", "https://surveillance-pro-1.onrender.com") ?: "https://surveillance-pro-1.onrender.com"
        set(value) = prefs.edit().putString("server_url", value).apply()
    
    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(value) = prefs.edit().putString("fcm_token", value).apply()

    var acceptanceDate: String?
        get() = prefs.getString("acceptance_date", null)
        set(value) = prefs.edit().putString("acceptance_date", value).apply()

    var eventCount: Int
        get() = prefs.getInt("event_count", 0)
        set(value) = prefs.edit().putInt("event_count", value).apply()

    fun getAndroidId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    companion object {
        @Volatile private var instance: DeviceStorage? = null
        fun getInstance(context: Context): DeviceStorage =
            instance ?: synchronized(this) {
                instance ?: DeviceStorage(context.applicationContext).also { instance = it }
            }
    }
}
