package com.securitypro.android.scanner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.securitypro.android.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "AppScanner"
    }
    
    private val pm: PackageManager = context.packageManager
    private val behaviorAnalyzer = BehaviorAnalyzer(context)
    private val dataLeakDetector = DataLeakDetector(context)
    
    suspend fun fullScan(): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val apps = getInstalledApps()
        val threats = mutableListOf<ThreatInfo>()
        
        Log.d(TAG, "Démarrage scan COMPLET de ${apps.size} applications...")
        
        // Scan de chaque application
        apps.forEach { pkg ->
            threats.addAll(scanPackage(pkg))
        }
        
        // Analyse comportementale avancée
        Log.d(TAG, "Analyse comportementale...")
        threats.addAll(behaviorAnalyzer.runFullBehaviorAnalysis())
        
        // Analyse des fuites de données
        Log.d(TAG, "Analyse des fuites de données...")
        threats.addAll(dataLeakDetector.runFullDataLeakAnalysis())
        
        // Dédoublonner les menaces
        val uniqueThreats = threats.distinctBy { "${it.packageName}_${it.threatType}" }
        
        Log.d(TAG, "Scan terminé: ${uniqueThreats.size} menaces uniques détectées")
        
        ScanResult(
            totalApps = apps.size,
            scannedApps = apps.size,
            threats = uniqueThreats,
            scanDurationMs = System.currentTimeMillis() - start,
            scanType = ScanType.FULL,
            systemStatus = checkSystem()
        )
    }
    
    suspend fun quickScan(): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val apps = getInstalledApps().filter { 
            it.firstInstallTime > cutoff || it.lastUpdateTime > cutoff 
        }
        val threats = mutableListOf<ThreatInfo>()
        
        Log.d(TAG, "Démarrage scan RAPIDE de ${apps.size} applications récentes...")
        
        // Scan des apps récentes
        apps.forEach { pkg ->
            threats.addAll(scanPackage(pkg))
        }
        
        // Vérifications critiques même en scan rapide
        Log.d(TAG, "Vérifications critiques...")
        threats.addAll(behaviorAnalyzer.detectMaliciousAccessibilityServices())
        threats.addAll(behaviorAnalyzer.detectMaliciousDeviceAdmins())
        threats.addAll(behaviorAnalyzer.detectMaliciousNotificationListeners())
        threats.addAll(behaviorAnalyzer.detectRootAndExploits())
        
        // Détection des fuites de données (comportements bizarres)
        Log.d(TAG, "Détection fuites de données...")
        threats.addAll(dataLeakDetector.detectDataExfiltrationCapableApps())
        threats.addAll(dataLeakDetector.detectWeirdPhoneBehavior())
        
        val allApps = getInstalledApps()
        val uniqueThreats = threats.distinctBy { "${it.packageName}_${it.threatType}" }
        
        Log.d(TAG, "Scan rapide terminé: ${uniqueThreats.size} menaces détectées")
        
        ScanResult(
            totalApps = allApps.size,
            scannedApps = apps.size,
            threats = uniqueThreats,
            scanDurationMs = System.currentTimeMillis() - start,
            scanType = ScanType.QUICK,
            systemStatus = checkSystem()
        )
    }
    
    fun scanSingleApp(packageName: String): List<ThreatInfo> {
        return try {
            val pkg = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            scanPackage(pkg)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur scan $packageName", e)
            emptyList()
        }
    }
    
    private fun scanPackage(pkg: PackageInfo): List<ThreatInfo> {
        val threats = mutableListOf<ThreatInfo>()
        val name = getAppName(pkg)
        val perms = pkg.requestedPermissions?.toList() ?: emptyList()
        val isSystem = (pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION MALWARE CONNU
        // ══════════════════════════════════════════════════════════════════════════
        if (MalwareDatabase.isMalware(pkg.packageName)) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.MALWARE,
                threatLevel = ThreatLevel.CRITICAL,
                description = "⚠️ MALWARE DÉTECTÉ - Trojan/RAT/Ransomware connu",
                recommendation = "DÉSINSTALLER IMMÉDIATEMENT - Cette app peut voler vos données",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION SPYWARE
        // ══════════════════════════════════════════════════════════════════════════
        if (MalwareDatabase.isSpyware(pkg.packageName)) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.SPYWARE,
                threatLevel = ThreatLevel.CRITICAL,
                description = "🔴 SPYWARE DÉTECTÉ - Application d'espionnage commerciale",
                recommendation = "DÉSINSTALLER - Quelqu'un vous surveille avec cette app",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION FAUX CONTRÔLE PARENTAL
        // ══════════════════════════════════════════════════════════════════════════
        if (MalwareDatabase.isFakeParentalControl(pkg.packageName)) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.SPYWARE,
                threatLevel = ThreatLevel.CRITICAL,
                description = "🔴 FAUX CONTRÔLE PARENTAL - C'est un spyware déguisé",
                recommendation = "DÉSINSTALLER - Cette app se fait passer pour du contrôle parental",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION ADWARE
        // ══════════════════════════════════════════════════════════════════════════
        if (MalwareDatabase.isAdware(pkg.packageName)) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.ADWARE,
                threatLevel = ThreatLevel.MEDIUM,
                description = "Adware détecté - Publicités intrusives",
                recommendation = "Envisager la désinstallation pour améliorer les performances",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION PATTERN SUSPECT
        // ══════════════════════════════════════════════════════════════════════════
        if (MalwareDatabase.isSuspicious(pkg.packageName) && !isSystem) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                threatLevel = ThreatLevel.HIGH,
                description = "⚠️ Nom de package suspect (spy/track/monitor/stealth)",
                recommendation = "Vérifier l'origine - Cette app a un nom typique de spyware",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION FAUSSE APP SYSTÈME
        // ══════════════════════════════════════════════════════════════════════════
        if (!isSystem && MalwareDatabase.isFakeSystemApp(name)) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.SUSPICIOUS_BEHAVIOR,
                threatLevel = ThreatLevel.HIGH,
                description = "⚠️ FAUSSE APP SYSTÈME - Se fait passer pour un service Android",
                recommendation = "Cette app n'est PAS une vraie app système - Vérifier son origine",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION PATTERN SPYWARE (combinaison de permissions)
        // ══════════════════════════════════════════════════════════════════════════
        if (!isSystem && MalwareDatabase.hasSpywarePermissionPattern(perms)) {
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.SPYWARE,
                threatLevel = ThreatLevel.HIGH,
                description = "🔴 PATTERN SPYWARE - Combinaison de permissions typique d'un espion",
                recommendation = "Cette app a les permissions d'un spyware (SMS+Audio+GPS+Internet)",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // ANALYSE DES PERMISSIONS DANGEREUSES
        // ══════════════════════════════════════════════════════════════════════════
        val risk = MalwareDatabase.calculatePermissionRisk(perms)
        if (risk >= 50 && !isSystem) {
            val level = when {
                risk >= 80 -> ThreatLevel.CRITICAL
                risk >= 65 -> ThreatLevel.HIGH
                else -> ThreatLevel.MEDIUM
            }
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.DANGEROUS_PERMISSIONS,
                threatLevel = level,
                description = "Permissions dangereuses (score risque: $risk/100)",
                recommendation = "Vérifier si ces permissions sont justifiées pour cette app",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        // ══════════════════════════════════════════════════════════════════════════
        // DÉTECTION SOURCE INCONNUE
        // ══════════════════════════════════════════════════════════════════════════
        if (!isSystem && isFromUnknownSource(pkg)) {
            // Plus grave si c'est aussi suspect
            val isSuspect = MalwareDatabase.isSuspicious(pkg.packageName) || risk >= 50
            threats.add(ThreatInfo(
                packageName = pkg.packageName,
                appName = name,
                threatType = ThreatType.UNKNOWN_SOURCE,
                threatLevel = if (isSuspect) ThreatLevel.HIGH else ThreatLevel.MEDIUM,
                description = "Installée hors Play Store" + if (isSuspect) " + permissions suspectes" else "",
                recommendation = "Préférer les apps du Play Store pour plus de sécurité",
                permissions = perms,
                isSystemApp = isSystem
            ))
        }
        
        return threats
    }
    
    fun checkSystem(): SystemStatus {
        return SystemStatus(
            isRooted = checkRoot(),
            unknownSourcesEnabled = checkUnknownSources(),
            developerOptionsEnabled = checkDevOptions(),
            adbEnabled = checkAdb(),
            screenLockEnabled = checkScreenLock(),
            securityPatchLevel = getSecurityPatch()
        )
    }
    
    private fun getInstalledApps(): List<PackageInfo> {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }
    }
    
    private fun getAppName(pkg: PackageInfo): String {
        return try {
            pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg.packageName
        } catch (e: Exception) {
            pkg.packageName
        }
    }
    
    private fun isFromUnknownSource(pkg: PackageInfo): Boolean {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg.packageName)
        }
        val trusted = setOf("com.android.vending", "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.huawei.appmarket", "com.xiaomi.market")
        return installer == null || !trusted.contains(installer)
    }
    
    private fun checkRoot(): Boolean {
        // Utiliser la base de données complète de chemins root
        for (p in MalwareDatabase.ROOT_PATHS) {
            if (File(p).exists()) return true
        }
        
        // Vérifier les packages root connus
        for (pkg in MalwareDatabase.ROOT_PACKAGES) {
            try { pm.getPackageInfo(pkg, 0); return true } catch (_: Exception) {}
        }
        
        // Tenter d'exécuter su
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val result = process.inputStream.bufferedReader().readText()
            result.isNotEmpty()
        } catch (_: Exception) { false }
    }
    
    private fun checkUnknownSources(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pm.canRequestPackageInstalls()
            } else {
                @Suppress("DEPRECATION")
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            }
        } catch (_: Exception) { false }
    }
    
    private fun checkDevOptions(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (_: Exception) { false }
    }
    
    private fun checkAdb(): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) { false }
    }
    
    private fun checkScreenLock(): Boolean {
        return try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isDeviceSecure
        } catch (_: Exception) { true }
    }
    
    private fun getSecurityPatch(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH
        } else "unknown"
    }
    
    fun getAppsList(): List<AppInfo> {
        return getInstalledApps().map { pkg ->
            val isSystem = (pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            AppInfo(
                packageName = pkg.packageName,
                appName = getAppName(pkg),
                versionName = pkg.versionName ?: "?",
                isSystemApp = isSystem,
                installTime = pkg.firstInstallTime,
                permissions = pkg.requestedPermissions?.toList() ?: emptyList()
            )
        }
    }
}
