package com.securitypro.android.scanner

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import com.securitypro.android.data.ThreatInfo
import com.securitypro.android.data.ThreatLevel
import com.securitypro.android.data.ThreatType
import java.util.Calendar

class DataLeakDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "DataLeakDetector"
        
        // Seuils de détection
        const val SUSPICIOUS_DATA_MB_PER_DAY = 50L // 50 MB/jour pour une app en arrière-plan
        const val SUSPICIOUS_BATTERY_PERCENT = 5 // 5% de batterie consommée
        const val SUSPICIOUS_BACKGROUND_TIME_HOURS = 2 // 2h en arrière-plan par jour
    }
    
    private val pm: PackageManager = context.packageManager
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES APPS QUI CONSOMMENT BEAUCOUP DE DONNÉES EN ARRIÈRE-PLAN
    // ══════════════════════════════════════════════════════════════════════════
    fun detectSuspiciousDataUsage(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
                    ?: return threats
                
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                
                // Analyser les dernières 24h
                val endTime = System.currentTimeMillis()
                val startTime = endTime - (24 * 60 * 60 * 1000)
                
                val installedApps = getInstalledApps()
                
                for (app in installedApps) {
                    val isSystemApp = (app.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                    if (isSystemApp) continue
                    
                    val uid = app.applicationInfo?.uid ?: continue
                    val appName = getAppName(app.packageName)
                    
                    // Vérifier si c'est un spyware connu qui utilise des données
                    val isKnownSpyware = MalwareDatabase.isSpyware(app.packageName) ||
                                         MalwareDatabase.isMalware(app.packageName) ||
                                         MalwareDatabase.isFakeParentalControl(app.packageName)
                    
                    // Vérifier la consommation de données mobile
                    try {
                        val mobileStats = networkStatsManager.queryDetailsForUid(
                            ConnectivityManager.TYPE_MOBILE,
                            telephonyManager.subscriberId,
                            startTime,
                            endTime,
                            uid
                        )
                        
                        var totalBytes = 0L
                        val bucket = NetworkStats.Bucket()
                        while (mobileStats.hasNextBucket()) {
                            mobileStats.getNextBucket(bucket)
                            totalBytes += bucket.txBytes + bucket.rxBytes
                        }
                        mobileStats.close()
                        
                        val totalMB = totalBytes / (1024 * 1024)
                        
                        // App suspecte qui envoie beaucoup de données
                        if (totalMB > SUSPICIOUS_DATA_MB_PER_DAY) {
                            val level = when {
                                isKnownSpyware -> ThreatLevel.CRITICAL
                                totalMB > 200 -> ThreatLevel.HIGH
                                totalMB > 100 -> ThreatLevel.MEDIUM
                                else -> ThreatLevel.LOW
                            }
                            
                            if (level >= ThreatLevel.MEDIUM || isKnownSpyware) {
                                threats.add(ThreatInfo(
                                    packageName = app.packageName,
                                    appName = appName,
                                    threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                                    threatLevel = level,
                                    description = "🔴 FUITE DE DONNÉES - ${totalMB}MB envoyés en 24h sur données mobiles",
                                    recommendation = if (isKnownSpyware) 
                                        "SPYWARE qui exfiltre vos données - DÉSINSTALLER" 
                                    else 
                                        "Cette app envoie beaucoup de données - Vérifier son utilité"
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        // Pas d'accès aux stats réseau pour cette app
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection data usage", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES APPS QUI TOURNENT EXCESSIVEMENT EN ARRIÈRE-PLAN
    // ══════════════════════════════════════════════════════════════════════════
    fun detectSuspiciousBackgroundActivity(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return threats
            
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000)
            
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            
            for (stats in usageStats) {
                val packageName = stats.packageName
                
                // Ignorer les apps système
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
                } catch (e: PackageManager.NameNotFoundException) {
                    continue
                }
                
                val appName = getAppName(packageName)
                val totalTimeMs = stats.totalTimeInForeground
                val totalTimeHours = totalTimeMs / (1000 * 60 * 60)
                
                val isKnownSpyware = MalwareDatabase.isSpyware(packageName) ||
                                     MalwareDatabase.isMalware(packageName)
                
                // Spyware connu avec activité en arrière-plan
                if (isKnownSpyware && totalTimeMs > 0) {
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SPYWARE,
                        threatLevel = ThreatLevel.CRITICAL,
                        description = "🔴 SPYWARE ACTIF - Fonctionne en arrière-plan",
                        recommendation = "DÉSINSTALLER IMMÉDIATEMENT - Cette app vous espionne"
                    ))
                }
                
                // App suspecte avec beaucoup d'activité
                if (MalwareDatabase.isSuspicious(packageName) && totalTimeHours > SUSPICIOUS_BACKGROUND_TIME_HOURS) {
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.HIGH,
                        description = "⚠️ Activité suspecte - ${totalTimeHours}h d'activité en 24h",
                        recommendation = "Cette app tourne beaucoup en arrière-plan"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection background activity", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES APPS QUI DRAINENT LA BATTERIE (signe de surveillance)
    // ══════════════════════════════════════════════════════════════════════════
    fun detectBatteryDrainingApps(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            // Vérifier si la batterie se décharge anormalement vite
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            // Si la batterie est basse et qu'il y a des spywares, alerter
            if (batteryLevel < 30) {
                val installedApps = getInstalledApps()
                
                for (app in installedApps) {
                    val isKnownSpyware = MalwareDatabase.isSpyware(app.packageName) ||
                                         MalwareDatabase.isMalware(app.packageName)
                    
                    if (isKnownSpyware) {
                        threats.add(ThreatInfo(
                            packageName = app.packageName,
                            appName = getAppName(app.packageName),
                            threatType = ThreatType.SPYWARE,
                            threatLevel = ThreatLevel.HIGH,
                            description = "🔋 DRAIN BATTERIE - Spyware actif (batterie: $batteryLevel%)",
                            recommendation = "Ce spyware draine votre batterie en vous surveillant"
                        ))
                    }
                }
            }
            
            // Vérifier les apps qui empêchent le téléphone de dormir
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isInteractive) {
                // Le téléphone devrait dormir mais des apps peuvent le garder éveillé
                // C'est un signe de surveillance
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection battery drain", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES APPS AVEC PERMISSIONS D'EXFILTRATION
    // ══════════════════════════════════════════════════════════════════════════
    fun detectDataExfiltrationCapableApps(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val installedApps = getInstalledApps()
            
            for (app in installedApps) {
                val isSystemApp = (app.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystemApp) continue
                
                val perms = app.requestedPermissions?.toSet() ?: continue
                val appName = getAppName(app.packageName)
                
                // Vérifier les combinaisons dangereuses pour l'exfiltration
                val hasInternet = perms.contains("android.permission.INTERNET")
                if (!hasInternet) continue
                
                val exfiltrationPerms = mutableListOf<String>()
                
                if (perms.contains("android.permission.READ_SMS")) exfiltrationPerms.add("SMS")
                if (perms.contains("android.permission.READ_CALL_LOG")) exfiltrationPerms.add("Appels")
                if (perms.contains("android.permission.READ_CONTACTS")) exfiltrationPerms.add("Contacts")
                if (perms.contains("android.permission.ACCESS_FINE_LOCATION")) exfiltrationPerms.add("GPS")
                if (perms.contains("android.permission.RECORD_AUDIO")) exfiltrationPerms.add("Micro")
                if (perms.contains("android.permission.CAMERA")) exfiltrationPerms.add("Caméra")
                if (perms.contains("android.permission.READ_EXTERNAL_STORAGE")) exfiltrationPerms.add("Fichiers")
                if (perms.contains("android.permission.READ_CALENDAR")) exfiltrationPerms.add("Calendrier")
                if (perms.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")) exfiltrationPerms.add("Écran")
                if (perms.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")) exfiltrationPerms.add("Notifications")
                
                // Si l'app a 4+ permissions d'exfiltration + Internet
                if (exfiltrationPerms.size >= 4) {
                    val isKnownSpyware = MalwareDatabase.isSpyware(app.packageName) ||
                                         MalwareDatabase.isFakeParentalControl(app.packageName)
                    
                    val level = when {
                        isKnownSpyware -> ThreatLevel.CRITICAL
                        exfiltrationPerms.size >= 6 -> ThreatLevel.HIGH
                        else -> ThreatLevel.MEDIUM
                    }
                    
                    threats.add(ThreatInfo(
                        packageName = app.packageName,
                        appName = appName,
                        threatType = if (isKnownSpyware) ThreatType.SPYWARE else ThreatType.DANGEROUS_PERMISSIONS,
                        threatLevel = level,
                        description = "🔴 CAPABLE D'EXFILTRER: ${exfiltrationPerms.joinToString(", ")}",
                        recommendation = "Cette app peut envoyer vos ${exfiltrationPerms.joinToString("/")} sur Internet"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection exfiltration", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES COMPORTEMENTS BIZARRES DU TÉLÉPHONE
    // ══════════════════════════════════════════════════════════════════════════
    fun detectWeirdPhoneBehavior(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            // Vérifier si le téléphone a des apps qui peuvent:
            // - Allumer le micro en secret
            // - Prendre des photos en secret
            // - Enregistrer l'écran
            // - Lire les messages
            
            val installedApps = getInstalledApps()
            
            for (app in installedApps) {
                val isSystemApp = (app.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystemApp) continue
                
                val perms = app.requestedPermissions?.toSet() ?: continue
                val appName = getAppName(app.packageName)
                
                // App qui peut enregistrer en secret (micro + pas d'UI visible)
                val canRecordAudio = perms.contains("android.permission.RECORD_AUDIO")
                val canUseCamera = perms.contains("android.permission.CAMERA")
                val hasOverlay = perms.contains("android.permission.SYSTEM_ALERT_WINDOW")
                val hasAccessibility = perms.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")
                val hasInternet = perms.contains("android.permission.INTERNET")
                val hasBackground = perms.contains("android.permission.FOREGROUND_SERVICE") ||
                                   perms.contains("android.permission.RECEIVE_BOOT_COMPLETED")
                
                // Combinaison très suspecte: peut enregistrer + envoyer + tourner en arrière-plan
                if ((canRecordAudio || canUseCamera) && hasInternet && hasBackground) {
                    val isKnownSpyware = MalwareDatabase.isSpyware(app.packageName)
                    
                    if (isKnownSpyware || hasAccessibility || hasOverlay) {
                        val capabilities = mutableListOf<String>()
                        if (canRecordAudio) capabilities.add("écouter")
                        if (canUseCamera) capabilities.add("filmer")
                        if (hasAccessibility) capabilities.add("voir l'écran")
                        if (hasOverlay) capabilities.add("overlay")
                        
                        threats.add(ThreatInfo(
                            packageName = app.packageName,
                            appName = appName,
                            threatType = ThreatType.SPYWARE,
                            threatLevel = if (isKnownSpyware) ThreatLevel.CRITICAL else ThreatLevel.HIGH,
                            description = "🎤📹 SURVEILLANCE POSSIBLE - Peut ${capabilities.joinToString("/")} en secret",
                            recommendation = "Cette app peut vous surveiller sans que vous le sachiez"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection weird behavior", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // ANALYSE COMPLÈTE DES FUITES DE DONNÉES
    // ══════════════════════════════════════════════════════════════════════════
    fun runFullDataLeakAnalysis(): List<ThreatInfo> {
        val allThreats = mutableListOf<ThreatInfo>()
        
        Log.d(TAG, "Démarrage analyse des fuites de données...")
        
        allThreats.addAll(detectSuspiciousDataUsage())
        allThreats.addAll(detectSuspiciousBackgroundActivity())
        allThreats.addAll(detectBatteryDrainingApps())
        allThreats.addAll(detectDataExfiltrationCapableApps())
        allThreats.addAll(detectWeirdPhoneBehavior())
        
        // Dédoublonner
        val uniqueThreats = allThreats.distinctBy { "${it.packageName}_${it.description.take(50)}" }
        
        Log.d(TAG, "Analyse terminée: ${uniqueThreats.size} fuites/comportements suspects détectés")
        
        return uniqueThreats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ══════════════════════════════════════════════════════════════════════════
    
    private fun getInstalledApps(): List<android.content.pm.PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}
