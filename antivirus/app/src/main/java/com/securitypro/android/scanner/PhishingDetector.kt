package com.securitypro.android.scanner

import android.util.Log
import android.util.Patterns
import java.net.URL

class PhishingDetector {
    
    companion object {
        private const val TAG = "PhishingDetector"
        
        // Domaines de phishing connus
        private val KNOWN_PHISHING_DOMAINS = setOf(
            "secure-login", "account-verify", "update-info", "confirm-identity",
            "banking-secure", "paypal-secure", "apple-id-verify", "google-verify",
            "facebook-login", "instagram-verify", "whatsapp-verify", "amazon-secure",
            "netflix-update", "microsoft-verify", "outlook-secure", "yahoo-verify",
            "0racle", "g00gle", "faceb00k", "amaz0n", "paypa1", "app1e",
            "micros0ft", "netf1ix", "instagr4m", "wh4tsapp"
        )
        
        // Mots-clés suspects dans les URLs
        private val SUSPICIOUS_KEYWORDS = setOf(
            "login", "signin", "verify", "confirm", "secure", "update",
            "account", "password", "credential", "banking", "wallet",
            "suspended", "locked", "urgent", "immediately", "expire"
        )
        
        // TLDs suspects (souvent utilisés pour le phishing)
        private val SUSPICIOUS_TLDS = setOf(
            ".tk", ".ml", ".ga", ".cf", ".gq", ".xyz", ".top", ".work",
            ".click", ".link", ".info", ".online", ".site", ".website"
        )
        
        // Domaines légitimes (pour détecter les imitations)
        private val LEGITIMATE_DOMAINS = mapOf(
            "google" to listOf("google.com", "google.fr", "googleapis.com"),
            "facebook" to listOf("facebook.com", "fb.com", "fbcdn.net"),
            "apple" to listOf("apple.com", "icloud.com"),
            "microsoft" to listOf("microsoft.com", "live.com", "outlook.com", "office.com"),
            "amazon" to listOf("amazon.com", "amazon.fr", "amazonaws.com"),
            "paypal" to listOf("paypal.com"),
            "netflix" to listOf("netflix.com"),
            "instagram" to listOf("instagram.com"),
            "whatsapp" to listOf("whatsapp.com", "whatsapp.net"),
            "twitter" to listOf("twitter.com", "x.com"),
            "linkedin" to listOf("linkedin.com"),
            "yahoo" to listOf("yahoo.com", "yahoo.fr")
        )
    }
    
    data class PhishingResult(
        val isPhishing: Boolean,
        val riskScore: Int, // 0-100
        val reasons: List<String>,
        val recommendation: String
    )
    
    fun analyzeUrl(urlString: String): PhishingResult {
        val reasons = mutableListOf<String>()
        var riskScore = 0
        
        try {
            // Vérifier si c'est une URL valide
            if (!Patterns.WEB_URL.matcher(urlString).matches()) {
                return PhishingResult(false, 0, emptyList(), "URL invalide")
            }
            
            val url = URL(if (urlString.startsWith("http")) urlString else "https://$urlString")
            val host = url.host.lowercase()
            val path = url.path.lowercase()
            val fullUrl = urlString.lowercase()
            
            // 1. Vérifier les domaines de phishing connus
            for (phishingDomain in KNOWN_PHISHING_DOMAINS) {
                if (host.contains(phishingDomain)) {
                    reasons.add("Domaine de phishing connu: $phishingDomain")
                    riskScore += 40
                }
            }
            
            // 2. Vérifier les imitations de domaines légitimes
            for ((brand, legitimateDomains) in LEGITIMATE_DOMAINS) {
                if (host.contains(brand) && !legitimateDomains.any { host.endsWith(it) }) {
                    reasons.add("Imitation de $brand détectée")
                    riskScore += 50
                }
            }
            
            // 3. Vérifier les TLDs suspects
            for (tld in SUSPICIOUS_TLDS) {
                if (host.endsWith(tld)) {
                    reasons.add("Extension de domaine suspecte: $tld")
                    riskScore += 15
                }
            }
            
            // 4. Vérifier les mots-clés suspects dans l'URL
            var keywordCount = 0
            for (keyword in SUSPICIOUS_KEYWORDS) {
                if (fullUrl.contains(keyword)) {
                    keywordCount++
                }
            }
            if (keywordCount >= 2) {
                reasons.add("Mots-clés suspects: $keywordCount trouvés")
                riskScore += keywordCount * 5
            }
            
            // 5. Vérifier l'utilisation d'IP au lieu de domaine
            if (host.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) {
                reasons.add("Adresse IP au lieu de domaine")
                riskScore += 30
            }
            
            // 6. Vérifier les sous-domaines excessifs
            val subdomainCount = host.count { it == '.' }
            if (subdomainCount > 3) {
                reasons.add("Trop de sous-domaines ($subdomainCount)")
                riskScore += 20
            }
            
            // 7. Vérifier les caractères suspects (homoglyphes)
            if (host.contains("0") || host.contains("1") || host.contains("3") || host.contains("4")) {
                val hasLetterReplacement = LEGITIMATE_DOMAINS.keys.any { brand ->
                    val normalized = host.replace("0", "o").replace("1", "l").replace("3", "e").replace("4", "a")
                    normalized.contains(brand) && !host.contains(brand)
                }
                if (hasLetterReplacement) {
                    reasons.add("Caractères de substitution détectés (homoglyphes)")
                    riskScore += 40
                }
            }
            
            // 8. Vérifier si HTTP au lieu de HTTPS pour des sites sensibles
            if (url.protocol == "http" && SUSPICIOUS_KEYWORDS.any { fullUrl.contains(it) }) {
                reasons.add("Connexion non sécurisée (HTTP) pour un site sensible")
                riskScore += 15
            }
            
            // 9. URL très longue (souvent utilisée pour cacher le vrai domaine)
            if (fullUrl.length > 100) {
                reasons.add("URL anormalement longue")
                riskScore += 10
            }
            
            // 10. Caractères encodés suspects
            if (fullUrl.contains("%") && (fullUrl.contains("%2f") || fullUrl.contains("%3a"))) {
                reasons.add("Caractères encodés suspects")
                riskScore += 15
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Erreur analyse URL", e)
            return PhishingResult(false, 0, listOf("Erreur d'analyse"), "URL invalide")
        }
        
        riskScore = riskScore.coerceAtMost(100)
        
        val isPhishing = riskScore >= 40
        val recommendation = when {
            riskScore >= 70 -> "🔴 DANGER - Ne pas ouvrir ce lien !"
            riskScore >= 40 -> "⚠️ SUSPECT - Vérifiez l'URL avant de continuer"
            riskScore >= 20 -> "⚡ ATTENTION - Soyez prudent"
            else -> "✓ Aucun risque détecté"
        }
        
        return PhishingResult(isPhishing, riskScore, reasons, recommendation)
    }
    
    fun isPhishingUrl(url: String): Boolean {
        return analyzeUrl(url).isPhishing
    }
    
    fun getRiskScore(url: String): Int {
        return analyzeUrl(url).riskScore
    }
}
