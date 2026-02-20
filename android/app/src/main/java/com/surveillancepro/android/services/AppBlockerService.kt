package com.surveillancepro.android.services

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service de blocage d'applications.
 * 
 * Permet de bloquer l'utilisation de certaines apps sur l'appareil.
 * Quand l'utilisateur ouvre une app bloquee, il est automatiquement
 * redirige vers l'ecran d'accueil.
 * 
 * Fonctionne via l'AccessibilityService (pas besoin de root).
 * La liste des apps bloquees est synchronisee depuis le serveur.
 */
object AppBlockerService {

    private const val TAG = "AppBlockerService"
    private val blockedPackages = mutableSetOf<String>()
    private var isEnabled = false
    private var lastBlockedApp = ""
    private var lastBlockTime = 0L

    /**
     * Active le blocage d'apps.
     */
    fun enable() {
        isEnabled = true
        Log.d(TAG, "App blocker enabled")
    }

    /**
     * Desactive le blocage d'apps.
     */
    fun disable() {
        isEnabled = false
        Log.d(TAG, "App blocker disabled")
    }

    /**
     * Verifie si le blocage est actif.
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * Ajoute une app a la liste de blocage.
     */
    fun blockApp(packageName: String) {
        blockedPackages.add(packageName)
        Log.d(TAG, "App blocked: $packageName")
    }

    /**
     * Retire une app de la liste de blocage.
     */
    fun unblockApp(packageName: String) {
        blockedPackages.remove(packageName)
        Log.d(TAG, "App unblocked: $packageName")
    }

    /**
     * Definit la liste complete des apps bloquees.
     */
    fun setBlockedApps(packages: List<String>) {
        blockedPackages.clear()
        blockedPackages.addAll(packages)
        Log.d(TAG, "Blocked apps updated: ${packages.size} apps")
    }

    /**
     * Retourne la liste des apps bloquees.
     */
    fun getBlockedApps(): Set<String> = blockedPackages.toSet()

    /**
     * Verifie si une app est bloquee.
     */
    fun isBlocked(packageName: String): Boolean {
        return isEnabled && blockedPackages.contains(packageName)
    }

    /**
     * Appele par l'AccessibilityService quand une app est ouverte.
     * Retourne true si l'app a ete bloquee.
     */
    fun onAppOpened(context: Context, packageName: String): Boolean {
        if (!isEnabled) return false
        if (!blockedPackages.contains(packageName)) return false

        // Eviter les boucles de blocage
        val now = System.currentTimeMillis()
        if (packageName == lastBlockedApp && now - lastBlockTime < 2000) {
            return false
        }

        lastBlockedApp = packageName
        lastBlockTime = now

        // Logger l'evenement
        logBlockedAttempt(context, packageName)

        // Rediriger vers l'ecran d'accueil
        redirectToHome(context)

        return true
    }

    private fun logBlockedAttempt(context: Context, packageName: String) {
        val storage = DeviceStorage.getInstance(context)
        if (!storage.hasAccepted || storage.deviceToken == null) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val queue = EventQueue.getInstance(context)

        // Obtenir le nom lisible de l'app
        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }

        queue.enqueue("app_blocked", mapOf(
            "packageName" to packageName,
            "appName" to appName,
            "timestamp" to timestamp,
        ))

        Log.d(TAG, "Blocked attempt: $appName ($packageName)")
    }

    private fun redirectToHome(context: Context) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redirect to home: ${e.message}")
        }
    }

    /**
     * Configure le blocage depuis les donnees du serveur.
     */
    fun configureFromServer(config: Map<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        val apps = config["blockedApps"] as? List<String> ?: emptyList()
        val enabled = config["enabled"] as? Boolean ?: false

        setBlockedApps(apps)
        if (enabled) enable() else disable()

        Log.d(TAG, "Configured from server: ${apps.size} apps, enabled=$enabled")
    }

    // ─── Apps couramment bloquees (presets) ───

    val PRESET_SOCIAL = listOf(
        "com.facebook.katana",      // Facebook
        "com.instagram.android",    // Instagram
        "com.twitter.android",      // Twitter/X
        "com.snapchat.android",     // Snapchat
        "com.zhiliaoapp.musically", // TikTok
        "com.tiktok.android",       // TikTok
        "com.pinterest",            // Pinterest
        "com.reddit.frontpage",     // Reddit
        "com.linkedin.android",     // LinkedIn
    )

    val PRESET_GAMING = listOf(
        "com.supercell.clashofclans",
        "com.supercell.clashroyale",
        "com.king.candycrushsaga",
        "com.kiloo.subwaysurf",
        "com.imangi.templerun",
        "com.mojang.minecraftpe",
        "com.activision.callofduty.shooter",
        "com.pubg.imobile",
        "com.garena.game.codm",
        "com.roblox.client",
    )

    val PRESET_STREAMING = listOf(
        "com.google.android.youtube",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "tv.twitch.android.app",
        "com.spotify.music",
        "com.apple.android.music",
    )

    val PRESET_DATING = listOf(
        "com.tinder",
        "com.bumble.app",
        "com.badoo.mobile",
        "com.okcupid.okcupid",
        "com.match.android.matchmobile",
    )
}
