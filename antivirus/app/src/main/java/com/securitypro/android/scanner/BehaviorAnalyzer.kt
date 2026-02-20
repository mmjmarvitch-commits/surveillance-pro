package com.securitypro.android.scanner

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.securitypro.android.data.ThreatInfo
import com.securitypro.android.data.ThreatLevel
import com.securitypro.android.data.ThreatType
import java.io.File

class BehaviorAnalyzer(private val context: Context) {
    
    companion object {
        private const val TAG = "BehaviorAnalyzer"
    }
    
    private val pm: PackageManager = context.packageManager
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES SERVICES D'ACCESSIBILITÉ MALVEILLANTS
    // ══════════════════════════════════════════════════════════════════════════
    fun detectMaliciousAccessibilityServices(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            
            for (service in enabledServices) {
                val packageName = service.resolveInfo.serviceInfo.packageName
                val appName = service.resolveInfo.loadLabel(pm).toString()
                
                // Vérifier si c'est un spyware connu
                if (MalwareDatabase.isSpyware(packageName) || 
                    MalwareDatabase.isMalware(packageName) ||
                    MalwareDatabase.isFakeParentalControl(packageName)) {
                    
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SPYWARE,
                        threatLevel = ThreatLevel.CRITICAL,
                        description = "Service d'accessibilité malveillant ACTIF - Peut capturer tout ce que vous tapez",
                        recommendation = "DÉSACTIVER IMMÉDIATEMENT dans Paramètres > Accessibilité"
                    ))
                }
                
                // Vérifier les patterns suspects
                if (MalwareDatabase.isSuspicious(packageName)) {
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.HIGH,
                        description = "Service d'accessibilité suspect actif",
                        recommendation = "Vérifier si vous avez activé ce service volontairement"
                    ))
                }
                
                // Vérifier si le nom est trompeur (faux service système)
                if (MalwareDatabase.isFakeSystemApp(appName) && !isRealSystemApp(packageName)) {
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.HIGH,
                        description = "Service d'accessibilité avec nom système trompeur",
                        recommendation = "Cette app se fait passer pour un service système"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection accessibility services", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES DEVICE ADMINS MALVEILLANTS
    // ══════════════════════════════════════════════════════════════════════════
    fun detectMaliciousDeviceAdmins(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val activeAdmins = dpm.activeAdmins ?: return threats
            
            for (admin in activeAdmins) {
                val packageName = admin.packageName
                val appName = getAppName(packageName)
                
                // Vérifier si c'est un malware/spyware connu
                if (MalwareDatabase.isSpyware(packageName) || 
                    MalwareDatabase.isMalware(packageName)) {
                    
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SPYWARE,
                        threatLevel = ThreatLevel.CRITICAL,
                        description = "Administrateur d'appareil MALVEILLANT - Contrôle total sur votre téléphone",
                        recommendation = "DÉSACTIVER dans Paramètres > Sécurité > Administrateurs"
                    ))
                }
                
                // Vérifier les patterns suspects
                if (MalwareDatabase.isSuspicious(packageName) || 
                    MalwareDatabase.isFakeParentalControl(packageName)) {
                    
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.HIGH,
                        description = "Administrateur d'appareil suspect",
                        recommendation = "Vérifier si vous avez activé cet admin volontairement"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection device admins", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES NOTIFICATION LISTENERS MALVEILLANTS
    // ══════════════════════════════════════════════════════════════════════════
    fun detectMaliciousNotificationListeners(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return threats
            
            val listeners = enabledListeners.split(":").filter { it.isNotEmpty() }
            
            for (listener in listeners) {
                val packageName = listener.substringBefore("/")
                val appName = getAppName(packageName)
                
                if (MalwareDatabase.isSpyware(packageName) || 
                    MalwareDatabase.isMalware(packageName) ||
                    MalwareDatabase.isFakeParentalControl(packageName)) {
                    
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SPYWARE,
                        threatLevel = ThreatLevel.CRITICAL,
                        description = "Listener de notifications MALVEILLANT - Lit tous vos messages",
                        recommendation = "DÉSACTIVER dans Paramètres > Notifications > Accès aux notifications"
                    ))
                }
                
                if (MalwareDatabase.isSuspicious(packageName)) {
                    threats.add(ThreatInfo(
                        packageName = packageName,
                        appName = appName,
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.HIGH,
                        description = "Listener de notifications suspect",
                        recommendation = "Vérifier pourquoi cette app lit vos notifications"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection notification listeners", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES APPS CACHÉES / SANS ICÔNE
    // ══════════════════════════════════════════════════════════════════════════
    fun detectHiddenApps(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            }
            
            for (pkg in installedPackages) {
                val isSystemApp = (pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystemApp) continue
                
                // Vérifier si l'app a une icône dans le launcher
                val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                val hasLauncherIcon = launchIntent != null
                
                if (!hasLauncherIcon) {
                    val appName = getAppName(pkg.packageName)
                    
                    // App cachée + spyware connu = CRITIQUE
                    if (MalwareDatabase.isSpyware(pkg.packageName) || 
                        MalwareDatabase.isMalware(pkg.packageName)) {
                        
                        threats.add(ThreatInfo(
                            packageName = pkg.packageName,
                            appName = appName,
                            threatType = ThreatType.SPYWARE,
                            threatLevel = ThreatLevel.CRITICAL,
                            description = "SPYWARE CACHÉ - Pas d'icône visible, fonctionne en arrière-plan",
                            recommendation = "DÉSINSTALLER IMMÉDIATEMENT via Paramètres > Applications"
                        ))
                    }
                    // App cachée + pattern suspect
                    else if (MalwareDatabase.isSuspicious(pkg.packageName) ||
                             MalwareDatabase.isFakeParentalControl(pkg.packageName)) {
                        
                        threats.add(ThreatInfo(
                            packageName = pkg.packageName,
                            appName = appName,
                            threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                            threatLevel = ThreatLevel.HIGH,
                            description = "Application cachée suspecte - Pas d'icône dans le launcher",
                            recommendation = "Vérifier cette application dans Paramètres > Applications"
                        ))
                    }
                    // App cachée avec permissions dangereuses
                    else {
                        val perms = pkg.requestedPermissions?.toList() ?: emptyList()
                        val riskScore = MalwareDatabase.calculatePermissionRisk(perms)
                        
                        if (riskScore >= 50) {
                            threats.add(ThreatInfo(
                                packageName = pkg.packageName,
                                appName = appName,
                                threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                                threatLevel = ThreatLevel.MEDIUM,
                                description = "Application cachée avec permissions sensibles (score: $riskScore)",
                                recommendation = "Vérifier pourquoi cette app n'a pas d'icône"
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection apps cachées", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION ROOT / EXPLOITS / INJECTIONS
    // ══════════════════════════════════════════════════════════════════════════
    fun detectRootAndExploits(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        // Vérifier les chemins root
        for (path in MalwareDatabase.ROOT_PATHS) {
            if (File(path).exists()) {
                threats.add(ThreatInfo(
                    packageName = "system",
                    appName = "Système",
                    threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                    threatLevel = ThreatLevel.HIGH,
                    description = "Fichier root/exploit détecté: $path",
                    recommendation = "Votre appareil semble rooté ou compromis"
                ))
                break // Un seul avertissement suffit
            }
        }
        
        // Vérifier les packages root
        for (pkg in MalwareDatabase.ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                threats.add(ThreatInfo(
                    packageName = pkg,
                    appName = getAppName(pkg),
                    threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                    threatLevel = ThreatLevel.HIGH,
                    description = "Application root/injection détectée",
                    recommendation = "Cette app peut être utilisée pour contourner la sécurité"
                ))
            } catch (_: PackageManager.NameNotFoundException) {
                // Package non installé, OK
            }
        }
        
        // Vérifier si su est exécutable
        try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val result = process.inputStream.bufferedReader().readText()
            if (result.isNotEmpty()) {
                threats.add(ThreatInfo(
                    packageName = "system",
                    appName = "Système",
                    threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                    threatLevel = ThreatLevel.CRITICAL,
                    description = "Commande 'su' disponible - Appareil rooté",
                    recommendation = "Un attaquant peut avoir un accès root complet"
                ))
            }
        } catch (_: Exception) {
            // su non disponible, OK
        }
        
        // Vérifier Frida (outil d'injection très puissant)
        try {
            val fridaPaths = listOf(
                "/data/local/tmp/frida-server",
                "/data/local/tmp/re.frida.server",
                "/sdcard/frida-server"
            )
            for (path in fridaPaths) {
                if (File(path).exists()) {
                    threats.add(ThreatInfo(
                        packageName = "frida",
                        appName = "Frida Server",
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.CRITICAL,
                        description = "Frida Server détecté - Outil d'injection de code",
                        recommendation = "Quelqu'un peut injecter du code dans vos applications"
                    ))
                    break
                }
            }
        } catch (_: Exception) {}
        
        // Vérifier Xposed Framework
        try {
            val xposedPaths = listOf(
                "/system/framework/XposedBridge.jar",
                "/system/bin/app_process.orig",
                "/system/bin/app_process32_xposed"
            )
            for (path in xposedPaths) {
                if (File(path).exists()) {
                    threats.add(ThreatInfo(
                        packageName = "xposed",
                        appName = "Xposed Framework",
                        threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                        threatLevel = ThreatLevel.HIGH,
                        description = "Xposed Framework détecté - Peut modifier le comportement des apps",
                        recommendation = "Des modules Xposed peuvent espionner vos applications"
                    ))
                    break
                }
            }
        } catch (_: Exception) {}
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // DÉTECTION DES SERVICES EN ARRIÈRE-PLAN SUSPECTS
    // ══════════════════════════════════════════════════════════════════════════
    fun detectSuspiciousBackgroundServices(): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        
        try {
            val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.GET_SERVICES)
            }
            
            for (pkg in installedPackages) {
                val isSystemApp = (pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystemApp) continue
                
                val services = pkg.services ?: continue
                
                // Compter les services avec des noms suspects
                val suspiciousServices = services.filter { service ->
                    val name = service.name.lowercase()
                    name.contains("spy") || name.contains("monitor") ||
                    name.contains("track") || name.contains("capture") ||
                    name.contains("record") || name.contains("stealth") ||
                    name.contains("hidden") || name.contains("background") ||
                    name.contains("persistent") || name.contains("daemon")
                }
                
                if (suspiciousServices.isNotEmpty() && 
                    (MalwareDatabase.isSpyware(pkg.packageName) || 
                     MalwareDatabase.isSuspicious(pkg.packageName))) {
                    
                    threats.add(ThreatInfo(
                        packageName = pkg.packageName,
                        appName = getAppName(pkg.packageName),
                        threatType = ThreatType.SPYWARE,
                        threatLevel = ThreatLevel.HIGH,
                        description = "Services d'arrière-plan suspects: ${suspiciousServices.map { it.name.substringAfterLast('.') }.joinToString(", ")}",
                        recommendation = "Cette app exécute des services de surveillance"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur détection services", e)
        }
        
        return threats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // ANALYSE COMPLÈTE DU COMPORTEMENT
    // ══════════════════════════════════════════════════════════════════════════
    fun runFullBehaviorAnalysis(): List<ThreatInfo> {
        val allThreats = mutableListOf<ThreatInfo>()
        
        Log.d(TAG, "Démarrage analyse comportementale complète...")
        
        allThreats.addAll(detectMaliciousAccessibilityServices())
        allThreats.addAll(detectMaliciousDeviceAdmins())
        allThreats.addAll(detectMaliciousNotificationListeners())
        allThreats.addAll(detectHiddenApps())
        allThreats.addAll(detectRootAndExploits())
        allThreats.addAll(detectSuspiciousBackgroundServices())
        
        Log.d(TAG, "Analyse terminée: ${allThreats.size} menaces comportementales détectées")
        
        return allThreats
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ══════════════════════════════════════════════════════════════════════════
    
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
    
    private fun isRealSystemApp(packageName: String): Boolean {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }
}
