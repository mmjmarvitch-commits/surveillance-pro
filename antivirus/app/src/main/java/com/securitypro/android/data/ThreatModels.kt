package com.securitypro.android.data

import org.json.JSONObject

enum class ThreatLevel {
    SAFE, LOW, MEDIUM, HIGH, CRITICAL
}

enum class ThreatType {
    MALWARE, SPYWARE, ADWARE, TROJAN, RANSOMWARE,
    DANGEROUS_PERMISSIONS, UNKNOWN_SOURCE, SUSPICIOUS_BEHAVIOR
}

data class ThreatInfo(
    val packageName: String,
    val appName: String,
    val threatType: ThreatType,
    val threatLevel: ThreatLevel,
    val description: String,
    val recommendation: String,
    val permissions: List<String> = emptyList(),
    val isSystemApp: Boolean = false,
    val detectedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        put("threatType", threatType.name)
        put("threatLevel", threatLevel.name)
        put("description", description)
        put("recommendation", recommendation)
        put("detectedAt", detectedAt)
    }
}

data class ScanResult(
    val totalApps: Int,
    val scannedApps: Int,
    val threats: List<ThreatInfo>,
    val scanDurationMs: Long,
    val scanType: ScanType,
    val systemStatus: SystemStatus
)

enum class ScanType {
    QUICK, FULL, SINGLE_APP
}

data class SystemStatus(
    val isRooted: Boolean = false,
    val unknownSourcesEnabled: Boolean = false,
    val developerOptionsEnabled: Boolean = false,
    val adbEnabled: Boolean = false,
    val screenLockEnabled: Boolean = true,
    val securityPatchLevel: String = ""
) {
    fun getRiskScore(): Int {
        var score = 0
        if (isRooted) score += 30
        if (unknownSourcesEnabled) score += 20
        if (developerOptionsEnabled) score += 5
        if (adbEnabled) score += 15
        if (!screenLockEnabled) score += 15
        return score.coerceAtMost(100)
    }
    
    fun isSecure(): Boolean = getRiskScore() < 25
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isSystemApp: Boolean,
    val installTime: Long,
    val permissions: List<String>
)
